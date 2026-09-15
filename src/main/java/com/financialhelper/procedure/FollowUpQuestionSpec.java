package com.financialhelper.procedure;

import java.util.List;

public record FollowUpQuestionSpec(
        String factKey,
        FollowUpInputType inputType,
        List<Option> options,
        String question,
        String description,
        boolean requiredForDecision,
        String questionIntent,
        boolean allowShortFreeText
) {
    public FollowUpQuestionSpec {
        options = options == null ? List.of() : List.copyOf(options);
        if (factKey == null || factKey.isBlank()) {
            throw new IllegalArgumentException("factKey must not be blank");
        }
        if (inputType == null) {
            throw new IllegalArgumentException("inputType must not be null");
        }
    }

    public record Option(String value, String label, String description) {
    }
}
