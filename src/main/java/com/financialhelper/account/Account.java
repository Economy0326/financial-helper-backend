package com.financialhelper.account;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "account", uniqueConstraints = @UniqueConstraint(
        name = "uk_account_provider_subject",
        columnNames = {"provider", "provider_subject"}
))
public class Account {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private AccountProvider provider;

    @Column(name = "provider_subject", nullable = false, length = 255)
    private String providerSubject;

    @Column(name = "display_name", length = 200)
    private String displayName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private AccountStatus status;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    protected Account() { }

    public Account(AccountProvider provider, String providerSubject,
                   String displayName, OffsetDateTime now) {
        if (provider == null || providerSubject == null || providerSubject.isBlank()) {
            throw new IllegalArgumentException("provider identity is required");
        }
        this.provider = provider;
        this.providerSubject = providerSubject;
        this.displayName = displayName;
        this.status = AccountStatus.ACTIVE;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public UUID getId() { return id; }
    public AccountProvider getProvider() { return provider; }
    public String getProviderSubject() { return providerSubject; }
    public String getDisplayName() { return displayName; }
    public AccountStatus getStatus() { return status; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }

    public void refreshDisplayName(String value, OffsetDateTime now) {
        if (value != null && !value.isBlank()) {
            this.displayName = value.substring(0, Math.min(value.length(), 200));
        }
        this.updatedAt = now;
    }
}
