package com.financialhelper.source;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

/**
 * representation/index generation을 위한 멱등 persistence 경계다.
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
     * key에 해당하는 generation을 반환하거나 한 번만 생성한다. generation key는
     * 변경 불가능한 identity이므로 model/tokenizer/encoding/index metadata가
     * 달라지면 update가 아니라 충돌로 처리한다.
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
     * 현재 저장된 lifecycle 상태를 기준으로 generation build를 시작한다.
     * 분리된 caller 객체는 Source of Truth로 사용하지 않는다.
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
     * 이후 재시도를 위해 동일한 generation 정의를 유지하면서 build 실패를 기록한다.
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
     * 모든 member mapping이 READY이고 현재 SourceChunk review가 APPROVED인 경우에만
     * build generation을 ready로 표시한다. 이는 persistence guard이며,
     * active generation 전환은 이후 orchestration 작업으로 남긴다.
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
