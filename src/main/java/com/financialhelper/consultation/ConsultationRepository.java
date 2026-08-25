package com.financialhelper.consultation;

import org.springframework.data.jpa.repository.JpaRepository;

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
}