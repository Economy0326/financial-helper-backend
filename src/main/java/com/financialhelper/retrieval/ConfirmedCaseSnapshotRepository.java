package com.financialhelper.retrieval;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

public interface ConfirmedCaseSnapshotRepository
        extends JpaRepository<ConfirmedCaseSnapshot, UUID> {

    Optional<ConfirmedCaseSnapshot>
    findByConsultation_IdAndCaseInputRevisionAndFollowUpAnswerRevision(
            UUID consultationId,
            long caseInputRevision,
            long followUpAnswerRevision
    );

    @Query("""
            select snapshot
            from ConfirmedCaseSnapshot snapshot
            where snapshot.consultation.id = :consultationId
            order by snapshot.caseInputRevision desc,
                     snapshot.followUpAnswerRevision desc
            """)
    Optional<ConfirmedCaseSnapshot> findLatest(
            @Param("consultationId") UUID consultationId
    );

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Transactional
    @Query("delete from ConfirmedCaseSnapshot snapshot where snapshot.consultation.id = :consultationId")
    void deleteByConsultation_Id(@Param("consultationId") UUID consultationId);
}
