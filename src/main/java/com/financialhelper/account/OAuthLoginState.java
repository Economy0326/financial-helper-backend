package com.financialhelper.account;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "oauth_login_state")
public class OAuthLoginState {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private AccountProvider provider;

    @Column(name = "state_hash", nullable = false, unique = true, length = 64)
    private String stateHash;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "expires_at", nullable = false)
    private OffsetDateTime expiresAt;

    protected OAuthLoginState() { }

    public OAuthLoginState(AccountProvider provider, String stateHash,
                           OffsetDateTime createdAt, OffsetDateTime expiresAt) {
        this.provider = provider;
        this.stateHash = stateHash;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
    }

    public AccountProvider getProvider() { return provider; }
    public String getStateHash() { return stateHash; }
    public OffsetDateTime getExpiresAt() { return expiresAt; }
}
