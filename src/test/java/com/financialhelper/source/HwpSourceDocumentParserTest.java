package com.financialhelper.source;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class HwpSourceDocumentParserTest {

    @Test
    void extractsOfficialGuidelineTextAndPreservesOriginalBytes() throws IOException {
        byte[] original;
        try (InputStream input = getClass().getResourceAsStream(
                "/official/c2-guideline-221021.hwp")) {
            assertThat(input).isNotNull();
            original = input.readAllBytes();
        }

        SourceIngestionData.Snapshot source = new SourceIngestionData.Snapshot(
                UUID.randomUUID(),
                "crefia-card-loss-compensation-guideline-221021",
                "여신금융협회",
                "m.crefia.or.kr",
                "https://m.crefia.or.kr/common/downloadFile.do?fileType=selfRegulation",
                SourceAcquisitionType.HWP,
                null
        );
        SourceIngestionData.Fetched fetched = new SourceIngestionData.Fetched(
                source.canonicalUrl(),
                "application/x-hwp",
                null,
                null,
                original,
                OffsetDateTime.of(2026, 9, 15, 0, 0, 0, 0, ZoneOffset.UTC)
        );

        SourceIngestionData.Parsed parsed = new HwpSourceDocumentParser().parse(
                source,
                fetched
        );

        assertThat(parsed.originalContent()).isEqualTo(original);
        assertThat(parsed.rawSha256()).isEqualTo(
                "bfffe9832f9d3ba7db80e613a120c332ad4e909d2eb8629917dc0cb695011dd6"
        );
        assertThat(parsed.normalizedContent())
                .contains("제4조")
                .contains("제12조")
                .contains("분실")
                .contains("도난");
    }
}
