package com.financialhelper.ai.analysis;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

public class AnalysisAiResult {

    @NotNull
    @JsonPropertyDescription(
            "READY_FOR_REPORT when the currently confirmed information is sufficient for an AI V1 report. NEEDS_MORE_INFO only when important missing information materially prevents useful analysis."
    )
    public Outcome outcome;

    @NotBlank
    @Size(max = 1500)
    @JsonPropertyDescription(
            "A concise Korean analysis summary based only on the confirmed consultation information."
    )
    public String analysisSummary;

    // title, explanation
    @NotNull
    @Size(min = 1, max = 6)
    @Valid
    @JsonPropertyDescription(
            "Important issues that should be considered when preparing the solution report."
    )
    public List<KeyIssue> keyIssues;

    // topic, reason
    @NotNull
    @Size(max = 5)
    @Valid
    @JsonPropertyDescription(
            "Additional information needed only when the outcome is NEEDS_MORE_INFO. Otherwise this must be empty."
    )
    public List<AdditionalInformation> additionalInformationNeeded;


    public enum Outcome {

        READY_FOR_REPORT,
        NEEDS_MORE_INFO
    }

    public static class KeyIssue {

        @NotBlank
        @Size(max = 120)
        @JsonPropertyDescription(
                "A short Korean title for the issue."
        )
        public String title;

        @NotBlank
        @Size(max = 500)
        @JsonPropertyDescription(
                "A Korean explanation of why this issue matters, without making unsupported legal conclusions."
        )
        public String explanation;
    }


    public static class AdditionalInformation {

        @NotBlank
        @Size(max = 150)
        @JsonPropertyDescription(
                "A short Korean description of additional information that is materially necessary."
        )
        public String topic;

        @NotBlank
        @Size(max = 400)
        @JsonPropertyDescription(
                "Why the information is necessary for meaningful analysis."
        )
        public String reason;
    }
}