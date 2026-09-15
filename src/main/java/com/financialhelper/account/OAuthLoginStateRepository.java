package com.financialhelper.account;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

public interface OAuthLoginStateRepository extends JpaRepository<OAuthLoginState, UUID> {
    Optional<OAuthLoginState> findByStateHashAndExpiresAtAfter(String stateHash, OffsetDateTime now);
    void deleteByStateHash(String stateHash);
}
