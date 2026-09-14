package com.financialhelper.retrieval;

import com.financialhelper.source.RetrievalGeneration;
import com.financialhelper.source.RetrievalGenerationRepository;
import com.financialhelper.source.RetrievalGenerationStatus;
import com.financialhelper.source.SourceChunk;
import com.financialhelper.source.SourceChunkIndexing;
import com.financialhelper.source.SourceChunkIndexingPersistenceService;
import com.financialhelper.source.SourceChunkIndexingRepository;
import com.financialhelper.source.SourceChunkIndexingStatus;
import com.financialhelper.source.SourceChunkRepository;
import com.financialhelper.source.SourceChunkReviewStatus;
import com.financialhelper.source.SourceDocumentStatus;
import com.financialhelper.source.SourceHashing;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Short database transactions around the external KURE build.  No HTTP call
 * is made from this class; it only snapshots, validates and persists state.
 */
@Service
public class RetrievalGenerationIndexingPersistenceService {

    private final RetrievalGenerationRepository generationRepository;
    private final SourceChunkRepository chunkRepository;
    private final SourceChunkIndexingRepository indexingRepository;
    private final SourceChunkIndexingPersistenceService indexingPersistence;

    public RetrievalGenerationIndexingPersistenceService(
            RetrievalGenerationRepository generationRepository,
            SourceChunkRepository chunkRepository,
            SourceChunkIndexingRepository indexingRepository,
            SourceChunkIndexingPersistenceService indexingPersistence
    ) {
        this.generationRepository = generationRepository;
        this.chunkRepository = chunkRepository;
        this.indexingRepository = indexingRepository;
        this.indexingPersistence = indexingPersistence;
    }

