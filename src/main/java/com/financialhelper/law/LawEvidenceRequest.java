package com.financialhelper.law;

import java.time.LocalDate;

/** 구조화된 법령 조회 입력이며 상담 원문은 law API 경계를 넘지 않는다. */
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
