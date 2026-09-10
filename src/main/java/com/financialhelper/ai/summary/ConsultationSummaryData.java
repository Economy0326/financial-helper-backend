package com.financialhelper.ai.summary;

import com.financialhelper.consultation.ConsultationCategory;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public final class ConsultationSummaryData {

    private ConsultationSummaryData() {
    }

    public record FollowUpAnswer(
            int sequenceNo,
            String question,
            String answerValue,
            String answerLabel
    ) {
    }

    // Summary 생성에 사용할 전체 입력 상태
    public record Snapshot(
            UUID consultationId,
            UUID guestSessionId,
            ConsultationCategory category,
            String situationText,
            long caseInputRevision,
            long followUpAnswerRevision,
            List<FollowUpAnswer> followUpAnswers
    ) {
    }

    // 생성된 summary 결과와 메타데이터
    public record Document(
            UUID consultationId,
            long caseInputRevision,
            long followUpAnswerRevision,
            String model,
            ConsultationSummaryAiResult result,
            OffsetDateTime generatedAt,
            OffsetDateTime confirmedAt
    ) {
    }
}