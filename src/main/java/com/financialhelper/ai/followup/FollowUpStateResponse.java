package com.financialhelper.ai.followup;

import java.util.List;
import java.util.UUID;

public record FollowUpStateResponse(
        String kind,
        Question question,
        Integer currentQuestionNumber,
        Integer totalQuestions,
        String savedAnswer,
        String versionBlockReason,
        String message
) {
    public FollowUpStateResponse(String kind, Question question, Integer currentQuestionNumber,
            Integer totalQuestions, String savedAnswer) {
        this(kind, question, currentQuestionNumber, totalQuestions, savedAnswer, null, null);
    }

    public static FollowUpStateResponse versionBlocked(String reason) {
        return new FollowUpStateResponse("version_blocked", null, null, null, null,
                reason, "현재 상황에 적용할 공식 절차를 확인할 수 없습니다.");
    }

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
        return question(followUpQuestion, options, followUpQuestion.getSequenceNo(), totalQuestions);
    }

    public static FollowUpStateResponse question(
            FollowUpQuestion followUpQuestion, List<Option> options,
            int displayNumber, int totalQuestions
    ) {

        return new FollowUpStateResponse(
                "question",

                new Question(
                        followUpQuestion.getId(),
                        followUpQuestion.getInputType(),
                        followUpQuestion
                                .getQuestionText(),
                        followUpQuestion
                                .getDescription(),
                        options
                ),

                displayNumber,

                totalQuestions,

                followUpQuestion
                        .getAnswerValue()
        );
    }

    public record Question(
            UUID id,
            String inputType,
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
