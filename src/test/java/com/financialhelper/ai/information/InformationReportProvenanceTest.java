package com.financialhelper.ai.information;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;

class InformationReportProvenanceTest {
    @Test void urlAndReviewedScopeNeverAssertOfficialOrSyntheticOrigin() {
        var first = evidence("https://synthetic.invalid/source");
        var second = evidence("https://official.example.org/source");
        var response = new InformationReportResponse(UUID.randomUUID(), "READY_CANDIDATES", null,
                null, null, List.of(first, second), List.of());
        assertThat(response.evidenceProvenance()).containsEntry(first.supportChunkId(), "UNKNOWN")
                .containsEntry(second.supportChunkId(), "UNKNOWN");
    }

    private InformationEvidenceSnapshot.Evidence evidence(String url) {
        return new InformationEvidenceSnapshot.Evidence(UUID.randomUUID(), 1, "a".repeat(64),
                UUID.randomUUID(), UUID.randomUUID(), "COMMON", "합성 기관", "합성 문서", url,
                null, null, "p1", "합성 본문", null, null, null, null, List.of(), List.of());
    }
}
