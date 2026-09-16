package com.financialhelper.account;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;

public interface AccountAiUsageRepository extends JpaRepository<AccountAiUsage, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<AccountAiUsage> findByAccountId(UUID accountId);
}
