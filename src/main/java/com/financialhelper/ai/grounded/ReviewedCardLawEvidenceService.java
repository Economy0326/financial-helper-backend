package com.financialhelper.ai.grounded;

import com.financialhelper.law.LawEvidence;
import com.financialhelper.law.LawEvidenceRequest;
import com.financialhelper.law.LawEvidenceService;
import com.financialhelper.law.KoreanLawOpenApiProperties;

import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.List;
import java.util.Set;

/**
 * 검토된 CARD law allowlist는 의도적으로 제한한다. direct Work 5.5 adapter가
 * 정확한 법령과 locator를 검증한 경우에만 runtime 법령 text를 허용한다.
 * 이 class는 법령 응답을 financial action으로 변환하지 않는다.
 */
@Service
public class ReviewedCardLawEvidenceService {
    private final LawEvidenceService lawEvidenceService;
    private final KoreanLawOpenApiProperties properties;

    public ReviewedCardLawEvidenceService(
            LawEvidenceService lawEvidenceService,
            KoreanLawOpenApiProperties properties
    ) {
        this.lawEvidenceService = lawEvidenceService;
        this.properties = properties;
    }

    public List<AnalysisEvidenceSnapshotData.ReviewedLawEvidence> load(LocalDate incidentDate) {
        List<LawEvidenceRequest> requests = List.of(
                new LawEvidenceRequest("여신전문금융업법", "제16조", incidentDate),
                new LawEvidenceRequest("여신전문금융업법 시행령", "제6조의9", incidentDate),
                new LawEvidenceRequest("전자금융거래법", "제9조", incidentDate),
                new LawEvidenceRequest("전자금융거래법", "제10조", incidentDate),
                new LawEvidenceRequest("전자금융거래법 시행령", "제8조", incidentDate)
        );
        try {
            return requests.stream().map(lawEvidenceService::lookup)
                    .peek(evidence -> validateReviewedIdentity(evidence, "CARD_LOSS_UNAUTHORIZED_USE"))
                    .map(AnalysisEvidenceSnapshotData.ReviewedLawEvidence::from)
                    .toList();
        } catch (RuntimeException exception) {
            throw new GroundedEvidenceUnavailableException(
                    "reviewed CARD law evidence is unavailable or not applicable", exception);
        }
    }

    /**
     * breadth scenario에 대해 수동 검토한 제한된 law identity만 불러온다.
     * incident date가 없으면 의도적으로 law evidence를 반환하지 않는다.
     * version을 확정하지 않은 과거 incident에 현재 법령을 암묵적으로 적용해서는 안 된다.
     */
    public List<AnalysisEvidenceSnapshotData.ReviewedLawEvidence> loadForScenario(
            String scenario, LocalDate incidentDate) {
        if ("CARD_LOSS_UNAUTHORIZED_USE".equals(scenario)) return load(incidentDate);
        if (incidentDate == null) return List.of();
        List<LawEvidenceRequest> requests = switch (scenario) {
            case "VOICE_PHISHING_SUSPICIOUS_TRANSFER" -> List.of(
                    new LawEvidenceRequest(
                            "전기통신금융사기 피해 방지 및 피해금 환급에 관한 특별법", "제3조", incidentDate),
                    new LawEvidenceRequest(
                            "전기통신금융사기 피해 방지 및 피해금 환급에 관한 특별법", "제4조", incidentDate));
            case "UNAUTHORIZED_ACCOUNT_TRANSFER" -> List.of(
                    new LawEvidenceRequest("전자금융거래법", "제9조", incidentDate),
                    new LawEvidenceRequest("전자금융거래법", "제10조", incidentDate));
            case "PERSONAL_INFO_SMISHING_MALICIOUS_APP" -> List.of();
            default -> List.of();
        };
        if (requests.isEmpty()) return List.of();
        try {
            return requests.stream().map(lawEvidenceService::lookup)
                    .peek(evidence -> validateReviewedIdentity(evidence, scenario))
                    .map(AnalysisEvidenceSnapshotData.ReviewedLawEvidence::from)
                    .toList();
        } catch (RuntimeException exception) {
            throw new GroundedEvidenceUnavailableException(
                    "reviewed law evidence is unavailable or not applicable for scenario", exception);
        }
    }

    private void validateReviewedIdentity(LawEvidence evidence, String scenario) {
        ReviewedLawIdentity identity = REVIEWED_IDENTITIES.get(
                scenario + "|" + normalize(evidence.statuteName()) + "|" + evidence.articleLocator());
        if (identity == null) {
            throw new IllegalArgumentException("law evidence is outside the reviewed law allowlist");
        }
        if (!identity.lawIdentifier().equals(evidence.lawIdentifier())
                || !identity.acceptedMsts().contains(evidence.mst())) {
            throw new IllegalArgumentException("law evidence identity does not match reviewed law version");
        }
        if (!properties.providerRevision().equals(evidence.serverCommit())
                || !properties.providerVersion().equals(evidence.serverVersion())
                || !com.financialhelper.law.LawEvidenceService.ACQUISITION_KIND
                .equals(evidence.acquisitionKind())) {
            throw new IllegalArgumentException("law evidence provider provenance is not validated");
        }
        try {
            LocalDate effective = LocalDate.parse(evidence.effectiveDate(), DateTimeFormatter.BASIC_ISO_DATE);
            if (!identity.allowAnyCurrentEffectiveDate()
                    && !identity.acceptedEffectiveDates().contains(effective)) {
                throw new IllegalArgumentException(
                        "law evidence effective date is outside reviewed versions: " + evidence.effectiveDate());
            }
        } catch (DateTimeParseException exception) {
            throw new IllegalArgumentException("law evidence effective date is invalid", exception);
        }
    }

