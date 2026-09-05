package com.financialhelper.ai.followup;

import java.util.List;

public record FollowUpOptionsPayload(
        List<Option> options
) {

    public record Option(
            String value,
            String label,
            String description
    ) {
    }

    public static FollowUpOptionsPayload from(
            List<FollowUpQuestionAiResult.Option> aiOptions
    ) {

        List<Option> options =
                aiOptions.stream()
                        .map(
                                option ->
                                        new Option(
                                                option.value,
                                                option.label,
                                                option.description
                                        )
                        )
                        .toList();

        return new FollowUpOptionsPayload(
                options
        );
    }
}