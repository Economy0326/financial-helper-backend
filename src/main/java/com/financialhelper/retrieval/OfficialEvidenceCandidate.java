package com.financialhelper.retrieval;

import java.util.UUID;

/** 검색 범위의 handle이며 그 자체가 승인된 Evidence snapshot은 아니다. */
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
        String body,
        java.time.LocalDate publishedAt,
        String currentApplicability
) {
    public OfficialEvidenceCandidate(UUID candidateId, UUID sourceChunkId, UUID sourceDocumentId,
            UUID retrievalGenerationId, int rank, Double semanticScore, Double keywordScore, double rrfScore,
            String organizationName, String title, int documentVersion, String parentSection,
            String representationMetadataJson, String articleReference, String pageReference, String locator,
            String canonicalUrl, String body) {
        this(candidateId, sourceChunkId, sourceDocumentId, retrievalGenerationId, rank, semanticScore, keywordScore,
                rrfScore, organizationName, title, documentVersion, parentSection, representationMetadataJson,
                articleReference, pageReference, locator, canonicalUrl, body, null, null);
    }
}
