package com.financialhelper.source;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Opt-in read-only fetch of the one hard-coded official attachment. No database or approval. */
class OfficialDepositSourceNetworkPreflightTest {
    @Test
    @EnabledIfEnvironmentVariable(named = "FH_OFFICIAL_DEPOSIT_NETWORK_PREFLIGHT", matches = "true")
    void currentOfficialPdfMatchesTheHumanReviewedVersionAndMetadata() {
        var source = new SourceIngestionData.Snapshot(UUID.randomUUID(), "fsc-deposit-protection-qa-pdf",
                "금융위원회", "fsc.go.kr", VerifiedPdfMetadata.DEPOSIT_PDF_URL,
                SourceAcquisitionType.PDF, null);
        var fetcher = new HttpSourceContentFetcher(new SourceIngestionProperties(false,
                Duration.ofSeconds(5), Duration.ofSeconds(30), 2_000_000, 2,
                "financial-helper-official-source-preflight"), new SourceUrlPolicy());
        var fetched = fetcher.fetch(source);
        assertThat(fetched.resolvedUrl()).isEqualTo(VerifiedPdfMetadata.DEPOSIT_PDF_URL);
        assertThat(fetched.originalContent()).hasSize(170319);
        assertThat(SourceHashing.sha256(fetched.originalContent()))
                .isEqualTo(VerifiedPdfMetadata.DEPOSIT_RAW_SHA256);
        var parsed = new PdfSourceDocumentParser().parse(source, fetched);
        assertThat(parsed.title()).isEqualTo("예금보호한도 상향 관련 주요 QA");
        assertThat(parsed.publishedAt()).isEqualTo(java.time.LocalDate.of(2025, 7, 22));
        assertThat(parsed.normalizedContent()).contains("10.", "사고보험금", "※ (예)");
    }
}
