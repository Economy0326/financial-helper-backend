package com.financialhelper.ai.report;

import com.financialhelper.procedure.ConditionResult;
import com.financialhelper.procedure.FinancialActionPlanData;
import com.financialhelper.procedure.PlanStatus;
import com.financialhelper.ai.grounded.AnalysisEvidenceSnapshotService;
import com.financialhelper.ai.grounded.GroundedEvidenceUnavailableException;
import com.financialhelper.ai.grounded.GroundedOutputValidator;
import com.financialhelper.account.Account;
import com.financialhelper.account.AccountSessionService;
import com.financialhelper.ai.analysis.AnalysisJobRepository;
import com.financialhelper.ai.summary.ConsultationSummaryRepository;
import com.financialhelper.consultation.Consultation;
import com.financialhelper.consultation.ConsultationRepository;
import com.financialhelper.consultation.ConsultationScenario;
import com.financialhelper.guest.GuestSessionService;
import tools.jackson.databind.json.JsonMapper;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ConsultationReportServiceTest {
    @Test
    void accountReadDoesNotReturnReportWithStaleGroundedSnapshot() {
        UUID consultationId = UUID.randomUUID();
        UUID snapshotId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        Account account = mock(Account.class);
        AccountSessionService accountSession = mock(AccountSessionService.class);
        ConsultationReportRepository reports = mock(ConsultationReportRepository.class);
        AnalysisEvidenceSnapshotService snapshots = mock(AnalysisEvidenceSnapshotService.class);
        ConsultationReport report = mock(ConsultationReport.class);
        Consultation consultation = mock(Consultation.class);
        when(accountSession.currentAccount()).thenReturn(Optional.of(account));
        when(account.getId()).thenReturn(accountId);
        when(reports.findByConsultation_IdAndConsultation_Account_Id(consultationId, accountId))
                .thenReturn(Optional.of(report));
        when(report.getConsultation()).thenReturn(consultation);
        when(consultation.getScenario()).thenReturn(ConsultationScenario.CARD_LOSS_UNAUTHORIZED_USE);
        when(report.getAnalysisEvidenceSnapshotId()).thenReturn(snapshotId);
        when(snapshots.loadForId(snapshotId)).thenThrow(
                new GroundedEvidenceUnavailableException("stored action relation is unavailable"));
        ConsultationReportPersistenceService persistence = new ConsultationReportPersistenceService(
                mock(ConsultationRepository.class), mock(ConsultationSummaryRepository.class),
                mock(AnalysisJobRepository.class), reports, mock(GuestSessionService.class),
                JsonMapper.builder().build(), snapshots, mock(GroundedOutputValidator.class),
                accountSession);

        assertThat(persistence.getStateForAccount(consultationId).kind()).isEqualTo("not-prepared");
    }

    @Test
    void procedureFieldsAreComposedFromTheBackendActionPlan() {
        UUID consultationId = UUID.randomUUID();
        FinancialActionPlanData plan = new FinancialActionPlanData(
                UUID.randomUUID(), consultationId, UUID.randomUUID(), UUID.randomUUID(),
                "CARD_COMPENSATION_PROCESS", 1, 2, 3, PlanStatus.READY,
                List.of(
                        new FinancialActionPlanData.Action(
                                "confirm-compensation-application", 9, "보상 신청 절차 확인",
                                "공식 채널에서 신청 절차를 확인합니다.", ConditionResult.TRUE,
                                "kb-card-compensation-process"),
                        new FinancialActionPlanData.Action(
                                "track-compensation-process", 9, "사고 접수·조사 진행 확인",
                                "공식 채널에서 진행 상태를 확인합니다.", ConditionResult.TRUE,
                                "kb-card-compensation-process")),
                List.of(new FinancialActionPlanData.Document(
                        "case-record", "사건 관련 확인 자료", "ON_REQUEST",
                        ConditionResult.TRUE, "kb-card-compensation-process")),
                List.of(), List.of(), List.of(), List.of());

        ConsultationReportAiResult modelResult = new ConsultationReportAiResult();
        ConsultationReportAiResult.FirstAction wrongFirst =
                new ConsultationReportAiResult.FirstAction();
        wrongFirst.actionId = "invented-action";
        wrongFirst.title = "다른 행동";
        wrongFirst.description = "모델이 임의로 만든 행동";
        modelResult.firstAction = wrongFirst;
        ConsultationReportAiResult.ActionStep wrongStep =
                new ConsultationReportAiResult.ActionStep();
        wrongStep.actionId = "invented-action";
        wrongStep.order = 2;
        wrongStep.title = "다른 행동";
        wrongStep.description = "모델이 임의로 만든 행동";
        ConsultationReportAiResult.ActionStep duplicateOrderStep =
                new ConsultationReportAiResult.ActionStep();
        duplicateOrderStep.actionId = "invented-action-2";
        duplicateOrderStep.order = 2;
        duplicateOrderStep.title = "또 다른 행동";
        duplicateOrderStep.description = "모델이 임의로 만든 두 번째 행동";
        modelResult.actionSteps = List.of(wrongStep, duplicateOrderStep);
        ConsultationReportAiResult.RequiredDocument wrongDocument =
                new ConsultationReportAiResult.RequiredDocument();
        wrongDocument.documentId = "invented-document";
        wrongDocument.name = "임의 자료";
        wrongDocument.reason = "임의 사유";
        modelResult.requiredDocuments = List.of(wrongDocument);

        ConsultationReportAiResult composed =
                ConsultationReportService.composeProcedureFields(modelResult, plan);

        assertThat(composed.firstAction.actionId).isEqualTo("confirm-compensation-application");
        assertThat(composed.firstAction.title).isEqualTo("보상 신청 절차 확인");
        assertThat(composed.actionSteps).extracting(step -> step.order)
                .containsExactly(1, 2);
        assertThat(composed.actionSteps).extracting(step -> step.actionId)
                .containsExactly("confirm-compensation-application", "track-compensation-process");
        assertThat(composed.actionSteps.get(1).description)
                .isEqualTo("공식 채널에서 진행 상태를 확인합니다.");
        assertThat(composed.requiredDocuments).singleElement().satisfies(document -> {
            assertThat(document.documentId).isEqualTo("case-record");
            assertThat(document.name).isEqualTo("사건 관련 확인 자료");
        });
    }
}
