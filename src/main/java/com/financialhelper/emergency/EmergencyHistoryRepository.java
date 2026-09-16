package com.financialhelper.emergency;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface EmergencyHistoryRepository extends JpaRepository<EmergencyHistory, UUID> {
    Page<EmergencyHistory> findByAccount_IdOrderByViewedAtDesc(UUID accountId, Pageable pageable);
}
