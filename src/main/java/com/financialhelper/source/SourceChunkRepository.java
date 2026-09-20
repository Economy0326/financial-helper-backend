package com.financialhelper.source;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SourceChunkRepository
        extends JpaRepository<SourceChunk, UUID> {

    Optional<SourceChunk>
    findBySourceDocument_IdAndChunkConfiguration_ConfigVersionAndSequence(
            UUID sourceDocumentId,
            String configVersion,
            int sequence
    );

    Optional<SourceChunk>
    findFirstByChunkConfiguration_ConfigVersion(
            String configVersion
    );

    List<SourceChunk>
    findAllBySourceDocument_IdOrderBySequenceAsc(
            UUID sourceDocumentId
    );

    @Query("select chunk from SourceChunk chunk join fetch chunk.sourceDocument where chunk.id = :id")
    Optional<SourceChunk> findWithDocumentById(@Param("id") UUID id);

    /**
     * approved chunk를 대상으로 parameter-safe PostgreSQL full-text 조회를 수행한다.
     * active document corpus만 대상으로 한다. simple configuration은 의도적으로
     * language-neutral하게 유지하고 alias/synonym 처리는 이후 layer에 맡긴다.
     */
    @Query(
            value = """
                    SELECT chunk.*
                    FROM source_chunk chunk
                    JOIN source_document document
                      ON document.id = chunk.source_document_id
                    WHERE chunk.review_status = 'APPROVED'
                      AND document.status = 'ACTIVE'
                      AND chunk.keyword_search_vector @@
                          websearch_to_tsquery('simple', CAST(:query AS TEXT))
                    ORDER BY ts_rank_cd(
                        chunk.keyword_search_vector,
                        websearch_to_tsquery('simple', CAST(:query AS TEXT))
                    ) DESC,
                    chunk.id
                    LIMIT :limit
                    """,
            nativeQuery = true
    )
    List<SourceChunk> searchApprovedKeyword(
            @Param("query")
            String query,
            @Param("limit")
            int limit
    );

    @Query(
            """
            select chunk
            from SourceChunk chunk
            join fetch chunk.sourceDocument document
            join fetch document.sourceRegistry
            where chunk.reviewStatus = com.financialhelper.source.SourceChunkReviewStatus.APPROVED
              and document.status = com.financialhelper.source.SourceDocumentStatus.ACTIVE
            order by document.id, chunk.sequence
            """
    )
    List<SourceChunk> findAllApprovedActive();
}
