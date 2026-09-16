package com.financialhelper.ai.followup;

import com.financialhelper.procedure.FollowUpInputType;
import com.financialhelper.procedure.FollowUpQuestionSpec;

import java.util.List;
import java.util.UUID;

public record StructuredFollowUpStateResponse(
        String kind,
        long caseInputRevision,
        List<String> missingFacts,
        Question question,
        Integer currentQuestionNumber,
        Integer totalQuestions,
        String savedAnswer
) {
    public StructuredFollowUpStateResponse {
        missingFacts = missingFacts == null ? List.of() : List.copyOf(missingFacts);
    }

    public static StructuredFollowUpStateResponse complete(long revision) {
        return new StructuredFollowUpStateResponse("complete", revision, List.of(), null, null, null, null);
    }

    public static StructuredFollowUpStateResponse question(
            FollowUpQuestion entity,
            List<FollowUpQuestionSpec.Option> options,
            int total
    ) {
        return question(entity, options, total, List.of(entity.getFactKey()));
    }

    public static StructuredFollowUpStateResponse question(
            FollowUpQuestion entity,
            List<FollowUpQuestionSpec.Option> options,
            int total,
            List<String> missingFacts
    ) {
        return new StructuredFollowUpStateResponse(
                "question",
                entity.getCaseInputRevision(),
                missingFacts,
                new Question(entity.getId(), entity.getFactKey(),
                        parseInputType(entity.getInputType()), options,
                        entity.getQuestionText(), entity.getDescription(),
                        entity.isRequiredForDecision(), entity.getQuestionIntent()),
                entity.getSequenceNo(), total, entity.getAnswerValue());
    }

    public record Question(
            UUID id,
            String factKey,
            FollowUpInputType inputType,
            List<FollowUpQuestionSpec.Option> options,
            String question,
            String description,
            boolean requiredForDecision,
            String questionIntent
    ) {
        public Question {
            options = options == null ? List.of() : List.copyOf(options);
        }
    }

    private static FollowUpInputType parseInputType(String value) {
        if (value == null) {
            return FollowUpInputType.SHORT_TEXT;
        }
        try {
            return FollowUpInputType.valueOf(value);
        } catch (IllegalArgumentException ignored) {
            return FollowUpInputType.SHORT_TEXT;
        }
    }
}
