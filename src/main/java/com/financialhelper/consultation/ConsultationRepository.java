package com.financialhelper.consultation;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface ConsultationRepository
        extends JpaRepository<Consultation, UUID> {

    // Guest는 자신이 소유한 Consultation만 조회할 수 있음
    Optional<Consultation> findByIdAndGuestSession_Id(
            UUID id,
            UUID guestSessionId
    );
}