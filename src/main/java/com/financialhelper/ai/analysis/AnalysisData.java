package com.financialhelper.ai.analysis;

import com.financialhelper.ai.summary
        .ConsultationSummaryAiResult;

import com.financialhelper.consultation
        .ConsultationCategory;
import com.financialhelper.consultation.ConsultationScenario;

import java.util.UUID;

public final class AnalysisData {

    private AnalysisData() {
    }

    // AI 입력 고정
    public record Snapshot(
            UUID jobId,
            UUID consultationId,
            ConsultationCategory category,
            ConsultationScenario scenario,
            long caseInputRevision,
            long followUpAnswerRevision,
            ConsultationSummaryAiResult confirmedSummary
    ) {
        public Snapshot(UUID jobId, UUID consultationId, ConsultationCategory category,
                        long caseInputRevision, long followUpAnswerRevision,
                        ConsultationSummaryAiResult confirmedSummary) {
            this(jobId, consultationId, category,
                    category == ConsultationCategory.CARD
                            ? ConsultationScenario.CARD_LOSS_UNAUTHORIZED_USE
                            : ConsultationScenario.UNKNOWN,
                    caseInputRevision, followUpAnswerRevision, confirmedSummary);
        }
    }

    // 실행 여부 결정
    public record Reservation(
            UUID jobId,
            // shouldDispatch: true => 새 분석 작업 실행 가능 (중복 방지용)
            boolean shouldDispatch,
            AnalysisStateResponse state
    ) {
    }
}
