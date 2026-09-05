package com.financialhelper.ai.summary;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

public class ConsultationSummaryAiResult {

    @NotBlank
    @Size(max = 100)
    @JsonPropertyDescription(
            "A short Korean headline describing the user's financial issue."
    )
    public String headline;

    @NotBlank
    @Size(max = 1200)
    @JsonPropertyDescription(
            "A concise Korean summary of the user's case based only on the provided information."
    )
    public String summaryText;

    @NotNull
    @Size(min = 2, max = 6)
    @Valid
    @JsonPropertyDescription(
            "Two to six short Korean key points summarizing the important case details."
    )
    public List<KeyPoint> keyPoints;


    public static class KeyPoint {

        @NotBlank
        @Size(max = 160)
        @JsonPropertyDescription(
                "A short Korean key point."
        )
        public String text;
    }
}