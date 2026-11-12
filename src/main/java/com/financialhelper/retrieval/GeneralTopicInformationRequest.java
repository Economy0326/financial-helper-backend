package com.financialhelper.retrieval;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;

/** An as-of date is a question scope, never an incident-date substitute. */
public record GeneralTopicInformationRequest(
        @NotNull @Min(0) Long expectedCaseInputRevision,
        @NotNull TimeScope timeScope,
        LocalDate asOfDate
) {
    public enum TimeScope { CURRENT, HISTORICAL }
}