    private static String normalize(String value) {
        return value == null ? "" : value.replaceAll("\\s+", "")
                .replace("·", "").replace("ㆍ", "").replace("‧", "");
    }

    private static ReviewedLawIdentity identity(
            String scenario, String statute, String article, String lawIdentifier,
            String currentMst, LocalDate currentEffective, String historicalMst,
            LocalDate historicalEffective
    ) {
        return identity(scenario, statute, article, lawIdentifier, currentMst, currentEffective,
                historicalMst, historicalEffective, false);
    }

    private static ReviewedLawIdentity identity(
            String scenario, String statute, String article, String lawIdentifier,
            String currentMst, LocalDate currentEffective, String historicalMst,
            LocalDate historicalEffective, boolean allowAnyCurrentEffectiveDate
    ) {
        return new ReviewedLawIdentity(
                scenario + "|" + normalize(statute) + "|" + article,
                lawIdentifier,
                Set.of(currentMst, historicalMst),
                Set.of(currentEffective, historicalEffective),
                allowAnyCurrentEffectiveDate);
    }

    private static final Map<String, ReviewedLawIdentity> REVIEWED_IDENTITIES = Map.ofEntries(
            Map.entry("CARD_LOSS_UNAUTHORIZED_USE|여신전문금융업법|제16조",
                    identity("CARD_LOSS_UNAUTHORIZED_USE", "여신전문금융업법", "제16조", "000536",
                            "277267", LocalDate.of(2025, 10, 1), "248927", LocalDate.of(2023, 6, 22), true)),
            Map.entry("CARD_LOSS_UNAUTHORIZED_USE|여신전문금융업법시행령|제6조의9",
                    identity("CARD_LOSS_UNAUTHORIZED_USE", "여신전문금융업법 시행령", "제6조의9", "004186",
                            "285799", LocalDate.of(2025, 10, 1), "256643", LocalDate.of(2023, 7, 1), true)),
            Map.entry("CARD_LOSS_UNAUTHORIZED_USE|전자금융거래법|제9조",
                    identity("CARD_LOSS_UNAUTHORIZED_USE", "전자금융거래법", "제9조", "010199",
                            "280277", LocalDate.of(2025, 12, 16), "218909", LocalDate.of(2020, 12, 10))),
            Map.entry("CARD_LOSS_UNAUTHORIZED_USE|전자금융거래법|제10조",
                    identity("CARD_LOSS_UNAUTHORIZED_USE", "전자금융거래법", "제10조", "010199",
                            "280277", LocalDate.of(2025, 12, 16), "218909", LocalDate.of(2020, 12, 10))),
            Map.entry("CARD_LOSS_UNAUTHORIZED_USE|전자금융거래법시행령|제8조",
                    identity("CARD_LOSS_UNAUTHORIZED_USE", "전자금융거래법 시행령", "제8조", "010366",
                            "285727", LocalDate.of(2026, 4, 28), "256699", LocalDate.of(2020, 12, 10))),
            Map.entry("VOICE_PHISHING_SUSPICIOUS_TRANSFER|전기통신금융사기피해방지및피해금환급에관한특별법|제3조",
                    identity("VOICE_PHISHING_SUSPICIOUS_TRANSFER", "전기통신금융사기 피해 방지 및 피해금 환급에 관한 특별법", "제3조", "011359",
                            "289413", LocalDate.of(2026, 9, 8), "251011", LocalDate.of(2023, 11, 17))),
            Map.entry("VOICE_PHISHING_SUSPICIOUS_TRANSFER|전기통신금융사기피해방지및피해금환급에관한특별법|제4조",
                    identity("VOICE_PHISHING_SUSPICIOUS_TRANSFER", "전기통신금융사기 피해 방지 및 피해금 환급에 관한 특별법", "제4조", "011359",
                            "289413", LocalDate.of(2026, 9, 8), "251011", LocalDate.of(2023, 11, 17))),
            Map.entry("UNAUTHORIZED_ACCOUNT_TRANSFER|전자금융거래법|제9조",
                    identity("UNAUTHORIZED_ACCOUNT_TRANSFER", "전자금융거래법", "제9조", "010199",
                            "280277", LocalDate.of(2025, 12, 16), "218909", LocalDate.of(2020, 12, 10))),
            Map.entry("UNAUTHORIZED_ACCOUNT_TRANSFER|전자금융거래법|제10조",
                    identity("UNAUTHORIZED_ACCOUNT_TRANSFER", "전자금융거래법", "제10조", "010199",
                            "280277", LocalDate.of(2025, 12, 16), "218909", LocalDate.of(2020, 12, 10)))
    );

    private record ReviewedLawIdentity(
            String key, String lawIdentifier, Set<String> acceptedMsts,
            Set<LocalDate> acceptedEffectiveDates, boolean allowAnyCurrentEffectiveDate
    ) {}
}
