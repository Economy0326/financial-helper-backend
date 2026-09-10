package com.financialhelper.ai.followup;

import java.util.List;
import java.util.UUID;

public record FollowUpStateResponse(
        String kind,
        Question question,
        Integer currentQuestionNumber,
        Integer totalQuestions,
        String savedAnswer
) {

    public static FollowUpStateResponse notPrepared() {
        return new FollowUpStateResponse(
                // 직접 url 접근이나 아직 follow-up 질문이 준비되지 않은 상태에서 follow-up api 호출 시
                // 생성 작업 성공 후, 추가 질문이 필요 없는 상황과 다름
                "not-prepared",
                null,
                null,
                null,
                null
        );
    }

    public static FollowUpStateResponse complete() {
        return new FollowUpStateResponse(
                "complete",
                null,
                null,
                null,
                null
        );
    }

    public static FollowUpStateResponse question(
            FollowUpQuestion followUpQuestion,
            List<Option> options,
            int totalQuestions
    ) {

        return new FollowUpStateResponse(
                "question",

                new Question(
                        followUpQuestion.getId(),
                        followUpQuestion
                                .getQuestionText(),
                        followUpQuestion
                                .getDescription(),
                        options
                ),

                followUpQuestion
                        .getSequenceNo(),

                totalQuestions,

                followUpQuestion
                        .getAnswerValue()
        );
    }

    public record Question(
            UUID id,
            String question,
            String description,
            List<Option> options
    ) {
    }

    public record Option(
            String value,
            String label,
            String description
    ) {
    }
}