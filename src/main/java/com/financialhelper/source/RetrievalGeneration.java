package com.financialhelper.source;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.UUID;

/**
 * 변경 불가능한 representation/index 정의와 변경 가능한 build lifecycle을 표현한다.
 * There is intentionally no active flag or switch operation in this
 * foundation task.
 */
@Entity
@Table(
        name = "retrieval_generation",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_retrieval_generation_key",
                        columnNames = "generation_key"
                )
        }
)
public class RetrievalGeneration {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(
            name = "generation_key",
            nullable = false,
            length = 128
    )
    private String generationKey;

    @Column(
            name = "representation_config_version",
            nullable = false,
            length = 100
    )
    private String representationConfigVersion;

    @Column(
            name = "model_identifier",
            nullable = false,
            columnDefinition = "TEXT"
    )
    private String modelIdentifier;

    @Column(
            name = "model_revision",
            nullable = false,
            columnDefinition = "TEXT"
    )
    private String modelRevision;

    @Column(
            name = "tokenizer_identifier",
            nullable = false,
            columnDefinition = "TEXT"
    )
    private String tokenizerIdentifier;

    @Column(
            name = "tokenizer_revision",
            nullable = false,
            columnDefinition = "TEXT"
    )
    private String tokenizerRevision;

    @Column(
            name = "encoding_config_json",
            nullable = false,
            columnDefinition = "TEXT"
    )
    private String encodingConfigJson;

    @Column(
            name = "index_config_json",
            nullable = false,
            columnDefinition = "TEXT"
    )
    private String indexConfigJson;

    @Column(
            name = "metadata_json",
            nullable = false,
            columnDefinition = "TEXT"
    )
    private String metadataJson;

    @Column(
            name = "chunk_config_version",
            length = 100
    )
    private String chunkConfigVersion;

    @Column(
            name = "corpus_snapshot_sha256",
            length = 64
    )
    private String corpusSnapshotSha256;

    @Enumerated(EnumType.STRING)
    @Column(
            name = "status",
            nullable = false,
            length = 32
    )
    private RetrievalGenerationStatus status;

    @Column(
            name = "ready_chunk_count",
            nullable = false
    )
    private int readyChunkCount;

    @Column(
            name = "failure_reason",
            columnDefinition = "TEXT"
    )
    private String failureReason;

    @Column(name = "ready_at")
    private OffsetDateTime readyAt;

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

    protected RetrievalGeneration() {
    }

    public RetrievalGeneration(
            RetrievalGenerationData.Definition definition,
            OffsetDateTime createdAt
    ) {
        if (definition == null) {
            throw new IllegalArgumentException(
                    "definition must not be null"
            );
        }

        validateLengths(definition);

        this.generationKey =
                definition.generationKey();

        this.representationConfigVersion =
                definition.representationConfigVersion();

        this.modelIdentifier =
                definition.modelIdentifier();

        this.modelRevision =
                definition.modelRevision();

        this.tokenizerIdentifier =
                definition.tokenizerIdentifier();

        this.tokenizerRevision =
                definition.tokenizerRevision();

        this.encodingConfigJson =
                definition.encodingConfigJson();

        this.indexConfigJson =
                definition.indexConfigJson();

        this.metadataJson =
                definition.metadataJson();

        this.chunkConfigVersion =
                definition.chunkConfigVersion();

        this.corpusSnapshotSha256 =
                definition.corpusSnapshotSha256();

        this.status =
                RetrievalGenerationStatus.PENDING;

        this.readyChunkCount =
                0;

        this.createdAt =
                createdAt == null
                        ? OffsetDateTime.now(ZoneOffset.UTC)
                        : createdAt;

        this.updatedAt =
                this.createdAt;
    }

    private static void validateLengths(
            RetrievalGenerationData.Definition definition
    ) {
        if (definition.generationKey().length() > 128) {
            throw new IllegalArgumentException(
                    "generationKey is too long"
            );
        }

        if (definition.representationConfigVersion().length() > 100) {
            throw new IllegalArgumentException(
                    "representationConfigVersion is too long"
            );
        }
    }

    public UUID getId() {
        return id;
    }

    public String getGenerationKey() {
        return generationKey;
    }

    public String getRepresentationConfigVersion() {
        return representationConfigVersion;
    }

    public String getModelIdentifier() {
        return modelIdentifier;
    }

    public String getModelRevision() {
        return modelRevision;
    }

    public String getTokenizerIdentifier() {
        return tokenizerIdentifier;
    }

    public String getTokenizerRevision() {
        return tokenizerRevision;
    }

    public String getEncodingConfigJson() {
        return encodingConfigJson;
    }

    public String getIndexConfigJson() {
        return indexConfigJson;
    }

    public String getMetadataJson() {
        return metadataJson;
    }

    public String getChunkConfigVersion() {
        return chunkConfigVersion;
    }

