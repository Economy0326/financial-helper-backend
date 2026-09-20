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
 * 하나의 immutable SourceDocument version에 속하는 저장 retrieval unit이다.
 * UUID는 외부 index와 공유하는 안정적인 identity다.
 */
@Entity
@Table(
        name = "source_chunk",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_source_chunk_document_config_sequence",
                        columnNames = {
                                "source_document_id",
                                "chunk_config_version",
                                "sequence"
                        }
                )
        }
)
public class SourceChunk {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(
            fetch = FetchType.LAZY,
            optional = false
    )
    @JoinColumn(
            name = "source_document_id",
            nullable = false
    )
    private SourceDocument sourceDocument;

    @ManyToOne(
            fetch = FetchType.LAZY,
            optional = false
    )
    @JoinColumn(
            name = "chunk_config_version",
            nullable = false
    )
    private SourceChunkConfiguration chunkConfiguration;

    @Column(
            name = "sequence",
            nullable = false
    )
    private int sequence;

    @Column(
            name = "body",
            nullable = false,
            columnDefinition = "TEXT"
    )
    private String body;

    @Column(
            name = "body_sha256",
            nullable = false,
            length = 64
    )
    private String bodySha256;

    @Column(
            name = "parent_section",
            columnDefinition = "TEXT"
    )
    private String parentSection;

    @Column(
            name = "article_reference",
            length = 255
    )
    private String articleReference;

    @Column(
            name = "page_reference",
            length = 100
    )
    private String pageReference;

    @Column(
            name = "locator",
            columnDefinition = "TEXT"
    )
    private String locator;

    // SourceDocument.normalizedContent에 대한 Java UTF-16 offset이다.
    @Column(
            name = "source_start_offset",
            nullable = false
    )
    private int sourceStartOffset;

    @Column(
            name = "source_end_offset",
            nullable = false
    )
    private int sourceEndOffset;

    @Column(
            name = "representation_metadata_json",
            nullable = false,
            columnDefinition = "TEXT"
    )
    private String representationMetadataJson;

    @Enumerated(EnumType.STRING)
    @Column(
            name = "review_status",
            nullable = false,
            length = 32
    )
    private SourceChunkReviewStatus reviewStatus;

    @Column(name = "reviewed_at")
    private OffsetDateTime reviewedAt;

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

    protected SourceChunk() {
    }

    public SourceChunk(
            SourceDocument sourceDocument,
            SourceChunkConfiguration chunkConfiguration,
            SourceChunkData.Definition definition,
            OffsetDateTime createdAt
    ) {
        if (sourceDocument == null) {
            throw new IllegalArgumentException(
                    "sourceDocument must not be null"
            );
        }

        if (chunkConfiguration == null) {
            throw new IllegalArgumentException(
                    "chunkConfiguration must not be null"
            );
        }

        if (definition == null) {
            throw new IllegalArgumentException(
                    "definition must not be null"
            );
        }

        if (definition.sequence() < 0) {
            throw new IllegalArgumentException(
                    "sequence must be non-negative"
            );
        }

        if (definition.body() == null || definition.body().isBlank()) {
            throw new IllegalArgumentException(
                    "body must not be blank"
            );
        }

        this.sourceDocument =
                sourceDocument;

        this.chunkConfiguration =
                chunkConfiguration;

        this.sequence =
                definition.sequence();

        this.body =
                definition.body();

        this.bodySha256 =
                SourceHashing.sha256(
                        this.body
                );

        this.parentSection =
                definition.parentSection();

        this.articleReference =
                definition.articleReference();

        this.pageReference =
                definition.pageReference();

        this.locator =
                definition.locator();

        validateOffsetsAgainstDocument(
                sourceDocument.getNormalizedContent(),
                this.body,
                definition.sourceStartOffset(),
                definition.sourceEndOffset()
        );

        this.sourceStartOffset =
                definition.sourceStartOffset();

        this.sourceEndOffset =
                definition.sourceEndOffset();

        this.representationMetadataJson =
                JsonObjectSupport.requireObject(
                        definition.representationMetadataJson(),
                        "representationMetadataJson"
                );

        this.reviewStatus =
                SourceChunkReviewStatus.PENDING;

        this.reviewedAt =
                null;

        this.createdAt =
                createdAt == null
                        ? OffsetDateTime.now(ZoneOffset.UTC)
                        : createdAt;

        this.updatedAt =
                this.createdAt;
    }

