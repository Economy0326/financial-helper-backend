package com.financialhelper.law;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import tools.jackson.databind.json.JsonMapper;
import com.financialhelper.ai.grounded.ReviewedCardLawEvidenceService;

import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 선택 실행 live smoke이며 일반 test suite에서는 실행하거나 LAW_OC를 저장하지 않는다. */
@EnabledIfEnvironmentVariable(named = "LAW_OPEN_API_LIVE_SMOKE", matches = "true")
class KoreanLawOpenApiLiveSmokeTest {

    @Test
    void validates_card_and_breadth_current_and_2024_historical_versions() {
        String lawOc = System.getenv("LAW_OC");
        KoreanLawOpenApiProperties properties = new KoreanLawOpenApiProperties(
                true,
                System.getenv().getOrDefault("LAW_OPEN_API_BASE_URL", "https://www.law.go.kr/DRF"),
                Duration.ofSeconds(5),
                Duration.ofSeconds(30),
                lawOc,
                "law-open-api",
                "unversioned");
        KoreanLawOpenApiClient client = new KoreanLawOpenApiHttpClient(
                properties,
                JsonMapper.builder().build());
        LawEvidenceService service = new LawEvidenceService(client, properties);

        List<LawEvidenceRequest> requests = List.of(
                new LawEvidenceRequest("여신전문금융업법", "제16조", null),
                new LawEvidenceRequest("여신전문금융업법 시행령", "제6조의9", null),
                new LawEvidenceRequest("전자금융거래법", "제9조", null),
                new LawEvidenceRequest("전자금융거래법", "제10조", null),
                new LawEvidenceRequest("전자금융거래법 시행령", "제8조", null),
                new LawEvidenceRequest(
                        "전기통신금융사기 피해 방지 및 피해금 환급에 관한 특별법", "제3조", null),
                new LawEvidenceRequest(
                        "전기통신금융사기 피해 방지 및 피해금 환급에 관한 특별법", "제4조", null)
        );
        Map<String, List<String>> expectedIdentity = Map.of(
                "여신전문금융업법|제16조", List.of("000536", "277267", "248927"),
                "여신전문금융업법 시행령|제6조의9", List.of("004186", "285799", "256643"),
                "전자금융거래법|제9조", List.of("010199", "280277", "218909"),
                "전자금융거래법|제10조", List.of("010199", "280277", "218909"),
                "전자금융거래법 시행령|제8조", List.of("010366", "285727", "256699")
                ,"전기통신금융사기 피해 방지 및 피해금 환급에 관한 특별법|제3조",
                        List.of("011359", "289413", "251011")
                ,"전기통신금융사기 피해 방지 및 피해금 환급에 관한 특별법|제4조",
                        List.of("011359", "289413", "251011")
        );
        for (LawEvidenceRequest request : requests) {
            var versions = client.searchLaw(request.lawName());
            var selected = LawEvidenceService.selectVersion(versions, request);
            client.getLawText(selected, request.articleLocator());
            LawEvidence current = service.lookup(request);
            assertThat(current.acquisitionKind()).isEqualTo(LawEvidenceService.ACQUISITION_KIND);
            assertThat(current.canonicalUrl()).isNull();
            assertThat(current.sourceUrl()).isNull();
            assertThat(current.derivedUrl()).doesNotContain("OC=");
            List<String> identity = expectedIdentity.get(request.lawName() + "|" + request.articleLocator());
            assertThat(identity).isNotNull();
            assertThat(current.lawIdentifier()).isEqualTo(identity.get(0));
            assertThat(current.mst()).isEqualTo(identity.get(1));

            LawEvidence historical = service.lookup(new LawEvidenceRequest(
                    request.lawName(), request.articleLocator(), LocalDate.of(2024, 1, 1)));
            assertThat(historical.effectiveDate()).isLessThanOrEqualTo("20240101");
            assertThat(historical.lawIdentifier()).isEqualTo(current.lawIdentifier());
            assertThat(historical.mst()).isEqualTo(identity.get(2));
        }

        var reviewedCard = new ReviewedCardLawEvidenceService(service, properties);
        assertThat(reviewedCard.load(LocalDate.of(2026, 9, 17))).hasSize(5);
    }
}
