package com.financialhelper.source;

import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

import java.util.Optional;
import java.util.UUID;

public interface RetrievalGenerationRepository
        extends JpaRepository<RetrievalGeneration, UUID> {

    Optional<RetrievalGeneration>
    findByGenerationKey(String generationKey);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query(
            """
            select generation
            from RetrievalGeneration generation
            where generation.id = :generationId
            """
    )
    Optional<RetrievalGeneration> findByIdForUpdate(
            @Param("generationId")
            UUID generationId
    );

    /**
     * database unique key에서 먼저 쓴 요청이 이기도록 생성을 직렬화한다.
     * 이후 persistence service가 변경 불가능한 정의를 비교하므로,
     * 설정을 바꾼 채 key를 재사용하면 거부된다.
     */
    @Modifying(flushAutomatically = true)
    @Query(
            value = """
                    INSERT INTO retrieval_generation (
                        id,
                        generation_key,
                        representation_config_version,
                        model_identifier,
                        model_revision,
                        tokenizer_identifier,
                        tokenizer_revision,
                        encoding_config_json,
                        index_config_json,
                        metadata_json,
                        chunk_config_version,
                        corpus_snapshot_sha256,
                        status,
                        ready_chunk_count,
                        created_at,
                        updated_at,
                        lock_version
                    )
                    VALUES (
                        :id,
                        :generationKey,
                        :representationConfigVersion,
                        :modelIdentifier,
                        :modelRevision,
                        :tokenizerIdentifier,
                        :tokenizerRevision,
                        :encodingConfigJson,
                        :indexConfigJson,
                        :metadataJson,
                        :chunkConfigVersion,
                        :corpusSnapshotSha256,
                        'PENDING',
                        0,
                        CURRENT_TIMESTAMP,
                        CURRENT_TIMESTAMP,
                        0
                    )
                    ON CONFLICT (generation_key) DO NOTHING
                    """,
            nativeQuery = true
    )
    int insertIfAbsent(
            @Param("id")
            UUID id,
            @Param("generationKey")
            String generationKey,
            @Param("representationConfigVersion")
            String representationConfigVersion,
            @Param("modelIdentifier")
            String modelIdentifier,
            @Param("modelRevision")
            String modelRevision,
            @Param("tokenizerIdentifier")
            String tokenizerIdentifier,
            @Param("tokenizerRevision")
            String tokenizerRevision,
            @Param("encodingConfigJson")
            String encodingConfigJson,
            @Param("indexConfigJson")
            String indexConfigJson,
            @Param("metadataJson")
            String metadataJson,
            @Param("chunkConfigVersion")
            String chunkConfigVersion,
            @Param("corpusSnapshotSha256")
            String corpusSnapshotSha256
    );
}
