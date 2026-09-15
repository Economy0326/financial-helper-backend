package com.financialhelper.guest;

import com.financialhelper.account.Account;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;
import java.util.UUID;

// Flyway => DB Schema 관리
// JPA Entity => DB와 Java 객체 Mapping
@Entity
@Table(name = "guest_session")
public class GuestSession {

    @Id
    // JPA에서 UUID생성, DB default UUID 생성 X
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "token_hash", nullable = false, unique = true)
    private String tokenHash;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "expires_at", nullable = false)
    private OffsetDateTime expiresAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "account_id")
    private Account account;

    protected GuestSession() {
    }

    public GuestSession(
            String tokenHash,
            OffsetDateTime createdAt,
            OffsetDateTime expiresAt
    ) {
        this.tokenHash = tokenHash;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
    }

    public UUID getId() {
        return id;
    }

    public String getTokenHash() {
        return tokenHash;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public OffsetDateTime getExpiresAt() {
        return expiresAt;
    }

    public Account getAccount() {
        return account;
    }

    public void bindAccount(Account account) {
        if (this.account != null && !this.account.getId().equals(account.getId())) {
            throw new IllegalStateException("guest session is already bound to another account");
        }
        this.account = account;
    }
}
