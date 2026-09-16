package com.financialhelper.retrieval;

import java.util.List;
import java.util.UUID;

public record OfficialSearchResponse(
        UUID searchId,
        RetrievalStatus status,
        boolean degraded,
        List<String> missingFacts,
        List<String> coverageGaps,
        List<OfficialEvidenceCandidate> candidates
) {
    public OfficialSearchResponse {
        missingFacts = missingFacts == null ? List.of() : List.copyOf(missingFacts);
        coverageGaps = coverageGaps == null ? List.of() : List.copyOf(coverageGaps);
        candidates = candidates == null ? List.of() : List.copyOf(candidates);
    }
}
