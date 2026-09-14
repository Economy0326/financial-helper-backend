package com.financialhelper.source;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

/**
 * Idempotent persistence boundary for representation/index generations.
 */
@Service
public class RetrievalGenerationPersistenceService {

    private final RetrievalGenerationRepository
            retrievalGenerationRepository;

    private final SourceChunkIndexingRepository
            sourceChunkIndexingRepository;

    public RetrievalGenerationPersistenceService(
            RetrievalGenerationRepository retrievalGenerationRepository,
            SourceChunkIndexingRepository sourceChunkIndexingRepository
    ) {
        this.retrievalGenerationRepository =
                retrievalGenerationRepository;

        this.sourceChunkIndexingRepository =
                sourceChunkIndexingRepository;
    }

    /**
     * Return the generation for a key or create it once.  A generation key is
     * an immutable identity; changed model/tokenizer/encoding/index metadata
     * is a conflict rather than an update.
     */
    @Transactional
    public RetrievalGeneration getOrCreate(
            RetrievalGenerationData.Definition definition
    ) {
        if (definition == null) {
            throw new IllegalArgumentException(
                    "definition must not be null"
            );
        }

        Optional<RetrievalGeneration> existing =
                retrievalGenerationRepository
                        .findByGenerationKey(
                                definition.generationKey()
                        );

        if (existing.isEmpty()) {
            retrievalGenerationRepository
                    .insertIfAbsent(
                            UUID.randomUUID(),
                            definition.generationKey(),
                            definition.representationConfigVersion(),
                            definition.modelIdentifier(),
                            definition.modelRevision(),
                            definition.tokenizerIdentifier(),
                            definition.tokenizerRevision(),
                            definition.encodingConfigJson(),
                            definition.indexConfigJson(),
                            definition.metadataJson(),
                            definition.chunkConfigVersion(),
                            definition.corpusSnapshotSha256()
                    );

            existing =
                    retrievalGenerationRepository
                            .findByGenerationKey(
                                    definition.generationKey()
                            );
        }

        RetrievalGeneration generation =
                existing.orElseThrow(() ->
                        new IllegalStateException(
                                "Retrieval generation could not be created"
                        )
                );

        if (!generation.hasSameDefinition(definition)) {
            throw new RetrievalGenerationDefinitionConflictException();
        }

        return generation;
    }

    @Transactional
    public RetrievalGeneration bindCorpusSnapshot(
            RetrievalGeneration retrievalGeneration,
            String corpusSnapshotSha256
    ) {
        RetrievalGeneration current =
                lockPersistedGeneration(retrievalGeneration);
        current.bindCorpusSnapshot(
                corpusSnapshotSha256,
                OffsetDateTime.now(ZoneOffset.UTC)
        );
        return retrievalGenerationRepository.save(current);
    }

    /**
     * Start a generation build against the current persisted lifecycle state.
     * A detached caller object is never used as the source of truth.
     */
    @Transactional
    public RetrievalGeneration markProcessing(
            RetrievalGeneration retrievalGeneration
    ) {
        RetrievalGeneration current = lockPersistedGeneration(retrievalGeneration);
        current.markProcessing(OffsetDateTime.now(ZoneOffset.UTC));
        return retrievalGenerationRepository.save(current);
    }

    /**
     * Record a generation build failure while preserving the same generation
     * definition for a later retry.
     */
    @Transactional
    public RetrievalGeneration markFailed(
            RetrievalGeneration retrievalGeneration,
            String failureReason
    ) {
        RetrievalGeneration current = lockPersistedGeneration(retrievalGeneration);
        current.markFailed(
                failureReason,
                OffsetDateTime.now(ZoneOffset.UTC)
        );
        return retrievalGenerationRepository.save(current);
    }

    /**
     * Mark a build generation ready only after every member mapping is READY
     * and its current SourceChunk review is APPROVED.  This is a persistence
     * guard; active-generation switching remains a later orchestration task.
     */
    @Transactional
    public RetrievalGeneration markReady(
            RetrievalGeneration retrievalGeneration
    ) {
        if (retrievalGeneration == null
                || retrievalGeneration.getId() == null) {
            throw new IllegalArgumentException(
                    "retrievalGeneration must be a persisted generation"
            );
        }

        RetrievalGeneration lockedGeneration =
                lockPersistedGeneration(retrievalGeneration);

        long totalMappings =
                sourceChunkIndexingRepository
                        .countByRetrievalGeneration_Id(
                                lockedGeneration.getId()
                        );

        long readyApprovedMappings =
                sourceChunkIndexingRepository
                        .countReadyApprovedByGenerationId(
                                lockedGeneration.getId()
                        );

        if (totalMappings <= 0
                || totalMappings != readyApprovedMappings) {
            throw new IllegalStateException(
                    "A retrieval generation requires at least one READY and currently approved mapping for every registered member"
            );
        }

        lockedGeneration.markReady(
                totalMappings,
                OffsetDateTime.now(ZoneOffset.UTC)
        );

        return retrievalGenerationRepository.save(
                lockedGeneration
        );
    }

    private RetrievalGeneration lockPersistedGeneration(
            RetrievalGeneration retrievalGeneration
    ) {
        if (retrievalGeneration == null
                || retrievalGeneration.getId() == null) {
            throw new IllegalArgumentException(
                    "retrievalGeneration must be a persisted generation"
            );
        }

        return retrievalGenerationRepository
                .findByIdForUpdate(
                        retrievalGeneration.getId()
                )
                .orElseThrow(() ->
                        new IllegalArgumentException(
                                "retrievalGeneration does not exist"
                        )
                );
    }
}
