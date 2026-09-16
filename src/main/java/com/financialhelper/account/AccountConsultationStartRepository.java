package com.financialhelper.account;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

public interface AccountConsultationStartRepository extends JpaRepository<AccountConsultationStart, UUID> {
    @Query("select count(start) from AccountConsultationStart start where start.account.id = :accountId and start.startedAt >= :since")
    long countSince(@Param("accountId") UUID accountId, @Param("since") OffsetDateTime since);

    @Query("select min(start.startedAt) from AccountConsultationStart start where start.account.id = :accountId and start.startedAt >= :since")
    Optional<OffsetDateTime> findOldestSince(@Param("accountId") UUID accountId, @Param("since") OffsetDateTime since);

    boolean existsByConsultation_Id(UUID consultationId);
}