    @Transactional(readOnly = true)
    public RetrievalGeneration inspect(UUID generationId) {
        return generationRepository.findById(generationId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "retrievalGeneration does not exist"
                ));
    }

    /**
     * Lock the generation, snapshot the currently approved active corpus,
     * create missing mappings, then freeze membership and claim the attempt.
     */
    @Transactional
    public GenerationBuildPlan start(UUID generationId) {
        RetrievalGeneration generation = generationRepository
                .findByIdForUpdate(generationId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "retrievalGeneration does not exist"
                ));

        if (generation.getStatus() != RetrievalGenerationStatus.PENDING
                && generation.getStatus() != RetrievalGenerationStatus.FAILED) {
            throw new IllegalStateException(
                    "Only pending or failed generations can start an indexing attempt"
            );
        }
        if (generation.getChunkConfigVersion() == null) {
            throw new IllegalStateException(
                    "generation chunk configuration is required"
            );
        }

        List<SourceChunk> approvedChunks = chunkRepository.findAllApprovedActive();
        if (approvedChunks.isEmpty()) {
            throw new IllegalStateException(
                    "At least one approved chunk in an active document is required"
            );
        }
        approvedChunks.forEach(chunk -> {
            if (!generation.getChunkConfigVersion().equals(chunk.getChunkConfigVersion())) {
                throw new IllegalStateException(
                        "approved chunk configuration does not match the generation"
                );
            }
        });

        String snapshot = corpusSnapshot(approvedChunks);
        if (generation.getCorpusSnapshotSha256() != null
                && !generation.getCorpusSnapshotSha256().equals(snapshot)) {
            throw new IllegalStateException(
                    "approved corpus changed; create a new retrieval generation"
            );
        }

        List<SourceChunkIndexing> existing = indexingRepository
                .findAllForUpdateByGenerationId(generationId);
        Set<UUID> approvedIds = approvedChunks.stream()
                .map(SourceChunk::getId)
                .collect(Collectors.toSet());
        Set<UUID> existingIds = existing.stream()
                .map(indexing -> indexing.getSourceChunk().getId())
                .collect(Collectors.toSet());

        if (generation.getStatus() == RetrievalGenerationStatus.FAILED
                && !existingIds.equals(approvedIds)) {
            throw new IllegalStateException(
                    "failed generation membership no longer matches the approved corpus"
            );
        }

        for (SourceChunk chunk : approvedChunks) {
            indexingPersistence.getOrCreate(chunk, generation);
        }

        if (generation.getCorpusSnapshotSha256() == null) {
            generation.bindCorpusSnapshot(snapshot, now());
        }
        generation.markProcessing(now());
        generationRepository.saveAndFlush(generation);

        List<SourceChunkIndexing> mappings = indexingRepository
                .findAllForUpdateByGenerationId(generationId);
        if (mappings.size() != approvedChunks.size()) {
            throw new IllegalStateException(
                    "generation mappings do not cover the approved corpus"
            );
        }
        for (SourceChunkIndexing mapping : mappings) {
            SourceChunk chunk = mapping.getSourceChunk();
            if (chunk.getReviewStatus() != SourceChunkReviewStatus.APPROVED
                    || chunk.getSourceDocument().getStatus()
                    != SourceDocumentStatus.ACTIVE) {
                throw new IllegalStateException(
                        "chunk approval changed while starting the generation"
                );
            }
            mapping.beginAttempt(now());
        }
        indexingRepository.saveAll(mappings);

        List<KureRuntimeClient.KureRuntimeBuildRequest.Document> documents =
                mappings.stream()
                        .map(mapping -> new KureRuntimeClient.KureRuntimeBuildRequest.Document(
                                mapping.getExternalDocumentId(),
                                mapping.getSourceChunk().getBody()
                        ))
                        .toList();

        KureRuntimeClient.KureRuntimeBuildRequest request =
                new KureRuntimeClient.KureRuntimeBuildRequest(
                        generation.getId(),
                        generation.getGenerationKey(),
                        generation.getModelIdentifier(),
                        generation.getModelRevision(),
                        generation.getTokenizerIdentifier(),
                        generation.getTokenizerRevision(),
                        generation.getChunkConfigVersion(),
                        generation.getCorpusSnapshotSha256(),
                        generation.getEncodingConfigJson(),
                        generation.getIndexConfigJson(),
                        documents
                );
        return new GenerationBuildPlan(
                generation.getId(),
                approvedIds,
                request
        );
    }

    @Transactional
    public RetrievalGeneration complete(
            GenerationBuildPlan plan,
            KureRuntimeClient.KureRuntimeBuildResult result
    ) {
        if (plan == null || result == null
                || !plan.generationId().equals(result.generationId())) {
            throw new IllegalArgumentException(
                    "KURE build result does not match the generation"
            );
        }

        RetrievalGeneration generation = generationRepository
                .findByIdForUpdate(plan.generationId())
                .orElseThrow(() -> new IllegalArgumentException(
                        "retrievalGeneration does not exist"
                ));
        if (generation.getStatus() != RetrievalGenerationStatus.PROCESSING) {
            throw new IllegalStateException(
                    "only a processing generation can complete"
            );
        }

        Set<UUID> readyIds = new HashSet<>(result.readyDocumentIds());
        if (result.readyDocumentIds().size() != plan.expectedDocumentIds().size()
                || readyIds.size() != result.readyDocumentIds().size()
                || !readyIds.equals(plan.expectedDocumentIds())) {
            throw new IllegalStateException(
                    "KURE runtime did not return exactly the expected chunk IDs"
            );
        }

        List<SourceChunkIndexing> mappings = indexingRepository
                .findAllForUpdateByGenerationId(plan.generationId());
        if (mappings.size() != plan.expectedDocumentIds().size()) {
            throw new IllegalStateException("generation membership changed during build");
        }
        String metadata = result.indexMetadataJson() == null
                ? "{}"
                : result.indexMetadataJson();
        for (SourceChunkIndexing mapping : mappings) {
            if (!plan.expectedDocumentIds().contains(mapping.getExternalDocumentId())
                    || mapping.getIndexingStatus() != SourceChunkIndexingStatus.PROCESSING) {
                throw new IllegalStateException(
                        "chunk mapping is not in the expected processing state"
                );
            }
            mapping.markReady(now(), metadata);
        }
        indexingRepository.saveAll(mappings);
        generation.markReady(mappings.size(), now());
        return generationRepository.save(generation);
    }

    @Transactional
    public void failIfProcessing(UUID generationId, String reason) {
        RetrievalGeneration generation = generationRepository
                .findByIdForUpdate(generationId)
                .orElse(null);
        if (generation == null
                || generation.getStatus() != RetrievalGenerationStatus.PROCESSING) {
            return;
        }

        String safeReason = reason == null || reason.isBlank()
                ? "KURE indexing failed"
                : reason;
        List<SourceChunkIndexing> mappings = indexingRepository
                .findAllForUpdateByGenerationId(generationId);
        for (SourceChunkIndexing mapping : mappings) {
            if (mapping.getIndexingStatus() != SourceChunkIndexingStatus.FAILED) {
                mapping.markFailedAfterAttempt(safeReason, now());
            }
        }
        indexingRepository.saveAll(mappings);
        generation.markFailed(safeReason, now());
        generationRepository.save(generation);
    }

    private static String corpusSnapshot(List<SourceChunk> chunks) {
        String material = chunks.stream()
                .sorted(Comparator.comparing(chunk -> chunk.getId().toString()))
                .map(chunk -> String.join("|",
                        chunk.getId().toString(),
                        chunk.getBodySha256(),
                        chunk.getSourceDocument().getId().toString(),
                        chunk.getSourceDocument().getContentSha256()
                ))
                .collect(Collectors.joining("\n"));
        return SourceHashing.sha256(material);
    }

    private static OffsetDateTime now() {
        return OffsetDateTime.now(ZoneOffset.UTC);
    }

    public record GenerationBuildPlan(
            UUID generationId,
            Set<UUID> expectedDocumentIds,
            KureRuntimeClient.KureRuntimeBuildRequest request
    ) {
        public GenerationBuildPlan {
            if (generationId == null || request == null) {
                throw new IllegalArgumentException("generationId and request are required");
            }
            expectedDocumentIds = expectedDocumentIds == null
                    ? Set.of()
                    : Set.copyOf(expectedDocumentIds);
        }
    }
}
