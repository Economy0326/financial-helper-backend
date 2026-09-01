package com.financialhelper.ai.understanding;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

public class CaseUnderstandingAiResult {

    // 이미 알고있는 사실의 종류
    @NotNull
    @Size(max = 20)
    @Valid
    @JsonPropertyDescription(
            "Facts explicitly stated or directly supported by the user's situation."
    )
    public List<ExtractedFact> facts;

    // 앞으로 더 확인해야 할 정보의 종류
    @NotNull
    @Size(max = 10)
    @Valid
    @JsonPropertyDescription(
            "Information that is still missing and would materially help understand the case."
    )
    public List<MissingInformation> missingInformation;


    public static class ExtractedFact {

        @NotNull
        @JsonPropertyDescription(
                "The semantic type of this fact."
        )
        public FactType type;

        @NotBlank
        @Size(max = 80)
        @JsonPropertyDescription(
                "A short Korean label describing the fact."
        )
        public String label;

        @NotBlank
        @Size(max = 500)
        @JsonPropertyDescription(
                "The fact value based only on information explicitly provided by the user."
        )
        public String value;
    }


    public enum FactType {

        PRODUCT,
        FINANCIAL_INSTITUTION,
        EVENT,
        DATE,
        AMOUNT,
        COMMUNICATION,
        ACTION_TAKEN,
        DOCUMENT,
        DESIRED_OUTCOME,
        OTHER
    }


    public static class MissingInformation {

        @NotNull
        @JsonPropertyDescription(
                "The semantic type of the missing information."
        )
        public MissingInformationType type;

        @NotBlank
        @Size(max = 120)
        @JsonPropertyDescription(
                "A short Korean description of what information is missing."
        )
        public String topic;

        @NotBlank
        @Size(max = 300)
        @JsonPropertyDescription(
                "Why this information would help understand the user's case."
        )
        public String reason;

        @NotNull
        @JsonPropertyDescription(
                "IMPORTANT when useful for understanding the core case, otherwise OPTIONAL."
        )
        public MissingInformationPriority priority;
    }


    public enum MissingInformationType {

        PRODUCT_DETAILS,
        TIMELINE,
        AMOUNT,
        FINANCIAL_INSTITUTION,
        COMMUNICATION,
        ACTION_TAKEN,
        DOCUMENT,
        DESIRED_OUTCOME,
        OTHER
    }


    public enum MissingInformationPriority {

        IMPORTANT,
        OPTIONAL
    }
}