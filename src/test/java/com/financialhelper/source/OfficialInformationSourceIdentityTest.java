package com.financialhelper.source;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OfficialInformationSourceIdentityTest {
    private static final String KEY = "law-financial-consumer-protection-19";
    private static final String URL = OfficialInformationClaimSelector.officialUrl(KEY);
    private static final SourceIngestionData.Snapshot SOURCE = new SourceIngestionData.Snapshot(
            UUID.randomUUID(), KEY, "국가법령정보센터", "law.go.kr", URL, SourceAcquisitionType.HTML, null);

    @Test
    void syntheticFixtureRetainsArticleVersionAndUtf16Range() throws Exception {
        byte[] bytes;
        try (var input = getClass().getResourceAsStream(
                "/official/extraction/synthetic-law-article-19.html")) {
            if (input == null) throw new IllegalStateException("Synthetic law fixture missing");
            bytes = input.readAllBytes();
        }
        org.assertj.core.api.Assertions.assertThat(SourceHashing.sha256(bytes))
                .isEqualTo("f0869e62231c29cd380aaea2cdd27f15d2b9354c4ed5582cb24132ad868ce01d");
        assertArticleVersionAndRange(bytes);
    }

    @Test
    @org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named = "FH_PRIVATE_LAW_HTML", matches = ".+")
    void privateOfficialSnapshotMatchesPinnedOriginalAndArticleRange() throws Exception {
        byte[] bytes = Files.readAllBytes(Path.of(System.getenv("FH_PRIVATE_LAW_HTML")));
        org.assertj.core.api.Assertions.assertThat(SourceHashing.sha256(bytes))
                .isEqualTo("abeefe9635c8982cc9c916d03e9028c3ed925ef2cc1bd195917f001d49f169d7");
        assertArticleVersionAndRange(bytes);
    }

    private static void assertArticleVersionAndRange(byte[] bytes) {
        var fetched = new SourceIngestionData.Fetched(URL, "text/html", null, null, bytes, OffsetDateTime.now());
        var parsed = new HtmlSourceDocumentParser().parse(SOURCE, fetched);
        OfficialInformationClaimSelector.validateFetchedCandidate(SOURCE, parsed);
        String content = parsed.normalizedContent();
        org.assertj.core.api.Assertions.assertThat(content).contains("[시행 2026. 1. 2.]",
                "[법률 제21065호, 2025. 10. 1., 타법개정]", "제19조(설명의무)",
                "투자에 따른 위험", "수수료 등 투자성 상품에 관한 중요한 사항");
        int start = content.indexOf("제19조(설명의무) ①");
        int end = content.indexOf("수수료 등 투자성 상품에 관한 중요한 사항으로서 대통령령으로 정하는 사항")
                + "수수료 등 투자성 상품에 관한 중요한 사항으로서 대통령령으로 정하는 사항".length();
        org.assertj.core.api.Assertions.assertThat(start).isGreaterThanOrEqualTo(0);
        org.assertj.core.api.Assertions.assertThat(end).isGreaterThan(start);
        org.assertj.core.api.Assertions.assertThat(content.substring(start, end)).startsWith("제19조(설명의무) ①")
                .endsWith("수수료 등 투자성 상품에 관한 중요한 사항으로서 대통령령으로 정하는 사항");
    }

    @Test
    void verifiedArticleStructureAndVersionCanProceed() {
        assertThatCode(() -> OfficialInformationClaimSelector.validateFetchedCandidate(SOURCE,
                parsed(URL, "금융소비자 보호에 관한 법률\n[시행 2026. 1. 2.] [법률 제21065호, "
                        + "2025. 10. 1., 타법개정]\n제19조(설명의무) ① 일반금융소비자에게 설명한다.\n"
                        + "투자에 따른 위험\n수수료 등 투자성 상품에 관한 중요한 사항으로서 대통령령으로 정하는 사항")))
                .doesNotThrowAnyException();
    }

    @Test
    void maintenancePageCannotBeStoredAsNewLawVersion() {
        assertThatThrownBy(() -> OfficialInformationClaimSelector.validateFetchedCandidate(SOURCE,
                parsed("https://www.law.go.kr/sorry/sorry.html?lsJoLnkSeq=1031274847",
                        "시스템 점검\n법령정보 열람 관련 대체사이트\n시스템 작업(중단) 시간")))
                .isInstanceOf(SourceIngestionException.class)
                .extracting("code").isEqualTo("SOURCE_TEMPORARILY_UNAVAILABLE");
    }

    @Test
    void conflictingVersionHeadersCannotBeSelected() {
        String body = "금융소비자 보호에 관한 법률\n[시행 2026. 1. 2.] [법률 제21065호, 2025. 10. 1., 타법개정]\n"
                + "[시행 2027. 1. 2.] [법률 제22065호, 2026. 10. 1., 타법개정]\n"
                + "제19조(설명의무) ① 일반금융소비자에게 설명한다.\n투자에 따른 위험\n"
                + "수수료 등 투자성 상품에 관한 중요한 사항으로서 대통령령으로 정하는 사항";
        assertThatThrownBy(() -> OfficialInformationClaimSelector.validateFetchedCandidate(SOURCE, parsed(URL, body)))
                .isInstanceOf(SourceIngestionException.class)
                .extracting("code").isEqualTo("SOURCE_OFFICIAL_CONTENT_UNAVAILABLE");
    }

    private static SourceIngestionData.Parsed parsed(String resolvedUrl, String body) {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        return new SourceIngestionData.Parsed(resolvedUrl, "조문정보", null, OffsetDateTime.now(),
                "text/html", null, null, SourceHashing.sha256(bytes), SourceHashing.sha256(body), bytes, body);
    }
}
