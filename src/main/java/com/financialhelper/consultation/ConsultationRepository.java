package com.financialhelper.consultation;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

public interface ConsultationRepository
        extends JpaRepository<Consultation, UUID> {

    // 해당 상담이 해당 Guest 소유인가?
    Optional<Consultation> findByIdAndGuestSession_Id(
            UUID id,
            UUID guestSessionId
    );

    // 해당 Guest에게 진행 중인 상담이 이미 있나?
    boolean existsByGuestSession_IdAndStatusIn(
            UUID guestSessionId,
            Collection<ConsultationStatus> statuses
    );

    // 현재 Guest의 Active Consultation을 가져옴
    Optional<Consultation>
    findFirstByGuestSession_IdAndStatusInOrderByUpdatedAtDesc(
            UUID guestSessionId,
            Collection<ConsultationStatus> statuses
    );

    Optional<Consultation> findFirstByAccount_IdAndStatusInOrderByUpdatedAtDesc(
            UUID accountId, Collection<ConsultationStatus> statuses);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select consultation from Consultation consultation
            where consultation.account.id = :accountId
              and consultation.status in :statuses
            order by consultation.updatedAt desc
            """)
    Optional<Consultation> findActiveForUpdateByAccountId(
            @Param("accountId") UUID accountId,
            @Param("statuses") Collection<ConsultationStatus> statuses);

    // PESSIMISTIC_WRITE를 걸어 같은 ROW에 대한 동시 수정/중복 상태 전이를 막음 
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query(
            """
            select consultation
            from Consultation consultation
            where consultation.id = :consultationId
            and consultation.guestSession.id = :guestSessionId
            """
    )
    Optional<Consultation>
    findForUpdateByIdAndGuestSession_Id(
            @Param("consultationId")
            UUID consultationId,

            @Param("guestSessionId")
            UUID guestSessionId
    );

    @Query("""
            select consultation from Consultation consultation
            where consultation.account.id = :accountId
              and exists (select report.id from ConsultationReport report where report.consultation.id = consultation.id)
            order by consultation.updatedAt desc
            """)
    Page<Consultation> findHistoryByAccountId(
            @Param("accountId") UUID accountId, Pageable pageable);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query(
            """
            select consultation
            from Consultation consultation
            where consultation.id = :consultationId
            """
    )
    Optional<Consultation> findForUpdateById(
            @Param("consultationId")
            UUID consultationId
    );
}
