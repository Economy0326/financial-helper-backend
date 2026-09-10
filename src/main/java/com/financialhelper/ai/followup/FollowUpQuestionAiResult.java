package com.financialhelper.ai.followup;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

public class FollowUpQuestionAiResult {

    @NotNull
    // 최대 질문 5개까지 허용
    @Size(max = 5)
    @Valid
    @JsonPropertyDescription(
            "Follow-up questions that are materially useful for understanding the case. Return an empty list when no follow-up is necessary."
    )
    public List<Question> questions;


    public static class Question {

        @NotBlank
        @Size(max = 300)
        @JsonPropertyDescription(
                "A simple Korean question asking only one thing."
        )
        public String question;

        @NotBlank
        @Size(max = 500)
        @JsonPropertyDescription(
                "A short Korean explanation that helps the user understand what to choose."
        )
        public String description;

        @NotNull
        // 질문마다 선택지 2~4개 허용
        @Size(
                min = 2,
                max = 4
        )
        @Valid
        @JsonPropertyDescription(
                "Two to four selectable options. Exactly one option must use value UNKNOWN."
        )
        public List<Option> options;
    }


    public static class Option {

        @NotBlank
        @Size(max = 40)
        @Pattern(
                regexp = "^[A-Z0-9_]+$"
        )
        @JsonPropertyDescription(
                "Stable machine-readable option value using uppercase letters, numbers, and underscores."
        )
        public String value;

        @NotBlank
        @Size(max = 120)
        @JsonPropertyDescription(
                "Short Korean option label."
        )
        public String label;

        @NotBlank
        @Size(max = 220)
        @JsonPropertyDescription(
                "Short Korean explanation of the option."
        )
        public String description;
    }
}