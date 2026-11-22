package com.financialhelper.source;

import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class DocumentChangeServiceTest {
    private OfficialSourceVerificationService.Result result(String code, String content) {
        return new OfficialSourceVerificationService.Result(UUID.randomUUID(), 1, "raw", "url", "url", OffsetDateTime.now(),
                200, true, "raw", content, "test", code, false, true, false);
    }
    @Test void distinguishesTextChangeFromFailureAndRawOnlyChanges() {
        assertThat(DocumentChangeService.classify("same", result("RAW_BYTES_MATCH", "same"))).isEqualTo("UNCHANGED");
        assertThat(DocumentChangeService.classify("same", result("HTML_NORMALIZED_MATCH_RAW_CHANGED_REVIEW_REQUIRED", "same"))).isEqualTo("UNCHANGED");
        assertThat(DocumentChangeService.classify("same", result("CONTENT_CHANGED_REVIEW_REQUIRED", "different"))).isEqualTo("CHANGED");
        assertThat(DocumentChangeService.classify("same", result("SOURCE_ERROR_PAGE", null))).isEqualTo("FAILED");
        assertThat(DocumentChangeService.classify("same", null)).isEqualTo("FAILED");
    }
}
