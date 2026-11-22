package com.financialhelper.source;

import org.junit.jupiter.api.Test;
import java.time.OffsetDateTime;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;

class UrlVerificationApprovalEvidenceTest {
    @Test void evidenceFailsClosedForExpiryDifferentVersionOrUnverifiedLaw() {
        UUID document = UUID.randomUUID(), registry = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now();
        var ok = new UrlVerificationApprovalEvidence.Attempt(document, registry, 1, "raw", "content", "url",
                now.minusMinutes(1), now.plusMinutes(1), "RAW_BYTES_MATCH", true, false);
        assertThat(UrlVerificationApprovalEvidence.eligible(ok, document, registry, 1, "raw", "content", "url", now)).isTrue();
        assertThat(UrlVerificationApprovalEvidence.eligible(ok, document, registry, 2, "raw", "content", "url", now)).isFalse();
        assertThat(UrlVerificationApprovalEvidence.eligible(ok, document, registry, 1, "raw", "content", "url", now.plusMinutes(1))).isFalse();
        assertThat(UrlVerificationApprovalEvidence.eligible(ok, UUID.randomUUID(), registry, 1, "raw", "content", "url", now)).isFalse();
        var law = new UrlVerificationApprovalEvidence.Attempt(document, registry, 1, "raw", "content", "url",
                now.minusMinutes(1), now.plusMinutes(1), "RAW_BYTES_MATCH", true, true);
        assertThat(UrlVerificationApprovalEvidence.eligible(law, document, registry, 1, "raw", "content", "url", now)).isFalse();
        var changedHtml = new UrlVerificationApprovalEvidence.Attempt(document, registry, 1, "raw", "content", "url",
                now.minusMinutes(1), now.plusMinutes(1), "HTML_NORMALIZED_MATCH_RAW_CHANGED_REVIEW_REQUIRED", true, false);
        assertThat(UrlVerificationApprovalEvidence.eligible(changedHtml, document, registry, 1, "raw", "content", "url", now)).isFalse();
    }
}
