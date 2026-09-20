package com.financialhelper.source;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

/**
 * 외부 index가 chunk와 retrieval generation에 사용할 mapping row 하나를 만든다.
 * indexing 자체는 실행하지 않는다.
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
     * generation이 PROCESSING에 진입한 뒤에만 기존 mapping을 indexing 대상으로 점유한다.
     * mapping과 generation 모두 lock된 persistence graph에서 읽는다.
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

    /** immutable chunk mapping을 변경하지 않고 실패한 attempt를 기록한다. */
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

        // caller가 전달한 분리된 status나 review association을 신뢰하지 않는다.
        // 변경 전에 현재 mapping을 다시 읽고 lock한다.
        return sourceChunkIndexingRepository
                .findForUpdateById(indexing.getId())
                .orElseThrow(() ->
                        new IllegalArgumentException(
                                "indexing mapping does not exist"
                        )
                );
    }
}
