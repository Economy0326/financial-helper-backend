package com.financialhelper.source;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface ActiveRetrievalGenerationRepository
        extends JpaRepository<ActiveRetrievalGeneration, Boolean> {

    @Modifying(flushAutomatically = true)
    @Query(
            value = """
                    INSERT INTO active_retrieval_generation (
                        singleton_key,
                        retrieval_generation_id,
                        switched_at
                    ) VALUES (TRUE, :generationId, :switchedAt)
                    ON CONFLICT (singleton_key)
                    DO UPDATE SET
                        retrieval_generation_id = EXCLUDED.retrieval_generation_id,
                        switched_at = EXCLUDED.switched_at
                    """,
            nativeQuery = true
    )
    int upsertSingleton(
            @Param("generationId") java.util.UUID generationId,
            @Param("switchedAt") java.time.OffsetDateTime switchedAt
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query(
            """
            select active
            from ActiveRetrievalGeneration active
            join fetch active.retrievalGeneration
            where active.singletonKey = true
            """
    )
    Optional<ActiveRetrievalGeneration> findSingletonForUpdate();

    @Query(
            """
            select active
            from ActiveRetrievalGeneration active
            join fetch active.retrievalGeneration
            where active.singletonKey = true
            """
    )
    Optional<ActiveRetrievalGeneration> findSingleton();
}
