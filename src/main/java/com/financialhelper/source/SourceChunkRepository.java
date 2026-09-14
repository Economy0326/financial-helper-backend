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

    /**
     * Parameter-safe PostgreSQL full-text lookup for approved chunks in the
     * active document corpus.  The simple configuration is intentionally
     * language-neutral and leaves aliases/synonyms to a later layer.
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
            where chunk.reviewStatus = com.financialhelper.source.SourceChunkReviewStatus.APPROVED
              and document.status = com.financialhelper.source.SourceDocumentStatus.ACTIVE
            order by document.id, chunk.sequence
            """
    )
    List<SourceChunk> findAllApprovedActive();
}
