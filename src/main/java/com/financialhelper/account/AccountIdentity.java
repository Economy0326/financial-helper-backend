package com.financialhelper.account;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.OffsetDateTime;
import java.util.UUID;

/** Provider identity is separate from account ownership so providers can grow without email-based merging. */
@Entity
@Table(name = "account_identity", uniqueConstraints = @UniqueConstraint(
        name = "uk_account_identity_provider_subject",
        columnNames = {"provider", "provider_subject"}
))
public class AccountIdentity {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "account_id", nullable = false)
    private Account account;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private AccountProvider provider;

    @Column(name = "provider_subject", nullable = false, length = 255)
    private String providerSubject;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "last_seen_at", nullable = false)
    private OffsetDateTime lastSeenAt;

    protected AccountIdentity() { }

    public AccountIdentity(Account account, AccountProvider provider,
                           String providerSubject, OffsetDateTime now) {
        if (account == null || provider == null || providerSubject == null || providerSubject.isBlank()) {
            throw new IllegalArgumentException("provider identity is required");
        }
        this.account = account;
        this.provider = provider;
        this.providerSubject = providerSubject;
        this.createdAt = now;
        this.lastSeenAt = now;
    }

    public UUID getId() { return id; }
    public Account getAccount() { return account; }
    public AccountProvider getProvider() { return provider; }
    public String getProviderSubject() { return providerSubject; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getLastSeenAt() { return lastSeenAt; }

    public void touch(OffsetDateTime now) { this.lastSeenAt = now; }
}