    private static void validateOffsetsAgainstDocument(
            String normalizedContent,
            String body,
            int startOffset,
            int endOffset
    ) {
        if (normalizedContent == null) {
            throw new IllegalArgumentException(
                    "source document normalized content must not be null"
            );
        }

        if (startOffset < 0 || endOffset <= startOffset) {
            throw new IllegalArgumentException(
                    "source offsets must be start-inclusive and end-exclusive"
            );
        }

        if (endOffset > normalizedContent.length()) {
            throw new IllegalArgumentException(
                    "source offsets exceed normalized content"
            );
        }

        // UTF-16 surrogate pair를 나누지 않는다.
        if (
                Character.isLowSurrogate(
                        normalizedContent.charAt(startOffset)
                )
                || (
                        endOffset < normalizedContent.length()
                                && Character.isLowSurrogate(
                                normalizedContent.charAt(endOffset)
                        )
                )
        ) {
            throw new IllegalArgumentException(
                    "source offsets must align to Unicode code points"
            );
        }

        String sourceSlice =
                normalizedContent.substring(
                        startOffset,
                        endOffset
                );

        if (!sourceSlice.equals(body)) {
            throw new IllegalArgumentException(
                    "body must equal the normalized source slice addressed by the offsets"
            );
        }
    }

    public UUID getId() {
        return id;
    }

    public SourceDocument getSourceDocument() {
        return sourceDocument;
    }

    public String getChunkConfigVersion() {
        return chunkConfiguration.getConfigVersion();
    }

    public SourceChunkConfiguration getChunkConfiguration() {
        return chunkConfiguration;
    }

    public int getSequence() {
        return sequence;
    }

    public String getBody() {
        return body;
    }

    public String getBodySha256() {
        return bodySha256;
    }

    public String getParentSection() {
        return parentSection;
    }

    public String getArticleReference() {
        return articleReference;
    }

    public String getPageReference() {
        return pageReference;
    }

    public String getLocator() {
        return locator;
    }

    public int getSourceStartOffset() {
        return sourceStartOffset;
    }

    public int getSourceEndOffset() {
        return sourceEndOffset;
    }

    public String getRepresentationMetadataJson() {
        return representationMetadataJson;
    }

    public SourceChunkReviewStatus getReviewStatus() {
        return reviewStatus;
    }

    public OffsetDateTime getReviewedAt() {
        return reviewedAt;
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

    public boolean isApproved() {
        return reviewStatus == SourceChunkReviewStatus.APPROVED;
    }

    public boolean hasSameDefinition(
            SourceChunkData.Definition definition
    ) {
        return definition != null
                && definition.body() != null
                && !definition.body().isBlank()
                && sequence == definition.sequence()
                && body.equals(definition.body())
                && bodySha256.equals(
                SourceHashing.sha256(
                        definition.body()
                )
        )
                && equalsNullable(
                parentSection,
                definition.parentSection()
        )
                && equalsNullable(
                articleReference,
                definition.articleReference()
        )
                && equalsNullable(
                pageReference,
                definition.pageReference()
        )
                && equalsNullable(
                locator,
                definition.locator()
        )
                && sourceStartOffset
                == definition.sourceStartOffset()
                && sourceEndOffset
                == definition.sourceEndOffset()
                && JsonObjectSupport.deepEquals(
                representationMetadataJson,
                definition.representationMetadataJson()
        );
    }

    public void approve(
            OffsetDateTime reviewedAt
    ) {
        transitionReview(
                SourceChunkReviewStatus.APPROVED,
                reviewedAt
        );
    }

    public void approve() {
        approve(OffsetDateTime.now(ZoneOffset.UTC));
    }

    public void reject(
            OffsetDateTime reviewedAt
    ) {
        transitionReview(
                SourceChunkReviewStatus.REJECTED,
                reviewedAt
        );
    }

    public void reject() {
        reject(OffsetDateTime.now(ZoneOffset.UTC));
    }

    /**
     * 승인을 취소하면 이후 Evidence query에서 chunk를 사용할 수 없다. 기존 index
     * row는 의도적으로 과거 기록으로 남으며 retrieval query는 READY를 승인으로
     * 취급하지 않고 이 review status를 확인해야 한다.
     */
    public void revokeApproval(
            OffsetDateTime revokedAt
    ) {
        if (reviewStatus != SourceChunkReviewStatus.APPROVED) {
            throw new IllegalStateException(
                    "Only an approved source chunk can have approval revoked"
            );
        }

        OffsetDateTime timestamp =
                requireTimestamp(revokedAt);

        reviewStatus =
                SourceChunkReviewStatus.PENDING;

        reviewedAt =
                null;

        updatedAt =
                timestamp;
    }

    public void revokeApproval() {
        revokeApproval(OffsetDateTime.now(ZoneOffset.UTC));
    }

    private void transitionReview(
            SourceChunkReviewStatus nextStatus,
            OffsetDateTime nextReviewedAt
    ) {
        OffsetDateTime timestamp =
                requireTimestamp(nextReviewedAt);

        reviewStatus =
                nextStatus;

        reviewedAt =
                timestamp;

        updatedAt =
                timestamp;
    }

    private static OffsetDateTime requireTimestamp(
            OffsetDateTime timestamp
    ) {
        if (timestamp == null) {
            throw new IllegalArgumentException(
                    "review timestamp must not be null"
            );
        }

        return timestamp;
    }

    private static boolean equalsNullable(
            String left,
            String right
    ) {
        return left == null
                ? right == null
                : left.equals(right);
    }
}
