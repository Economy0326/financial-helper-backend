package com.financialhelper.source;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

/**
 * Creates the one mapping row that an external index can use for a chunk and
 * retrieval generation.  It does not execute indexing.
 */
@Service
public class SourceChunkIndexingPersistenceService {

    private final SourceChunkIndexingRepository
            sourceChunkIndexingRepository;

    private final RetrievalGenerationRepository
            retrievalGenerationRepository;

    public SourceChunkIndexingPersistenceService(
            SourceChunkIndexingRepository sourceChunkIndexingRepository,
            RetrievalGenerationRepository retrievalGenerationRepository
    ) {
        this.sourceChunkIndexingRepository =
                sourceChunkIndexingRepository;

        this.retrievalGenerationRepository =
                retrievalGenerationRepository;
    }

    @Transactional
    public SourceChunkIndexing getOrCreate(
            SourceChunk sourceChunk,
            RetrievalGeneration retrievalGeneration
    ) {
        if (sourceChunk == null || sourceChunk.getId() == null) {
            throw new IllegalArgumentException(
                    "sourceChunk must be a persisted chunk"
            );
        }

        if (retrievalGeneration == null
                || retrievalGeneration.getId() == null) {
            throw new IllegalArgumentException(
                    "retrievalGeneration must be a persisted generation"
            );
        }

        RetrievalGeneration lockedGeneration =
                retrievalGenerationRepository
                        .findByIdForUpdate(
                                retrievalGeneration.getId()
                        )
                        .orElseThrow(() ->
                                new IllegalArgumentException(
                                        "retrievalGeneration does not exist"
                                )
                        );

        Optional<SourceChunkIndexing> existing =
                sourceChunkIndexingRepository
                        .findBySourceChunk_IdAndRetrievalGeneration_Id(
                                sourceChunk.getId(),
                                retrievalGeneration.getId()
                        );

        if (existing.isPresent()) {
            return existing.get();
        }

        if (lockedGeneration.getStatus()
                != RetrievalGenerationStatus.PENDING) {
            throw new IllegalStateException(
                    "Chunk membership is frozen after generation processing begins"
            );
        }

        sourceChunkIndexingRepository
                .insertIfAbsent(
                        UUID.randomUUID(),
                        sourceChunk.getId(),
                        retrievalGeneration.getId()
                );

        return sourceChunkIndexingRepository
                .findBySourceChunk_IdAndRetrievalGeneration_Id(
                        sourceChunk.getId(),
                        retrievalGeneration.getId()
                )
                .orElseThrow(() ->
                        new IllegalStateException(
                                "Source chunk indexing mapping could not be created"
                        )
                );
    }

    /**
     * Claim an existing mapping for indexing only after its generation has
     * entered PROCESSING.  Both mapping and generation are read from the
     * locked persistence graph.
     */
    @Transactional
    public SourceChunkIndexing markProcessing(
            SourceChunkIndexing indexing
    ) {
        SourceChunkIndexing current = lockPersistedIndexing(indexing);

        if (current.getRetrievalGeneration().getStatus()
                != RetrievalGenerationStatus.PROCESSING) {
            throw new IllegalStateException(
                    "A chunk mapping can process only while its generation is processing"
            );
        }

        current.markProcessing(OffsetDateTime.now(ZoneOffset.UTC));
        return sourceChunkIndexingRepository.save(current);
    }

    @Transactional
    public SourceChunkIndexing markReady(
            SourceChunkIndexing indexing,
            String indexMetadataJson
    ) {
        SourceChunkIndexing current = lockPersistedIndexing(indexing);

        if (current.getRetrievalGeneration().getStatus()
                != RetrievalGenerationStatus.PROCESSING) {
            throw new IllegalStateException(
                    "A chunk mapping can become ready only while its generation is processing"
            );
        }

        current.markReady(
                OffsetDateTime.now(ZoneOffset.UTC),
                indexMetadataJson
        );

        return sourceChunkIndexingRepository.save(current);
    }

    /** Record a failed attempt without changing the immutable chunk mapping. */
    @Transactional
    public SourceChunkIndexing markFailed(
            SourceChunkIndexing indexing,
            String failureReason
    ) {
        SourceChunkIndexing current = lockPersistedIndexing(indexing);
        current.markFailed(
                failureReason,
                OffsetDateTime.now(ZoneOffset.UTC)
        );
        return sourceChunkIndexingRepository.save(current);
    }

    private SourceChunkIndexing lockPersistedIndexing(
            SourceChunkIndexing indexing
    ) {
        if (indexing == null) {
            throw new IllegalArgumentException(
                    "indexing must not be null"
            );
        }

        if (indexing.getId() == null) {
            throw new IllegalArgumentException(
                    "indexing must be a persisted mapping"
            );
        }

        // Never trust a detached status or review association supplied by a
        // caller.  Reload and lock the current mapping before changing it.
        return sourceChunkIndexingRepository
                .findForUpdateById(indexing.getId())
                .orElseThrow(() ->
                        new IllegalArgumentException(
                                "indexing mapping does not exist"
                        )
                );
    }
}
