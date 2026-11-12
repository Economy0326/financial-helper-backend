package com.financialhelper.source;

import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class VerifiedPdfMetadataTest {
    private final SourceIngestionData.Snapshot source = new SourceIngestionData.Snapshot(
            UUID.randomUUID(), "test-only", "금융위원회", "fsc.go.kr",
            VerifiedPdfMetadata.DEPOSIT_PDF_URL, SourceAcquisitionType.PDF, null);
    private final SourceIngestionData.Fetched fetched = new SourceIngestionData.Fetched(
            VerifiedPdfMetadata.DEPOSIT_PDF_URL, "application/pdf", null, null,
            new byte[0], OffsetDateTime.now());

    @Test
    void metadataIsAttributedToTheVerifiedPostOnlyForTheExactPdfAndRawHash() {
        var verified = VerifiedPdfMetadata.forFetched(source, fetched,
                VerifiedPdfMetadata.DEPOSIT_RAW_SHA256).orElseThrow();
        assertThat(verified.title()).isEqualTo("예금보호한도 상향 관련 주요 QA");
        assertThat(verified.publishedAt()).hasYear(2025).hasMonthValue(7).hasDayOfMonth(22);
        assertThat(verified.publisher()).isEqualTo("금융위원회");
        assertThat(verified.metadataSourceUrl()).isEqualTo(VerifiedPdfMetadata.DEPOSIT_POST_URL);
        assertThat(verified.pdfUrl()).isEqualTo(VerifiedPdfMetadata.DEPOSIT_PDF_URL);

        assertThat(VerifiedPdfMetadata.forFetched(source, fetched, "0".repeat(64))).isEmpty();
        assertThat(VerifiedPdfMetadata.forFetched(new SourceIngestionData.Snapshot(
                source.sourceRegistryId(), source.sourceKey(), source.organizationName(),
                source.officialDomain(), "https://www.fsc.go.kr/other.pdf",
                SourceAcquisitionType.PDF, null), fetched,
                VerifiedPdfMetadata.DEPOSIT_RAW_SHA256)).isEmpty();
        assertThat(VerifiedPdfMetadata.forFetched(source, new SourceIngestionData.Fetched(
                "https://www.fsc.go.kr/other.pdf", "application/pdf", null, null,
                new byte[0], OffsetDateTime.now()), VerifiedPdfMetadata.DEPOSIT_RAW_SHA256)).isEmpty();
    }
}
