package com.financialhelper.retrieval;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** retrieval 입력으로 사용하는 consultation 범위의 immutable 사용자 context다. */
public record ConfirmedCaseSnapshotData(
        UUID id,
        UUID consultationId,
        long caseInputRevision,
        long followUpAnswerRevision,
        List<Fact> facts,
        List<String> missingFacts,
        OffsetDateTime createdAt
) {
    public ConfirmedCaseSnapshotData {
        facts = facts == null ? List.of() : List.copyOf(facts);
        missingFacts = missingFacts == null ? List.of() : List.copyOf(missingFacts);
    }

    public record Fact(
            String type,
            String key,
            String value,
            String label,
            String source,
            UUID questionId
    ) {
    }
}
