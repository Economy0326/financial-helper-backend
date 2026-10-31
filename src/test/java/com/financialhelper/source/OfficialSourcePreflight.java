package com.financialhelper.source;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Test-only, read-only inspection of five URLs already declared by V10/V17. */
final class OfficialSourcePreflight {
    private static final Map<String, SourceIngestionData.Snapshot> SOURCES = Map.of(
            "police-reporting", source("police-reporting", "경찰청", "counterscam112.go.kr",
                    "https://www.counterscam112.go.kr/board/CONTENT_000000000010.do"),
            "kb-card-compensation-process", source("kb-card-compensation-process", "KB국민카드",
                    "mapps.kbcard.com", "https://mapps.kbcard.com/SVC/DVIEW/HSGMCXCRSCSC0004"),
            "fsc-2026-alert", source("fsc-2026-alert", "금융위원회", "fsc.go.kr",
                    "https://www.fsc.go.kr/no010101/86271"),
            "fsc-personal-info", source("fsc-personal-info", "금융위원회", "fsc.go.kr",
                    "https://www.fsc.go.kr/edu/cardnews?cnId=1527"),
            "kisa-118", source("kisa-118", "한국인터넷진흥원", "kisa.kr",
                    "https://www.kisa.kr/118"));

    private OfficialSourcePreflight() {}

    static List<String> sourceKeys() { return SOURCES.keySet().stream().sorted().toList(); }

    static SourceIngestionData.Snapshot source(String key) {
        var source = SOURCES.get(key);
        if (source == null) throw new IllegalArgumentException("Source key is not in the preflight allowlist");
        return source;
    }

    static SourceContentFetcher officialFetcher() {
        var properties = new SourceIngestionProperties(false, Duration.ofSeconds(5),
                Duration.ofSeconds(15), 2_000_000, 2, "financial-helper-source-preflight/1");
        return new HttpSourceContentFetcher(properties, new SourceUrlPolicy());
    }

    static Result inspect(String key, SourceContentFetcher fetcher) {
        var source = source(key); // Reject unknown keys before any fetch.
        SourceIngestionData.Fetched fetched;
        try {
            fetched = fetcher.fetch(source);
        } catch (SourceIngestionException failure) {
            return failure(source, "FETCH_FAILED", failure.getCode());
        } catch (RuntimeException failure) {
            return failure(source, "FETCH_FAILED", "FETCH_UNEXPECTED_FAILURE");
        }
        SourceIngestionData.Parsed parsed;
        try {
            parsed = new HtmlSourceDocumentParser().parse(source, fetched);
        } catch (SourceIngestionException failure) {
            return failure(source, "PARSE_FAILED", failure.getCode());
        } catch (RuntimeException failure) {
            return failure(source, "PARSE_FAILED", "PARSE_UNEXPECTED_FAILURE");
        }
        try {
            // A local proxy tokenizer checks offsets only; it is not READY generation indexing.
            var chunks = ExtractionGoldenSupport.chunks(parsed.normalizedContent(), 160, 240);
            boolean offsetsValid = chunks.stream().allMatch(chunk ->
                    chunk.body().equals(parsed.normalizedContent().substring(
                            chunk.sourceStartOffset(), chunk.sourceEndOffset())));
            return new Result(key, source.canonicalUrl(), fetched.resolvedUrl(), fetched.retrievedAt(),
                    "ACCESSIBLE", "PASS", offsetsValid ? "PASS" : "FAIL",
                    parsed.rawSha256(), parsed.contentSha256(), fetched.contentType(),
                    parsed.title(), parsed.publishedAt(), parsed.normalizedContent().length(),
                    chunks.size(), chunks.stream().map(c -> new Locator(c.articleReference(),
                            c.pageReference(), c.locator())).toList(),
                    "NOT_REVIEWED", "UNVERIFIED", "ADDITIONAL_REVIEW_REQUIRED", null);
        } catch (RuntimeException failure) {
            return new Result(key, source.canonicalUrl(), fetched.resolvedUrl(), fetched.retrievedAt(),
                    "ACCESSIBLE", "PASS", "FAIL", parsed.rawSha256(), parsed.contentSha256(),
                    fetched.contentType(), parsed.title(), parsed.publishedAt(),
                    parsed.normalizedContent().length(), null, List.of(), "NOT_REVIEWED",
                    "UNVERIFIED", "ADDITIONAL_REVIEW_REQUIRED", "CHUNK_VALIDATION_FAILED");
        }
    }

    private static Result failure(SourceIngestionData.Snapshot source, String status, String code) {
        return new Result(source.sourceKey(), source.canonicalUrl(), null, null,
                status.equals("FETCH_FAILED") ? "UNAVAILABLE" : "ACCESSIBLE",
                status, "NOT_VERIFIED", null, null, null, null, null, null, null,
                List.of(), "NOT_REVIEWED", "UNVERIFIED", "ADDITIONAL_REVIEW_REQUIRED", code);
    }

    private static SourceIngestionData.Snapshot source(String key, String organization,
            String domain, String url) {
        return new SourceIngestionData.Snapshot(UUID.nameUUIDFromBytes(key.getBytes(
                java.nio.charset.StandardCharsets.UTF_8)), key, organization, domain, url,
                SourceAcquisitionType.HTML, null);
    }

    record Locator(String article, String page, String offset) {}
    record Result(String sourceKey, String declaredUrl, String resolvedUrl, OffsetDateTime fetchedAt,
            String access, String parser, String locator, String rawSha256, String contentSha256,
            String contentType, String title, java.time.LocalDate publishedAt,
            Integer extractedChars, Integer chunkCount, List<Locator> locators,
            String conditionExceptionReview, String versionAndApplicability,
            String v38Readiness, String failureCode) {}
}
