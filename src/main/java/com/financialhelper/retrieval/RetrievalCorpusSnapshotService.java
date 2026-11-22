package com.financialhelper.retrieval;

import com.financialhelper.source.SourceChunk;
import com.financialhelper.source.SourceChunkIndexing;
import com.financialhelper.source.SourceChunkIndexingRepository;
import com.financialhelper.source.SourceChunkReviewStatus;
import com.financialhelper.source.SourceDocumentStatus;
import com.financialhelper.source.DocumentClaimReviewerService;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/** remote retrieval 호출 전에 짧은 detached snapshot을 읽는다. */
@Service
public class RetrievalCorpusSnapshotService {
    private final SourceChunkIndexingRepository indexingRepository;
    private final DocumentClaimReviewerService genericReviews;

    @org.springframework.beans.factory.annotation.Autowired
    public RetrievalCorpusSnapshotService(SourceChunkIndexingRepository indexingRepository,
                                           @org.springframework.context.annotation.Lazy DocumentClaimReviewerService genericReviews) {
        this.indexingRepository = indexingRepository;
        this.genericReviews = genericReviews;
    }

    /** Existing isolated callers lack generic review access and therefore fail closed for generic chunks. */
    public RetrievalCorpusSnapshotService(SourceChunkIndexingRepository indexingRepository) {
        this.indexingRepository = indexingRepository;
        this.genericReviews = null;
    }

    @Transactional(readOnly = true)
    public List<EligibleChunk> readyChunks(UUID generationId) {
        return eligible(indexingRepository.findAllReadyByGenerationId(generationId));
    }

    @Transactional(readOnly = true)
    public List<EligibleChunk> readyChunks(UUID generationId, java.util.Set<UUID> chunkIds) {
        if (chunkIds.isEmpty()) return List.of();
        return eligible(indexingRepository.findReadyByGenerationIdAndChunkIds(generationId, chunkIds));
    }

    @Transactional(readOnly = true)
    public boolean matchesStoredCorpus(com.financialhelper.source.RetrievalGeneration generation) {
        var mappings = indexingRepository.findAllReadyByGenerationId(generation.getId());
        if (mappings.size() != generation.getReadyChunkCount()) return false;
        var chunks = mappings.stream().map(SourceChunkIndexing::getSourceChunk).toList();
        return java.util.Objects.equals(generation.getCorpusSnapshotSha256(),
                RetrievalGenerationIndexingPersistenceService.corpusSnapshot(chunks,
                        RetrievalGenerationIndexingPersistenceService.searchContext(generation.getMetadataJson())));
    }

    private List<EligibleChunk> eligible(List<SourceChunkIndexing> mappings) {
        // This classification is immutable within this snapshot read. Reuse it
        // when constructing the result instead of querying it again per chunk.
        var registeredClaims = new java.util.HashMap<UUID, Boolean>();
        return mappings.stream()
                .filter(mapping -> mayDisplay(mapping, registeredClaims.computeIfAbsent(
                        mapping.getSourceChunk().getId(), id -> genericReviews != null && genericReviews.isDocumentClaimChunk(id))))
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
                        chunk.getSourceDocument().getSourceRegistry().getSourceKey(),
                        chunk.getSourceDocument().getPublishedAt(),
                        Boolean.TRUE.equals(registeredClaims.get(chunk.getId()))))
                .toList();
    }

    private boolean mayDisplay(SourceChunkIndexing mapping, boolean registered) {
        SourceChunk chunk = mapping.getSourceChunk();
        // An orphaned old generic chunk must never fall through as a legacy source.
        boolean oldGenericMarker = java.util.Set.of("generic-claim-v1", "generic-claim-v2", "generic-claim-v3").contains(chunk.getChunkConfigVersion());
        var generation = mapping.getRetrievalGeneration();
        String metadata = generation == null ? null : generation.getMetadataJson();
        boolean expectedGeneric = RetrievalGenerationIndexingPersistenceService.includesDocumentClaims(metadata)
                && (!RetrievalGenerationIndexingPersistenceService.chunkIds(metadata).contains(chunk.getId())
                || RetrievalGenerationIndexingPersistenceService.documentClaimDocumentIds(metadata)
                        .contains(chunk.getSourceDocument().getId()));
        if (genericReviews == null) return !(oldGenericMarker || expectedGeneric);
        return !(registered || oldGenericMarker || expectedGeneric)
                || registered && generation != null && genericReviews.genericChunkMayBeDisplayedForGeneration(chunk.getId(), generation.getId());
    }

    /** Legacy passage expansion is preserved; document claims must be current generation members. */
    public boolean mayExpand(SourceChunk chunk, java.util.Set<UUID> readyIds) {
        boolean oldGenericMarker = java.util.Set.of("generic-claim-v1", "generic-claim-v2", "generic-claim-v3").contains(chunk.getChunkConfigVersion());
        if (genericReviews == null) return !oldGenericMarker || readyIds.contains(chunk.getId());
        boolean registered = genericReviews.isDocumentClaimChunk(chunk.getId());
        return !(registered || oldGenericMarker) || readyIds.contains(chunk.getId());
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
            String sourceKey,
            java.time.LocalDate publishedAt,
            boolean documentClaim
    ) {
        public EligibleChunk(UUID sourceChunkId, String body, UUID sourceDocumentId, int documentVersion,
                java.time.LocalDate applicabilityStartDate, java.time.LocalDate applicabilityEndDate,
                String representationMetadataJson, String organizationName, String title, String parentSection,
                String articleReference, String pageReference, String locator, String canonicalUrl, String sourceKey) {
            this(sourceChunkId, body, sourceDocumentId, documentVersion, applicabilityStartDate, applicabilityEndDate,
                    representationMetadataJson, organizationName, title, parentSection, articleReference, pageReference,
                    locator, canonicalUrl, sourceKey, null, false);
        }
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
