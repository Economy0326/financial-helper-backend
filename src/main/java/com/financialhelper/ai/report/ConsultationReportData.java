package com.financialhelper.ai.report;

import com.financialhelper.ai.analysis.AnalysisAiResult;
import com.financialhelper.ai.grounded.AnalysisEvidenceSnapshotData;
import com.financialhelper.ai.summary.ConsultationSummaryAiResult;
import com.financialhelper.consultation.ConsultationCategory;
import com.financialhelper.consultation.ConsultationScenario;

import java.time.OffsetDateTime;
import java.util.UUID;

public final class ConsultationReportData {

    private ConsultationReportData() {
    }

    public record Snapshot(
            UUID consultationId,
            UUID guestSessionId,
            UUID analysisJobId,
            ConsultationCategory category,
            long caseInputRevision,
            long followUpAnswerRevision,
            ConsultationSummaryAiResult summary,
            AnalysisAiResult analysis,
            AnalysisEvidenceSnapshotData groundedEvidence
    ) {
    }

    public record Document(
            UUID consultationId,
            long caseInputRevision,
            long followUpAnswerRevision,
            String model,
            ConsultationReportAiResult result,
            OffsetDateTime generatedAt,
            UUID evidenceSnapshotId,
            ConsultationScenario scenario
    ) {
        public Document(
                UUID consultationId,
                long caseInputRevision,
                long followUpAnswerRevision,
                String model,
                ConsultationReportAiResult result,
                OffsetDateTime generatedAt,
                UUID evidenceSnapshotId
        ) {
            this(consultationId, caseInputRevision, followUpAnswerRevision, model,
                    result, generatedAt, evidenceSnapshotId, null);
        }
    }
}
