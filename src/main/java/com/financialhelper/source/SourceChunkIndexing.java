package com.financialhelper.source;

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
import jakarta.persistence.Version;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * Mapping between one SourceChunk and one external retrieval generation.
 * READY here means the external index artifact accepted the chunk; it does
 * not replace the SourceChunk human review status.
 */
@Entity
@Table(
        name = "source_chunk_indexing",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_source_chunk_indexing_chunk_generation",
                        columnNames = {
                                "source_chunk_id",
                                "retrieval_generation_id"
                        }
                ),
                @UniqueConstraint(
                        name = "uk_source_chunk_indexing_generation_external_id",
                        columnNames = {
                                "retrieval_generation_id",
                                "external_document_id"
                        }
                )
        }
)
public class SourceChunkIndexing {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(
            fetch = FetchType.LAZY,
            optional = false
    )
    @JoinColumn(
            name = "source_chunk_id",
            nullable = false
    )
    private SourceChunk sourceChunk;

    @ManyToOne(
            fetch = FetchType.LAZY,
            optional = false
    )
    @JoinColumn(
            name = "retrieval_generation_id",
            nullable = false
    )
    private RetrievalGeneration retrievalGeneration;

    @Column(
            name = "external_document_id",
            nullable = false
    )
    private UUID externalDocumentId;

    @Enumerated(EnumType.STRING)
    @Column(
            name = "indexing_status",
            nullable = false,
            length = 32
    )
    private SourceChunkIndexingStatus indexingStatus;

    @Column(
            name = "index_metadata_json",
            nullable = false,
            columnDefinition = "TEXT"
    )
    private String indexMetadataJson;

    @Column(
            name = "failure_reason",
            columnDefinition = "TEXT"
    )
    private String failureReason;

    @Column(name = "processed_at")
    private OffsetDateTime processedAt;

    @Column(
            name = "created_at",
            nullable = false
    )
    private OffsetDateTime createdAt;

    @Column(
            name = "updated_at",
            nullable = false
    )
    private OffsetDateTime updatedAt;

    @Version
    @Column(
            name = "lock_version",
            nullable = false
    )
    private long lockVersion;

    protected SourceChunkIndexing() {
    }

    public SourceChunkIndexing(
            SourceChunk sourceChunk,
            RetrievalGeneration retrievalGeneration,
            OffsetDateTime createdAt
    ) {
        if (sourceChunk == null || sourceChunk.getId() == null) {
            throw new IllegalArgumentException(
                    "sourceChunk must be a persisted chunk"
            );
        }

        if (retrievalGeneration == null
                || retrievalGeneration.getId() == null) {
            throw new IllegalArgumentException(
                    "retrievalGeneration must be a persisted generation"
            );
        }

        this.sourceChunk =
                sourceChunk;

        this.retrievalGeneration =
                retrievalGeneration;

        this.externalDocumentId =
                sourceChunk.getId();

        this.indexingStatus =
                SourceChunkIndexingStatus.PENDING;

        this.indexMetadataJson =
                "{}";

        this.createdAt =
                createdAt == null
                        ? OffsetDateTime.now(ZoneOffset.UTC)
                        : createdAt;

        this.updatedAt =
                this.createdAt;
    }

    public UUID getId() {
        return id;
    }

    public SourceChunk getSourceChunk() {
        return sourceChunk;
    }

    public RetrievalGeneration getRetrievalGeneration() {
        return retrievalGeneration;
    }

    public UUID getExternalDocumentId() {
        return externalDocumentId;
    }

    public SourceChunkIndexingStatus getIndexingStatus() {
        return indexingStatus;
    }

    public String getIndexMetadataJson() {
        return indexMetadataJson;
    }

    public String getFailureReason() {
        return failureReason;
    }

    public OffsetDateTime getProcessedAt() {
        return processedAt;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }

    public long getLockVersion() {
        return lockVersion;
    }

    public void markProcessing(
            OffsetDateTime processingAt
    ) {
        if (
                indexingStatus != SourceChunkIndexingStatus.PENDING
                        && indexingStatus != SourceChunkIndexingStatus.FAILED
        ) {
            throw new IllegalStateException(
                    "Only pending or failed chunk mappings can start processing"
            );
        }

        OffsetDateTime timestamp =
                requireTimestamp(processingAt);

        indexingStatus =
                SourceChunkIndexingStatus.PROCESSING;

        processedAt =
                null;

        failureReason =
                null;

        updatedAt =
                timestamp;
    }

    public void markProcessing() {
        markProcessing(OffsetDateTime.now(ZoneOffset.UTC));
    }

    /**
     * Mapping readiness is gated by human review.  A READY index row remains
     * historical if approval is later revoked; query code must check the
     * current SourceChunk review status as well.
     */
    public void markReady(
            OffsetDateTime readyAt,
            String indexMetadataJson
    ) {
        if (!sourceChunk.isApproved()) {
            throw new IllegalStateException(
                    "A source chunk must be approved before its index mapping can be ready"
            );
        }

        if (indexingStatus != SourceChunkIndexingStatus.PROCESSING) {
            throw new IllegalStateException(
                    "Only a processing chunk mapping can become ready"
            );
        }

        OffsetDateTime timestamp =
                requireTimestamp(readyAt);

        this.indexMetadataJson =
                JsonObjectSupport.requireObject(
                        indexMetadataJson,
                        "indexMetadataJson"
                );

        this.indexingStatus =
                SourceChunkIndexingStatus.READY;

        this.failureReason =
                null;

        this.processedAt =
                timestamp;

        this.updatedAt =
                this.processedAt;
    }

    public void markReady(
            OffsetDateTime readyAt
    ) {
        markReady(
                readyAt,
                indexMetadataJson
        );
    }

    public void markReady() {
        markReady(
                OffsetDateTime.now(ZoneOffset.UTC)
        );
    }

    public void markFailed(
            String failureReason,
            OffsetDateTime failedAt
    ) {
        if (
                indexingStatus != SourceChunkIndexingStatus.PENDING
                        && indexingStatus != SourceChunkIndexingStatus.PROCESSING
        ) {
            throw new IllegalStateException(
                    "Only pending or processing chunk mappings can fail"
            );
        }

        if (failureReason == null || failureReason.isBlank()) {
            throw new IllegalArgumentException(
                    "failureReason must not be blank"
            );
        }

        OffsetDateTime timestamp =
                requireTimestamp(failedAt);

        indexingStatus =
                SourceChunkIndexingStatus.FAILED;

        this.failureReason =
                failureReason.trim();

        this.processedAt =
                timestamp;

        this.updatedAt =
                this.processedAt;
    }

    public void markFailed(
            String failureReason
    ) {
        markFailed(
                failureReason,
                OffsetDateTime.now(ZoneOffset.UTC)
        );
    }

    private static OffsetDateTime requireTimestamp(
            OffsetDateTime timestamp
    ) {
        if (timestamp == null) {
            throw new IllegalArgumentException(
                    "timestamp must not be null"
            );
        }

        return timestamp;
    }
}
