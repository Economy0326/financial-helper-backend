package com.financialhelper.account;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "account_ai_usage")
public class AccountAiUsage {
    @Id
    @Column(name = "account_id")
    private UUID accountId;
    @OneToOne
    @JoinColumn(name = "account_id", insertable = false, updatable = false)
    private Account account;
    @Column(name = "window_started_at", nullable = false)
    private OffsetDateTime windowStartedAt;
    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    protected AccountAiUsage() { }
    public AccountAiUsage(UUID accountId, OffsetDateTime windowStartedAt,
                          int attemptCount, OffsetDateTime updatedAt) {
        this.accountId = accountId;
        this.windowStartedAt = windowStartedAt;
        this.attemptCount = attemptCount;
        this.updatedAt = updatedAt;
    }
    public UUID getAccountId() { return accountId; }
    public OffsetDateTime getWindowStartedAt() { return windowStartedAt; }
    public int getAttemptCount() { return attemptCount; }
    public void reset(OffsetDateTime start, OffsetDateTime now) {
        windowStartedAt = start; attemptCount = 1; updatedAt = now;
    }
    public void increment(OffsetDateTime now) { attemptCount++; updatedAt = now; }
}
