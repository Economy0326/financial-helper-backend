package com.financialhelper.emergency;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface EmergencySelectionRepository
        extends JpaRepository<EmergencySelection, UUID> {

    // 1 Guest : 1 Emergency selection
    Optional<EmergencySelection> findByGuestSession_Id(
            UUID guestSessionId
    );
}