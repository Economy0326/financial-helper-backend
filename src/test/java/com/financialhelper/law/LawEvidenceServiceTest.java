package com.financialhelper.law;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.LocalDate;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LawEvidenceServiceTest {

    @Mock
    private KoreanLawOpenApiClient client;

    private LawEvidenceService service;

    @BeforeEach
    void setUp() {
        service = new LawEvidenceService(client, properties());
    }

    @Test
    void validates_current_document_and_marks_url_as_derived() {
        KoreanLawOpenApiClient.LawVersion version = version(
                "여신전문금융업법", "000536", "277267",
                LocalDate.of(2025, 10, 1), LocalDate.of(2025, 10, 1), true);
        when(client.searchLaw("여신전문금융업법")).thenReturn(List.of(version));
        when(client.getLawText(version, "제16조")).thenReturn(document(version, "제16조"));

        LawEvidence evidence = service.lookup(
                new LawEvidenceRequest("여신전문금융업법", "제16조", null));

        assertThat(evidence.lawIdentifier()).isEqualTo("000536");
        assertThat(evidence.mst()).isEqualTo("277267");
        assertThat(evidence.effectiveDate()).isEqualTo("20251001");
        assertThat(evidence.acquisitionKind()).isEqualTo("DIRECT_KOREAN_LAW_OPEN_API");
        assertThat(evidence.canonicalUrl()).isNull();
        assertThat(evidence.sourceUrl()).isNull();
        assertThat(evidence.derivedUrl()).startsWith("https://www.law.go.kr/");
        assertThat(evidence.serverVersion()).isEqualTo("law-open-api");
        assertThat(evidence.serverCommit()).isEqualTo("unversioned");
    }

    @Test
    void selects_latest_version_effective_on_or_before_incident_date() {
        KoreanLawOpenApiClient.LawVersion current = version(
                "여신전문금융업법", "000536", "277267",
                LocalDate.of(2025, 10, 1), LocalDate.of(2025, 10, 1), true);
        KoreanLawOpenApiClient.LawVersion historical = version(
                "여신전문금융업법", "000536", "248927",
                LocalDate.of(2023, 3, 21), LocalDate.of(2023, 6, 22), false);
        when(client.searchLaw("여신전문금융업법")).thenReturn(List.of(current, historical));
        when(client.getLawText(historical, "제16조")).thenReturn(document(historical, "제16조"));

        LawEvidence evidence = service.lookup(new LawEvidenceRequest(
                "여신전문금융업법", "제16조", LocalDate.of(2024, 1, 1)));

        assertThat(evidence.mst()).isEqualTo("248927");
        assertThat(evidence.effectiveDate()).isEqualTo("20230622");
        assertThat(evidence.applicabilityBasis()).isEqualTo("INCIDENT_DATE");
    }

    @Test
    void future_only_version_fails_closed_for_historical_incident() {
        KoreanLawOpenApiClient.LawVersion future = version(
                "여신전문금융업법", "000536", "277267",
                LocalDate.of(2025, 10, 1), LocalDate.of(2025, 10, 1), true);
        when(client.searchLaw("여신전문금융업법")).thenReturn(List.of(future));

        assertThatThrownBy(() -> service.lookup(new LawEvidenceRequest(
                "여신전문금융업법", "제16조", LocalDate.of(2024, 1, 1))))
                .isInstanceOf(KoreanLawOpenApiException.class)
                .hasMessageContaining("no version applicable");
    }

    @Test
    void mismatched_identity_or_locator_is_rejected() {
        KoreanLawOpenApiClient.LawVersion version = version(
                "여신전문금융업법", "000536", "277267",
                LocalDate.of(2025, 10, 1), LocalDate.of(2025, 10, 1), true);
        when(client.searchLaw("여신전문금융업법")).thenReturn(List.of(version));
        when(client.getLawText(version, "제16조")).thenReturn(document(version, "제17조"));

        assertThatThrownBy(() -> service.lookup(
                new LawEvidenceRequest("여신전문금융업법", "제16조", null)))
                .isInstanceOf(KoreanLawOpenApiException.class)
                .hasMessageContaining("requested article");
    }

    @Test
    void unrelated_search_result_is_rejected() {
        KoreanLawOpenApiClient.LawVersion version = version(
                "전자금융거래법", "010199", "280277",
                LocalDate.of(2025, 1, 1), LocalDate.of(2025, 1, 1), true);
        when(client.searchLaw("여신전문금융업법")).thenReturn(List.of(version));

        assertThatThrownBy(() -> service.lookup(
                new LawEvidenceRequest("여신전문금융업법", "제16조", null)))
                .isInstanceOf(KoreanLawOpenApiException.class)
                .hasMessageContaining("different statute");
    }

    private KoreanLawOpenApiProperties properties() {
        return new KoreanLawOpenApiProperties(
                true,
                "https://www.law.go.kr/DRF",
                Duration.ofSeconds(1),
                Duration.ofSeconds(2),
                "test-oc",
                "law-open-api",
                "unversioned");
    }

    private KoreanLawOpenApiClient.LawVersion version(
            String name,
            String id,
            String mst,
            LocalDate promulgation,
            LocalDate effective,
            boolean current
    ) {
        return new KoreanLawOpenApiClient.LawVersion(
                name, id, mst, promulgation, effective, current,
                "https://www.law.go.kr/DRF/lawService.do?target=law&MST=" + mst);
    }

    private KoreanLawOpenApiClient.LawDocument document(
            KoreanLawOpenApiClient.LawVersion version,
            String locator
    ) {
        return new KoreanLawOpenApiClient.LawDocument(
                version.statuteName(), version.lawIdentifier(), version.mst(), locator,
                locator + " 본문", version.promulgationDate(), version.effectiveDate(),
                version.derivedUrl());
    }
}
