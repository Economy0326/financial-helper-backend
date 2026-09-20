package com.financialhelper.ai.analysis;

import com.financialhelper.ai.grounded.GroundedEvidenceUnavailableException;
import com.financialhelper.ai.OpenAiStructuredClient;
import com.financialhelper.ai.followup.FollowUpQuestion;
import com.financialhelper.ai.followup.FollowUpQuestionRepository;
import com.financialhelper.account.AccountProperties;
import com.financialhelper.consultation.ConsultationCategory;
import com.financialhelper.consultation.ConsultationScenario;
import com.financialhelper.procedure.FinancialActionPlanData;
import com.financialhelper.procedure.FinancialActionPlanService;
import com.financialhelper.procedure.PlanStatus;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AnalysisWorkerFailureClassificationTest {
    @Test
    void distinguishes_fap_generation_mapping_and_approved_evidence_failures() {
        assertThat(AnalysisWorker.groundedFailureCode(
                new GroundedEvidenceUnavailableException("financial action plan is not READY: UNSUPPORTED")))
                .isEqualTo("FAP_UNAVAILABLE");
        assertThat(AnalysisWorker.groundedFailureCode(
                new GroundedEvidenceUnavailableException("retrieval generation changed")))
                .isEqualTo("RETRIEVAL_GENERATION_MISMATCH");
        assertThat(AnalysisWorker.groundedFailureCode(
                new GroundedEvidenceUnavailableException("source chunk index is not READY")))
                .isEqualTo("RETRIEVAL_MAPPING_MISSING");
        assertThat(AnalysisWorker.groundedFailureCode(
                new GroundedEvidenceUnavailableException("official evidence is unavailable")))
                .isEqualTo("NO_APPROVED_EVIDENCE");
    }

    @Test
    void distinguishes_law_api_configuration_transport_and_validation_failures() {
        assertThat(AnalysisWorker.groundedFailureCode(
                new GroundedEvidenceUnavailableException(
                        "reviewed law evidence is unavailable",
                        new RuntimeException("Korean Law Open API client is disabled"))))
                .isEqualTo("LAW_API_CONFIGURATION_MISSING");
        assertThat(AnalysisWorker.groundedFailureCode(
                new GroundedEvidenceUnavailableException(
                        "reviewed law evidence is unavailable",
                        new RuntimeException("Korean Law Open API HTTP error 503"))))
                .isEqualTo("LAW_API_HTTP_FAILED");
        assertThat(AnalysisWorker.groundedFailureCode(
                new GroundedEvidenceUnavailableException(
                        "reviewed law evidence is unavailable",
                        new RuntimeException("Open API response does not contain requested article"))))
                .isEqualTo("LAW_REQUIRED_ARTICLE_NOT_FOUND");
        assertThat(AnalysisWorker.groundedFailureCode(
                new GroundedEvidenceUnavailableException(
                        "reviewed law evidence is unavailable",
                        new RuntimeException("law evidence effective date is outside reviewed versions: 20260428"))))
                .isEqualTo("LAW_EVIDENCE_VALIDATION_FAILED");
        assertThat(AnalysisWorker.groundedFailureCode(
                new GroundedEvidenceUnavailableException(
                        "reviewed law evidence is unavailable",
                        new RuntimeException("Korean Law Open API returned malformed JSON"))))
                .isEqualTo("LAW_RESPONSE_PARSE_FAILED");
    }

    @Test
    void exhaustedStructuredClarificationDoesNotOpenAnalysisSupplement() {
        AnalysisPersistenceService persistence = mock(AnalysisPersistenceService.class);
        OpenAiStructuredClient openAi = mock(OpenAiStructuredClient.class);
        AnalysisAiBusinessValidator validator = mock(AnalysisAiBusinessValidator.class);
        JsonMapper jsonMapper = mock(JsonMapper.class);
        com.financialhelper.ai.grounded.AnalysisEvidenceSnapshotService evidenceSnapshots =
                mock(com.financialhelper.ai.grounded.AnalysisEvidenceSnapshotService.class);
        com.financialhelper.ai.grounded.GroundedOutputValidator groundedValidator =
                mock(com.financialhelper.ai.grounded.GroundedOutputValidator.class);
        AccountProperties accountProperties = mock(AccountProperties.class);
        FinancialActionPlanService actionPlans = mock(FinancialActionPlanService.class);
        FollowUpQuestionRepository questions = mock(FollowUpQuestionRepository.class);

        AnalysisWorker worker = new AnalysisWorker(
                persistence, openAi, validator, jsonMapper, evidenceSnapshots,
                groundedValidator, accountProperties, actionPlans, questions);

        UUID jobId = UUID.randomUUID();
        UUID consultationId = UUID.randomUUID();
        AnalysisData.Snapshot snapshot = new AnalysisData.Snapshot(
                jobId, consultationId, ConsultationCategory.CARD,
                ConsultationScenario.CARD_LOSS_UNAUTHORIZED_USE,
                4L, 2L, null);
        FinancialActionPlanData plan = new FinancialActionPlanData(
                UUID.randomUUID(), consultationId, UUID.randomUUID(), UUID.randomUUID(),
                "CARD_LOSS_UNAUTHORIZED_USE", 1, 4L, 2L,
                PlanStatus.NEEDS_CLARIFICATION,
                List.of(new FinancialActionPlanData.Action(
                        "report-loss", 1, "분실·도난 신고", "공식 채널로 신고합니다.",
                        com.financialhelper.procedure.ConditionResult.TRUE, "kb-card-loss-report-ars")),
                List.of(), List.of(), List.of("unauthorizedPayment"), List.of(), List.of());
        FollowUpQuestion clarification = new FollowUpQuestion(
                null, 4L, 2, "확인해도 잘 모르겠어요", "", "{}", "backend",
                OffsetDateTime.now(ZoneOffset.UTC), "unauthorizedPayment", "YES_NO_UNKNOWN",
                true, "CLARIFY_UNAUTHORIZED_PAYMENT");
        clarification.answer("UNKNOWN", "확인해도 잘 모르겠어요", OffsetDateTime.now(ZoneOffset.UTC));

        when(persistence.beginProcessing(jobId)).thenReturn(Optional.of(snapshot));
        when(actionPlans.buildForCurrent(consultationId)).thenReturn(plan);
        when(questions.findByConsultation_IdAndCaseInputRevisionOrderBySequenceNoAsc(
                consultationId, 4L)).thenReturn(List.of(clarification));

        worker.run(jobId);

        verify(persistence).completeWithoutSupplement(eq(snapshot), any(AnalysisAiResult.class));
        verify(persistence, never()).complete(eq(snapshot), any(AnalysisAiResult.class));
        verify(openAi, never()).generateStructured(any(), any(), eq(AnalysisAiResult.class));
    }
}
