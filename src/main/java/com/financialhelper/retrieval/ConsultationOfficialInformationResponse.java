package com.financialhelper.retrieval;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** FOUND는 승인된 공식 문서 후보의 발견만 뜻한다. 사건 적용성 승인이 아니다. */
public record ConsultationOfficialInformationResponse(
        UUID consultationId,
        long caseInputRevision,
        long followUpAnswerRevision,
        String scenario,
        Scope scope,
        String status,
        boolean degraded,
        List<String> missingFacts,
        List<String> coverageGaps,
        List<String> limitations,
        List<Candidate> candidates
) {
    public ConsultationOfficialInformationResponse {
        missingFacts = List.copyOf(missingFacts);
        coverageGaps = List.copyOf(coverageGaps);
        limitations = List.copyOf(limitations);
        candidates = List.copyOf(candidates);
    }

    public record Scope(String institution, String productType, LocalDate incidentDate) {}

    public record Candidate(
            UUID sourceDocumentId,
            int documentVersion,
            UUID sourceChunkId,
            UUID retrievalGenerationId,
            String organizationName,
            String title,
            String canonicalUrl,
            String articleReference,
            String pageReference,
            String locator,
            String excerpt,
            String scopeKind,
            UUID applicabilityReviewId,
            String applicableInstitution,
            String applicableProduct,
            LocalDate applicabilityStartDate,
            LocalDate applicabilityEndDate,
            List<String> conditionLocators,
            List<String> exceptionLocators
    ) {}
}
