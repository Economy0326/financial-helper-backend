package com.financialhelper.source;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OfficialSourcePreflightTest {
    @Test
    void declaredKeysMatchRepositoryMigrations() throws Exception {
        String v10 = resource("/db/migration/V10__create_source_ingestion_foundation.sql");
        String v17 = resource("/db/migration/V17__activate_card_official_sources.sql");
        assertThat(OfficialSourcePreflight.sourceKeys()).hasSize(5);
        for (String key : OfficialSourcePreflight.sourceKeys()) {
            var source = OfficialSourcePreflight.source(key);
            String declaration = key.equals("kb-card-compensation-process") ? v17 : v10;
            assertThat(declaration).contains("'" + key + "'").contains("'" + source.canonicalUrl() + "'");
            new SourceUrlPolicy().validate(source.canonicalUrl(), source.officialDomain());
        }
    }

    @Test
    void unknownKeyCannotTriggerFetch() {
        assertThatThrownBy(() -> OfficialSourcePreflight.inspect("arbitrary-url", source -> {
            throw new AssertionError("fetch must not run");
        })).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void failedFetchIsRecordedWithoutInventedHashOrApproval() {
        var result = OfficialSourcePreflight.inspect("police-reporting", source -> {
            throw new SourceIngestionException("SOURCE_REQUEST_FAILED", "offline");
        });
        assertThat(result.access()).isEqualTo("UNAVAILABLE");
        assertThat(result.parser()).isEqualTo("FETCH_FAILED");
        assertThat(result.failureCode()).isEqualTo("SOURCE_REQUEST_FAILED");
        assertThat(result.rawSha256()).isNull();
        assertThat(result.v38Readiness()).isEqualTo("ADDITIONAL_REVIEW_REQUIRED");
        assertThat(result.reviewMarkers()).isEmpty();
    }

    @Test
    void actualFetchedBytesAreHashedAndOffsetsCheckedWithoutApproval() throws Exception {
        var result = OfficialSourcePreflight.inspect("police-reporting", source -> {
            byte[] bytes = ("<html><body><main><h1>합성 제보 안내</h1><p>"
                    + "이 문장은 공식 원문이 아닌 테스트 입력입니다. ".repeat(12)
                    + "</p></main></body></html>").getBytes(StandardCharsets.UTF_8);
            return new SourceIngestionData.Fetched(source.canonicalUrl(), "text/html", null,
                    null, bytes, OffsetDateTime.parse("2026-10-31T00:00:00Z"));
        });
        assertThat(result.access()).isEqualTo("ACCESSIBLE");
        assertThat(result.parser()).isEqualTo("PASS");
        assertThat(result.locator()).isEqualTo("PASS");
        assertThat(result.rawSha256()).hasSize(64);
        assertThat(result.chunkCount()).isPositive();
        assertThat(result.bodyHeading()).isEqualTo("합성 제보 안내");
        assertThat(result.mainImageCount()).isZero();
        assertThat(result.conditionExceptionReview()).isEqualTo("NOT_REVIEWED");
        assertThat(result.versionAndApplicability()).isEqualTo("UNVERIFIED");
        assertThat(result.reviewMarkers()).containsEntry("제보방법 안내", false);
        assertThat(JsonMapper.builder().build().writeValueAsString(result)).contains("rawSha256");
    }

    @Test
    void malformedHtmlDoesNotBecomeVerifiedEvidence() {
        var result = OfficialSourcePreflight.inspect("kisa-118", source ->
                new SourceIngestionData.Fetched(source.canonicalUrl(), "text/html", null,
                        null, "<html><body>short</body></html>".getBytes(StandardCharsets.UTF_8),
                        OffsetDateTime.parse("2026-10-31T00:00:00Z")));
        assertThat(result.parser()).isEqualTo("PARSE_FAILED");
        assertThat(result.locator()).isEqualTo("NOT_VERIFIED");
        assertThat(result.rawSha256()).isNull();
    }

    @Test
    void rawOnlyChangeIsFlaggedWithoutTreatingItAsApprovedSameVersion() {
        var first = OfficialSourcePreflight.inspect("police-reporting", source -> synthetic(source,
                "<nav>조회 1</nav>"));
        var second = OfficialSourcePreflight.inspect("police-reporting", source -> synthetic(source,
                "<nav>조회 2</nav>"));

        assertThat(first.rawSha256()).isNotEqualTo(second.rawSha256());
        assertThat(first.contentSha256()).isEqualTo(second.contentSha256());
        assertThat(OfficialSourcePreflight.compare(first, second))
                .isEqualTo("RAW_CHANGED_CONTENT_SAME_REVIEW_REQUIRED");
    }

    @Test
    void changedConditionCannotBeClassifiedAsDynamicOnly() {
        var first = OfficialSourcePreflight.inspect("police-reporting", source -> synthetic(source,
                "<p>조건 A</p>"));
        var second = OfficialSourcePreflight.inspect("police-reporting", source -> synthetic(source,
                "<p>조건 B</p>"));

        assertThat(OfficialSourcePreflight.compare(first, second))
                .isEqualTo("CONTENT_CHANGED_REVIEW_REQUIRED");
    }

    @Test
    void imageChangeWithSameExtractedTextStillNeedsReview() {
        var first = OfficialSourcePreflight.inspect("fsc-personal-info", source -> synthetic(source,
                "<img src='card-1.png' alt='합성 카드뉴스'>"));
        var second = OfficialSourcePreflight.inspect("fsc-personal-info", source -> synthetic(source,
                "<img src='card-2.png' alt='합성 카드뉴스'>"));

        assertThat(first.contentSha256()).isEqualTo(second.contentSha256());
        assertThat(OfficialSourcePreflight.compare(first, second))
                .isEqualTo("RAW_CHANGED_CONTENT_SAME_REVIEW_REQUIRED");
    }

    @Test
    void failedFetchCannotBeComparedAsStableContent() {
        var good = OfficialSourcePreflight.inspect("police-reporting", source -> synthetic(source, ""));
        var failed = OfficialSourcePreflight.inspect("police-reporting", source -> {
            throw new SourceIngestionException("SOURCE_REQUEST_FAILED", "offline");
        });

        assertThat(OfficialSourcePreflight.compare(good, failed))
                .isEqualTo("UNVERIFIED_FETCH_PARSE_OR_LOCATOR");
    }

    private SourceIngestionData.Fetched synthetic(SourceIngestionData.Snapshot source, String variation) {
        byte[] bytes = ("<html><body><main><h1>합성 제보 안내</h1>"
                + "<p>제보를 위한 합성 본문입니다. ".repeat(12)
                + variation + "</main></body></html>").getBytes(StandardCharsets.UTF_8);
        return new SourceIngestionData.Fetched(source.canonicalUrl(), "text/html", null,
                null, bytes, OffsetDateTime.parse("2026-10-31T00:00:00Z"));
    }

    /** Explicit opt-in. Execute this one test with OFFICIAL_PREFLIGHT_LIVE=true; it never writes a fixture. */
    @Test
    @EnabledIfEnvironmentVariable(named = "OFFICIAL_PREFLIGHT_LIVE", matches = "true")
    void liveOfficialUrlsProduceReviewJsonOnlyWhenExplicitlyRequested() throws Exception {
        var mapper = JsonMapper.builder().build();
        for (String key : OfficialSourcePreflight.sourceKeys()) {
            var first = OfficialSourcePreflight.inspect(key, OfficialSourcePreflight.officialFetcher());
            var second = OfficialSourcePreflight.inspect(key, OfficialSourcePreflight.officialFetcher());
            System.out.println(mapper.writeValueAsString(first));
            System.out.println(mapper.writeValueAsString(second));
            System.out.println("PREFLIGHT_COMPARISON sourceKey=" + key + " result="
                    + OfficialSourcePreflight.compare(first, second));
        }
    }

    private String resource(String path) throws Exception {
        try (var input = getClass().getResourceAsStream(path)) {
            assertThat(input).isNotNull();
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
