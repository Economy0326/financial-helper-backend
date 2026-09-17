package com.financialhelper.ai.grounded;

import com.financialhelper.law.KoreanLawOpenApiProperties;
import com.financialhelper.law.LawEvidence;
import com.financialhelper.law.LawEvidenceRequest;
import com.financialhelper.law.LawEvidenceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReviewedCardLawEvidenceServiceTest {

    @Mock
    private LawEvidenceService lawEvidenceService;

    private ReviewedCardLawEvidenceService service;

    @BeforeEach
    void setUp() {
        service = new ReviewedCardLawEvidenceService(lawEvidenceService, properties());
    }

    @Test
    void voice_phishing_loads_only_reviewed_current_law_articles() {
        when(lawEvidenceService.lookup(any(LawEvidenceRequest.class)))
                .thenAnswer(invocation -> voiceEvidence(invocation.getArgument(0)));

        var result = service.loadForScenario(
                "VOICE_PHISHING_SUSPICIOUS_TRANSFER", LocalDate.of(2026, 9, 17));

        assertThat(result).hasSize(2)
                .extracting(AnalysisEvidenceSnapshotData.ReviewedLawEvidence::evidenceId)
                .containsExactly(
                        "law:011359:289413:제3조",
                        "law:011359:289413:제4조");
        verify(lawEvidenceService, atLeastOnce()).lookup(any(LawEvidenceRequest.class));
    }

    @Test
    void historical_voice_phishing_version_is_accepted_only_for_reviewed_mst() {
        when(lawEvidenceService.lookup(any(LawEvidenceRequest.class)))
                .thenAnswer(invocation -> historicalVoiceEvidence(invocation.getArgument(0)));

        var result = service.loadForScenario(
                "VOICE_PHISHING_SUSPICIOUS_TRANSFER", LocalDate.of(2024, 1, 1));

        assertThat(result).hasSize(2)
                .allSatisfy(evidence -> {
                    assertThat(evidence.lawIdentifier()).isEqualTo("011359");
                    assertThat(evidence.mst()).isEqualTo("251011");
                    assertThat(evidence.effectiveDate()).isEqualTo("20231117");
                });
    }

    @Test
    void unknown_incident_date_does_not_apply_current_law() {
        assertThat(service.loadForScenario(
                "VOICE_PHISHING_SUSPICIOUS_TRANSFER", null)).isEmpty();
        verifyNoInteractions(lawEvidenceService);
    }

    @Test
    void mismatched_law_identity_fails_closed() {
        when(lawEvidenceService.lookup(any(LawEvidenceRequest.class)))
                .thenReturn(evidence(
                        "전기통신금융사기 피해 방지 및 피해금 환급에 관한 특별법",
                        "010199", "289413", "제3조", LocalDate.of(2026, 9, 8)));

        assertThatThrownBy(() -> service.loadForScenario(
                "VOICE_PHISHING_SUSPICIOUS_TRANSFER", LocalDate.of(2026, 9, 17)))
                .isInstanceOf(GroundedEvidenceUnavailableException.class)
                .hasMessageContaining("unavailable or not applicable");
    }

    @Test
    void personal_information_scenario_has_no_unreviewed_law_dependency() {
        assertThat(service.loadForScenario(
                "PERSONAL_INFO_SMISHING_MALICIOUS_APP", LocalDate.of(2026, 9, 17)))
                .isEmpty();
        verifyNoInteractions(lawEvidenceService);
    }

    private LawEvidence voiceEvidence(LawEvidenceRequest request) {
        return evidence(
                request.lawName(), "011359", "289413", request.articleLocator(),
                LocalDate.of(2026, 9, 8));
    }

    private LawEvidence historicalVoiceEvidence(LawEvidenceRequest request) {
        return evidence(
                request.lawName(), "011359", "251011", request.articleLocator(),
                LocalDate.of(2023, 11, 17));
    }

    private LawEvidence evidence(
            String statuteName,
            String lawIdentifier,
            String mst,
            String article,
            LocalDate effectiveDate
    ) {
        return new LawEvidence(
                statuteName,
                lawIdentifier,
                mst,
                article,
                "20260203",
                effectiveDate.toString().replace("-", ""),
                null,
                null,
                article + " 본문",
                Instant.parse("2026-09-17T00:00:00Z"),
                "law-open-api",
                "unversioned",
                "lawService.do",
                LawEvidenceService.ACQUISITION_KIND,
                "INCIDENT_DATE",
                "VALIDATED_PENDING_REVIEW",
                "https://www.law.go.kr/DRF/lawService.do?target=eflaw&MST=" + mst);
    }

    private KoreanLawOpenApiProperties properties() {
        return new KoreanLawOpenApiProperties(
                true,
                "https://www.law.go.kr/DRF",
                Duration.ofSeconds(1),
                Duration.ofSeconds(2),
                null,
                "law-open-api",
                "unversioned");
    }
}
