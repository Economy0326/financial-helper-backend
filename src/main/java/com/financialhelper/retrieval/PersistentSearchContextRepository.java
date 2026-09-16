package com.financialhelper.retrieval;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

public interface PersistentSearchContextRepository extends JpaRepository<PersistentSearchContext, UUID> {
    Optional<PersistentSearchContext> findByIdAndExpiresAtAfter(UUID id, OffsetDateTime now);
    Optional<PersistentSearchContext> findByIdAndConsultation_IdAndExpiresAtAfter(
            UUID id, UUID consultationId, OffsetDateTime now);
}
