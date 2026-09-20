package com.financialhelper.source;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/** 일반 retrieval이 사용하는 generation의 singleton pointer다. */
@Entity
@Table(name = "active_retrieval_generation")
public class ActiveRetrievalGeneration {

    @Id
    @Column(name = "singleton_key", nullable = false)
    private boolean singletonKey;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "retrieval_generation_id", nullable = false)
    private RetrievalGeneration retrievalGeneration;

    @Column(name = "switched_at", nullable = false)
    private OffsetDateTime switchedAt;

    protected ActiveRetrievalGeneration() {
    }

    public ActiveRetrievalGeneration(
            RetrievalGeneration retrievalGeneration,
            OffsetDateTime switchedAt
    ) {
        if (retrievalGeneration == null || retrievalGeneration.getId() == null) {
            throw new IllegalArgumentException(
                    "retrievalGeneration must be persisted"
            );
        }

        this.singletonKey = true;
        this.retrievalGeneration = retrievalGeneration;
        this.switchedAt = switchedAt == null
                ? OffsetDateTime.now(ZoneOffset.UTC)
                : switchedAt;
    }

    public RetrievalGeneration getRetrievalGeneration() {
        return retrievalGeneration;
    }

    public OffsetDateTime getSwitchedAt() {
        return switchedAt;
    }

    public void switchTo(
            RetrievalGeneration retrievalGeneration,
            OffsetDateTime switchedAt
    ) {
        if (retrievalGeneration == null || retrievalGeneration.getId() == null) {
            throw new IllegalArgumentException(
                    "retrievalGeneration must be persisted"
            );
        }

        if (switchedAt == null) {
            throw new IllegalArgumentException(
                    "switchedAt must not be null"
            );
        }

        this.retrievalGeneration = retrievalGeneration;
        this.switchedAt = switchedAt;
    }
}
