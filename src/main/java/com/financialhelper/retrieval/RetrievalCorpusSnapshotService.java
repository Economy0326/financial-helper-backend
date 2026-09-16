package com.financialhelper.retrieval;

import com.financialhelper.source.SourceChunk;
import com.financialhelper.source.SourceChunkIndexing;
import com.financialhelper.source.SourceChunkIndexingRepository;
import com.financialhelper.source.SourceChunkReviewStatus;
import com.financialhelper.source.SourceDocumentStatus;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/** Reads a short, detached snapshot before any remote retrieval call. */
@Service
public class RetrievalCorpusSnapshotService {
    private final SourceChunkIndexingRepository indexingRepository;

    public RetrievalCorpusSnapshotService(SourceChunkIndexingRepository indexingRepository) {
        this.indexingRepository = indexingRepository;
    }

    @Transactional(readOnly = true)
    public List<EligibleChunk> readyChunks(UUID generationId) {
        return indexingRepository.findAllReadyByGenerationId(generationId).stream()
                .map(SourceChunkIndexing::getSourceChunk)
                .filter(chunk -> chunk.getReviewStatus() == SourceChunkReviewStatus.APPROVED)
                .filter(chunk -> chunk.getSourceDocument().getStatus() == SourceDocumentStatus.ACTIVE)
                .map(chunk -> new EligibleChunk(
                        chunk.getId(),
                        chunk.getBody(),
                        chunk.getSourceDocument().getId(),
                        chunk.getSourceDocument().getDocumentVersion(),
                        chunk.getSourceDocument().getApplicabilityStartDate(),
                        chunk.getSourceDocument().getApplicabilityEndDate(),
                        chunk.getRepresentationMetadataJson(),
                        chunk.getSourceDocument().getOrganizationName(),
                        chunk.getSourceDocument().getTitle(),
                        chunk.getParentSection(),
                        chunk.getArticleReference(),
                        chunk.getPageReference(),
                        chunk.getLocator(),
                        chunk.getSourceDocument().getCanonicalUrl()))
                .toList();
    }

    public record EligibleChunk(
            UUID sourceChunkId,
            String body,
            UUID sourceDocumentId,
            int documentVersion,
            java.time.LocalDate applicabilityStartDate,
            java.time.LocalDate applicabilityEndDate,
            String representationMetadataJson,
            String organizationName,
            String title,
            String parentSection,
            String articleReference,
            String pageReference,
            String locator,
            String canonicalUrl
    ) {
    }
}
