package com.financialhelper.source;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.List;
import java.util.UUID;

public interface SourceChunkIndexingRepository
        extends JpaRepository<SourceChunkIndexing, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query(
            """
            select indexing
            from SourceChunkIndexing indexing
            join fetch indexing.sourceChunk
            join fetch indexing.retrievalGeneration
            where indexing.id = :indexingId
            """
    )
    Optional<SourceChunkIndexing> findForUpdateById(
            @Param("indexingId")
            UUID indexingId
    );

    Optional<SourceChunkIndexing>
    findBySourceChunk_IdAndRetrievalGeneration_Id(
            UUID sourceChunkId,
            UUID retrievalGenerationId
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query(
            """
            select indexing
            from SourceChunkIndexing indexing
            join fetch indexing.sourceChunk chunk
            join fetch chunk.sourceDocument
            join fetch indexing.retrievalGeneration
            where indexing.retrievalGeneration.id = :generationId
            order by chunk.sourceDocument.id, chunk.sequence
            """
    )
    List<SourceChunkIndexing> findAllForUpdateByGenerationId(
            @Param("generationId") UUID generationId
    );

    @Query(
            """
            select indexing
            from SourceChunkIndexing indexing
            join fetch indexing.sourceChunk chunk
            join fetch chunk.sourceDocument document
            join fetch indexing.retrievalGeneration generation
            where generation.id = :generationId
              and indexing.indexingStatus = com.financialhelper.source.SourceChunkIndexingStatus.READY
            order by document.id, chunk.sequence
            """
    )
    List<SourceChunkIndexing> findAllReadyByGenerationId(
            @Param("generationId") UUID generationId
    );

    long countByRetrievalGeneration_IdAndIndexingStatus(
            UUID retrievalGenerationId,
            SourceChunkIndexingStatus indexingStatus
    );

    long countByRetrievalGeneration_Id(
            UUID retrievalGenerationId
    );

    @Query(
            """
            select count(indexing)
            from SourceChunkIndexing indexing
            where indexing.retrievalGeneration.id = :generationId
              and indexing.indexingStatus = com.financialhelper.source.SourceChunkIndexingStatus.READY
              and indexing.sourceChunk.reviewStatus = com.financialhelper.source.SourceChunkReviewStatus.APPROVED
              and indexing.sourceChunk.sourceDocument.status = com.financialhelper.source.SourceDocumentStatus.ACTIVE
            """
    )
    long countReadyApprovedByGenerationId(
            @Param("generationId")
            UUID generationId
    );

    @Modifying(flushAutomatically = true)
    @Query(
            value = """
                    INSERT INTO source_chunk_indexing (
                        id,
                        source_chunk_id,
                        retrieval_generation_id,
                        external_document_id,
                        indexing_status,
                        index_metadata_json,
                        created_at,
                        updated_at,
                        lock_version
                    )
                    VALUES (
                        :id,
                        :sourceChunkId,
                        :generationId,
                        :sourceChunkId,
                        'PENDING',
                        '{}',
                        CURRENT_TIMESTAMP,
                        CURRENT_TIMESTAMP,
                        0
                    )
                    ON CONFLICT (source_chunk_id, retrieval_generation_id)
                    DO NOTHING
                    """,
            nativeQuery = true
    )
    int insertIfAbsent(
            @Param("id")
            UUID id,
            @Param("sourceChunkId")
            UUID sourceChunkId,
            @Param("generationId")
            UUID generationId
    );
}
