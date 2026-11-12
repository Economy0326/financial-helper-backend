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
        String caseApplicabilityStatus,
        String financialTopic,
        LocalDate referenceDate
) {
    public InformationEvidenceSnapshot(UUID consultationId, long caseInputRevision,
            long followUpAnswerRevision, String scenario, UUID retrievalGenerationId,
            List<ConfirmedCaseSnapshotData.Fact> confirmedFacts, List<String> missingFacts,
            List<String> coverageGaps, List<Evidence> evidence, String sourceIntegrityStatus,
            String humanScopeReviewStatus, String caseApplicabilityStatus) {
        this(consultationId, caseInputRevision, followUpAnswerRevision, scenario,
                retrievalGenerationId, confirmedFacts, missingFacts, coverageGaps, evidence,
                sourceIntegrityStatus, humanScopeReviewStatus, caseApplicabilityStatus, null, null);
    }
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
            List<RelatedText> exceptions,
            Integer questionNumber,
            String metadataSourceUrl
    ) {
        public Evidence(UUID sourceDocumentId, int documentVersion, String rawSha256,
                UUID supportChunkId, UUID applicabilityReviewId, String scopeKind,
                String organizationName, String title, String canonicalUrl,
                String articleReference, String pageReference, String supportLocator,
                String supportText, String applicableInstitution, String applicableProduct,
                LocalDate applicabilityStartDate, LocalDate applicabilityEndDate,
                List<RelatedText> conditions, List<RelatedText> exceptions) {
            this(sourceDocumentId, documentVersion, rawSha256, supportChunkId,
                    applicabilityReviewId, scopeKind, organizationName, title, canonicalUrl,
                    articleReference, pageReference, supportLocator, supportText,
                    applicableInstitution, applicableProduct, applicabilityStartDate,
                    applicabilityEndDate, conditions, exceptions, null, null);
        }
        public Evidence {
            conditions = List.copyOf(conditions);
            exceptions = List.copyOf(exceptions);
        }
    }

    public record RelatedText(UUID chunkId, String locator, String text) {}
}
