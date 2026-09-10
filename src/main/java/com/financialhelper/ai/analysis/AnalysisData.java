package com.financialhelper.ai.analysis;

import com.financialhelper.ai.summary
        .ConsultationSummaryAiResult;

import com.financialhelper.consultation
        .ConsultationCategory;

import java.util.UUID;

public final class AnalysisData {

    private AnalysisData() {
    }

    // AI 입력 고정
    public record Snapshot(
            UUID jobId,
            UUID consultationId,
            ConsultationCategory category,
            long caseInputRevision,
            long followUpAnswerRevision,
            ConsultationSummaryAiResult confirmedSummary
    ) {
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