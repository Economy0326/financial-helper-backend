package com.financialhelper.law;

import java.time.LocalDate;

/** Structured law lookup input; raw consultation text never crosses the MCP boundary. */
public record LawEvidenceRequest(
        String lawName,
        String articleLocator,
        LocalDate incidentDate
) {

    public LawEvidenceRequest {
        if (lawName == null || lawName.isBlank()) {
            throw new IllegalArgumentException("lawName is required");
        }
        if (articleLocator != null && articleLocator.isBlank()) {
            articleLocator = null;
        }
    }
}
