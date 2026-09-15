package com.financialhelper.ai.grounded;

import com.financialhelper.ai.AiOutputContractException;
import com.financialhelper.ai.analysis.AnalysisAiResult;
import com.financialhelper.ai.report.ConsultationReportAiResult;
import com.financialhelper.procedure.ConditionResult;
import com.financialhelper.procedure.FinancialActionPlanData;
import com.financialhelper.procedure.PlanStatus;

import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatCode;

class GroundedOutputValidatorTest {
    private final GroundedOutputValidator validator = new GroundedOutputValidator();

    @Test
    void analysisRejectsCitationOutsideSnapshot() {
        AnalysisAiResult result = new AnalysisAiResult();
        result.evidenceCitations = List.of(citation("source:missing", "제40조"));

        assertThatThrownBy(() -> validator.validateAnalysis(result, snapshot()))
                .isInstanceOf(AiOutputContractException.class);
    }

    @Test
    void reportMustUseProcedureActionAndDocumentIdentities() {
        AnalysisEvidenceSnapshotData snapshot = snapshot();
        ConsultationReportAiResult result = validReport(snapshot);
        result.actionSteps.get(0).description = "모델이 만든 다른 행동";

        assertThatThrownBy(() -> validator.validateReport(result, snapshot))
                .isInstanceOf(AiOutputContractException.class);
    }

    @Test
    void validGroundedReportPasses() {
        AnalysisEvidenceSnapshotData snapshot = snapshot();
        assertThatCode(() -> validator.validateReport(validReport(snapshot), snapshot))
                .doesNotThrowAnyException();
    }

    private AnalysisEvidenceSnapshotData snapshot() {
        UUID sourceDocumentId = UUID.randomUUID();
        UUID chunkId = UUID.randomUUID();
        FinancialActionPlanData plan = new FinancialActionPlanData(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "CARD_LOSS_UNAUTHORIZED_USE", 1, 1, 1, PlanStatus.READY,
                List.of(new FinancialActionPlanData.Action(
                        "report-loss", 1, "분실·도난 신고", "카드사 공식 채널에 신고하세요.",
                        ConditionResult.TRUE, "kb-card-loss")),
                List.of(new FinancialActionPlanData.Document(
                        "incident-record", "거래 내역", "CONDITIONAL", ConditionResult.TRUE, "kb-form")),
                List.of(), List.of(), List.of(), List.of());
        return new AnalysisEvidenceSnapshotData(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 1, 1,
                plan.procedureVersionId(), plan.id(), UUID.randomUUID(), "generation",
                "model", "revision", "tokenizer", "tokenizer-revision", "{}", "{}", "hash",
                plan,
                List.of(new AnalysisEvidenceSnapshotData.SourceEvidence(
                        "source:" + chunkId, sourceDocumentId, 1, "raw", "content",
                        "publisher", "official.example", "https://official.example/source",
                        "https://official.example/source", "Document",
                        chunkId, 1, "제40조", "p.30", "제40조", "분실 신고")),
                List.of(), 1, AnalysisEvidenceSnapshotStatus.READY,
                OffsetDateTime.now(ZoneOffset.UTC));
    }

    private ConsultationReportAiResult validReport(AnalysisEvidenceSnapshotData snapshot) {
        ConsultationReportAiResult result = new ConsultationReportAiResult();
        String evidenceId = snapshot.sourceEvidence().get(0).evidenceId();
        GroundedEvidenceCitation citation = citation(evidenceId, "제40조");
        result.evidenceCitations = List.of(citation);
        result.firstAction = new ConsultationReportAiResult.FirstAction();
        result.firstAction.actionId = "report-loss";
        result.firstAction.title = "분실·도난 신고";
        result.firstAction.description = "카드사 공식 채널에 신고하세요.";
        result.actionSteps = List.of(step("report-loss", "분실·도난 신고", "카드사 공식 채널에 신고하세요."));
        result.requiredDocuments = List.of(document("incident-record", "거래 내역"));
        return result;
    }

    private ConsultationReportAiResult.ActionStep step(String id, String title, String description) {
        ConsultationReportAiResult.ActionStep step = new ConsultationReportAiResult.ActionStep();
        step.actionId = id;
        step.order = 1;
        step.title = title;
        step.description = description;
        return step;
    }

    private ConsultationReportAiResult.RequiredDocument document(String id, String name) {
        ConsultationReportAiResult.RequiredDocument document = new ConsultationReportAiResult.RequiredDocument();
        document.documentId = id;
        document.name = name;
        return document;
    }

    private GroundedEvidenceCitation citation(String id, String locator) {
        GroundedEvidenceCitation citation = new GroundedEvidenceCitation();
        citation.evidenceId = id;
        citation.locator = locator;
        return citation;
    }
}
