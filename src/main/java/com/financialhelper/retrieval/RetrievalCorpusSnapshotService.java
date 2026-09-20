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

/** remote retrieval 호출 전에 짧은 detached snapshot을 읽는다. */
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
                        chunk.getSourceDocument().getCanonicalUrl(),
                        chunk.getSourceDocument().getSourceRegistry().getSourceKey()))
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
            String canonicalUrl,
            String sourceKey
    ) {
    /** focused retrieval test를 위한 호환 생성자다. */
        public EligibleChunk(
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
            this(sourceChunkId, body, sourceDocumentId, documentVersion,
                    applicabilityStartDate, applicabilityEndDate,
                    representationMetadataJson, organizationName, title,
                    parentSection, articleReference, pageReference, locator,
                    canonicalUrl, null);
        }
    }
}
