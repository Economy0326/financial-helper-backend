package com.financialhelper.account;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

public interface AccountSessionRepository extends JpaRepository<AccountSession, UUID> {
    Optional<AccountSession> findByTokenHashAndExpiresAtAfter(String tokenHash, OffsetDateTime now);
    void deleteByTokenHash(String tokenHash);
}
