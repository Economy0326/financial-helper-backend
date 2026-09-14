package com.financialhelper.retrieval;

import java.util.UUID;

/** A search-scoped handle; it is not itself an approved evidence snapshot. */
public record OfficialEvidenceCandidate(
        UUID candidateId,
        UUID sourceChunkId,
        UUID sourceDocumentId,
        UUID retrievalGenerationId,
        int rank,
        Double semanticScore,
        Double keywordScore,
        double rrfScore,
        String organizationName,
        String title,
        int documentVersion,
        String parentSection,
        String representationMetadataJson,
        String articleReference,
        String pageReference,
        String locator,
        String canonicalUrl,
        String body
) {
}
