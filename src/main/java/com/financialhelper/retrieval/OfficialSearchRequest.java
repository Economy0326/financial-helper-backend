package com.financialhelper.retrieval;

import java.time.LocalDate;
import java.util.List;

public record OfficialSearchRequest(
        String query,
        String category,
        String institution,
        String productType,
        LocalDate incidentDate,
        List<ConfirmedCaseSnapshotData.Fact> confirmedFacts,
        int limit,
        String scenario
) {
    public OfficialSearchRequest(String query, String category, String institution,
                                 String productType, LocalDate incidentDate,
                                 List<ConfirmedCaseSnapshotData.Fact> confirmedFacts,
                                 int limit) {
        this(query, category, institution, productType, incidentDate, confirmedFacts, limit, null);
    }

    public OfficialSearchRequest {
        query = query == null ? "" : query.trim();
        category = normalize(category);
        institution = normalize(institution);
        productType = normalize(productType);
        scenario = normalize(scenario);
        confirmedFacts = confirmedFacts == null ? List.of() : List.copyOf(confirmedFacts);
        if (limit == 0) {
            limit = 10;
        }
        if (limit < 1 || limit > 100) {
            throw new IllegalArgumentException("limit must be between 1 and 100");
        }
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
