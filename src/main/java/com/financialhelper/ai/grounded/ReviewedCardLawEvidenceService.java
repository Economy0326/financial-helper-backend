package com.financialhelper.ai.grounded;

import com.financialhelper.law.LawEvidence;
import com.financialhelper.law.LawEvidenceRequest;
import com.financialhelper.law.LawEvidenceService;
import com.financialhelper.law.KoreanLawOpenApiProperties;

import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Set;

/**
 * The reviewed CARD law allowlist is deliberately narrow.  Runtime law text
 * is accepted only when the direct Work 5.5 adapter validates the exact statute
 * and locator; this class never turns a law response into a financial action.
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
                    .peek(this::validateReviewedIdentity)
                    .map(AnalysisEvidenceSnapshotData.ReviewedLawEvidence::from)
                    .toList();
        } catch (RuntimeException exception) {
            throw new GroundedEvidenceUnavailableException(
                    "reviewed CARD law evidence is unavailable or not applicable", exception);
        }
    }

    private void validateReviewedIdentity(LawEvidence evidence) {
        Set<String> allowed = Set.of(
                "여신전문금융업법|제16조",
                "여신전문금융업법 시행령|제6조의9",
                "전자금융거래법|제9조",
                "전자금융거래법|제10조",
                "전자금융거래법 시행령|제8조");
        if (!allowed.contains(evidence.statuteName() + "|" + evidence.articleLocator())) {
            throw new IllegalArgumentException("law evidence is outside the reviewed CARD allowlist");
        }
        if (!properties.providerRevision().equals(evidence.serverCommit())
                || !properties.providerVersion().equals(evidence.serverVersion())
                || !com.financialhelper.law.LawEvidenceService.ACQUISITION_KIND
                .equals(evidence.acquisitionKind())) {
            throw new IllegalArgumentException("law evidence provider provenance is not validated");
        }
        try {
            LocalDate.parse(evidence.effectiveDate(), DateTimeFormatter.BASIC_ISO_DATE);
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("law evidence effective date is invalid", exception);
        }
    }
}
