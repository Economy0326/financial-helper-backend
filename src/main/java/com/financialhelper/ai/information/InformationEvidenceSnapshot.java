package com.financialhelper.ai.information;

import com.financialhelper.retrieval.ConfirmedCaseSnapshotData;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Detached, candidate-only evidence. It does not approve applicability to an individual case. */
public record InformationEvidenceSnapshot(
        UUID consultationId,
        long caseInputRevision,
        long followUpAnswerRevision,
        String scenario,
        UUID retrievalGenerationId,
        List<ConfirmedCaseSnapshotData.Fact> confirmedFacts,
        List<String> missingFacts,
        List<String> coverageGaps,
        List<Evidence> evidence,
        String sourceIntegrityStatus,
        String humanScopeReviewStatus,
        String caseApplicabilityStatus
) {
    public InformationEvidenceSnapshot {
        confirmedFacts = List.copyOf(confirmedFacts);
        missingFacts = List.copyOf(missingFacts);
        coverageGaps = List.copyOf(coverageGaps);
        evidence = List.copyOf(evidence);
    }

    public record Evidence(
            UUID sourceDocumentId,
            int documentVersion,
            String rawSha256,
            UUID supportChunkId,
            UUID applicabilityReviewId,
            String scopeKind,
            String organizationName,
            String title,
            String canonicalUrl,
            String articleReference,
            String pageReference,
            String supportLocator,
            String supportText,
            String applicableInstitution,
            String applicableProduct,
            LocalDate applicabilityStartDate,
            LocalDate applicabilityEndDate,
            List<RelatedText> conditions,
            List<RelatedText> exceptions
    ) {
        public Evidence {
            conditions = List.copyOf(conditions);
            exceptions = List.copyOf(exceptions);
        }
    }

    public record RelatedText(UUID chunkId, String locator, String text) {}
}