    public String getCorpusSnapshotSha256() {
        return corpusSnapshotSha256;
    }

    public RetrievalGenerationStatus getStatus() {
        return status;
    }

    public int getReadyChunkCount() {
        return readyChunkCount;
    }

    public String getFailureReason() {
        return failureReason;
    }

    public OffsetDateTime getReadyAt() {
        return readyAt;
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

    public boolean hasSameDefinition(
            RetrievalGenerationData.Definition definition
    ) {
        return definition != null
                && generationKey.equals(definition.generationKey())
                && representationConfigVersion.equals(
                definition.representationConfigVersion()
        )
                && modelIdentifier.equals(definition.modelIdentifier())
                && modelRevision.equals(definition.modelRevision())
                && tokenizerIdentifier.equals(
                definition.tokenizerIdentifier()
        )
                && tokenizerRevision.equals(
                definition.tokenizerRevision()
        )
                && JsonObjectSupport.deepEquals(
                encodingConfigJson,
                definition.encodingConfigJson()
        )
                && JsonObjectSupport.deepEquals(
                indexConfigJson,
                definition.indexConfigJson()
        )
                && JsonObjectSupport.deepEquals(
                metadataJson,
                definition.metadataJson()
        )
                && Objects.equals(
                chunkConfigVersion,
                definition.chunkConfigVersion()
        )
                && (definition.corpusSnapshotSha256() == null
                || Objects.equals(
                corpusSnapshotSha256,
                definition.corpusSnapshotSha256()
        ));
    }

    /** build 시작 전에 immutable corpus snapshot을 한 번 binding한다. */
    public void bindCorpusSnapshot(
            String corpusSnapshotSha256,
            OffsetDateTime boundAt
    ) {
        String normalized =
                requireSha256(corpusSnapshotSha256);

        if (this.corpusSnapshotSha256 != null
                && !this.corpusSnapshotSha256.equals(normalized)) {
            throw new IllegalStateException(
                    "The retrieval generation corpus snapshot cannot be changed"
            );
        }

        OffsetDateTime timestamp =
                requireTimestamp(boundAt);

        this.corpusSnapshotSha256 = normalized;
        this.updatedAt = timestamp;
    }

    public void markProcessing(
            OffsetDateTime processingAt
    ) {
        if (
                status != RetrievalGenerationStatus.PENDING
                        && status != RetrievalGenerationStatus.FAILED
        ) {
            throw new IllegalStateException(
                    "Only pending or failed generations can start processing"
            );
        }

        OffsetDateTime timestamp =
                requireTimestamp(processingAt);

        status =
                RetrievalGenerationStatus.PROCESSING;

        readyChunkCount =
                0;

        readyAt =
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
     * 비어 있지 않은 indexed corpus 없이는 generation이 READY가 될 수 없다.
     * active-generation 전환은 이후 orchestration에서 처리한다.
     */
    public void markReady(
            long readyChunkCount,
            OffsetDateTime readyAt
    ) {
        if (readyChunkCount <= 0 || readyChunkCount > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(
                    "A ready generation must contain at least one indexed chunk"
            );
        }

        if (status != RetrievalGenerationStatus.PROCESSING) {
            throw new IllegalStateException(
                    "Only a processing generation can become ready"
            );
        }

        OffsetDateTime timestamp =
                requireTimestamp(readyAt);

        this.status =
                RetrievalGenerationStatus.READY;

        this.readyChunkCount =
                (int) readyChunkCount;

        this.failureReason =
                null;

        this.readyAt =
                timestamp;

        this.updatedAt =
                this.readyAt;
    }

    public void markReady(
            long readyChunkCount
    ) {
        markReady(
                readyChunkCount,
                OffsetDateTime.now(ZoneOffset.UTC)
        );
    }

    public void markFailed(
            String failureReason,
            OffsetDateTime failedAt
    ) {
        if (status == RetrievalGenerationStatus.READY) {
            throw new IllegalStateException(
                    "A ready generation cannot be marked failed"
            );
        }

        if (failureReason == null || failureReason.isBlank()) {
            throw new IllegalArgumentException(
                    "failureReason must not be blank"
            );
        }

        OffsetDateTime timestamp =
                requireTimestamp(failedAt);

        status =
                RetrievalGenerationStatus.FAILED;

        this.readyChunkCount =
                0;

        this.readyAt =
                null;

        this.failureReason =
                failureReason.trim();

        updatedAt =
                timestamp;
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
        return Objects.requireNonNull(
                timestamp,
                "timestamp must not be null"
        );
    }

    private static String requireSha256(
            String value
    ) {
        if (value == null || !value.matches("[0-9a-fA-F]{64}")) {
            throw new IllegalArgumentException(
                    "corpusSnapshotSha256 must be a SHA-256 hex value"
            );
        }

        return value.toLowerCase();
    }
}
