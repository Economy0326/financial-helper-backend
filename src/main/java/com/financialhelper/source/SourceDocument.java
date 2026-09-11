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

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.UUID;

@Entity
@Table(
        name = "source_document",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_source_document_version",
                        columnNames = {
                                "source_registry_id",
                                "document_version"
                        }
                )
        }
)
public class SourceDocument {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(
            fetch = FetchType.LAZY,
            optional = false
    )
    @JoinColumn(
            name = "source_registry_id",
            nullable = false
    )
    private SourceRegistry sourceRegistry;

    @Column(
            name = "document_version",
            nullable = false
    )
    private int documentVersion;

    @Enumerated(EnumType.STRING)
    @Column(
            name = "status",
            nullable = false,
            length = 32
    )
    private SourceDocumentStatus status;

    @Column(
            name = "organization_name",
            nullable = false,
            length = 150
    )
    private String organizationName;

    @Column(
            name = "official_domain",
            nullable = false,
            length = 255
    )
    private String officialDomain;

    // registry에만 두지 않고 Document에도 있는 이유
    // -> registry URL이 바뀌었을 때 과거에 수집한 V1 문서가 어떤 provenance를 갖고있었는지
    @Column(
            name = "canonical_url",
            nullable = false,
            columnDefinition = "TEXT"
    )
    private String canonicalUrl;

    @Column(
            name = "resolved_url",
            nullable = false,
            columnDefinition = "TEXT"
    )
    private String resolvedUrl;

    @Column(
            name = "title",
            nullable = false,
            columnDefinition = "TEXT"
    )
    private String title;

    @Column(name = "published_at")
    private LocalDate publishedAt;

    @Column(
            name = "retrieved_at",
            nullable = false
    )
    private OffsetDateTime retrievedAt;

    @Column(
            name = "last_checked_at",
            nullable = false
    )
    private OffsetDateTime lastCheckedAt;

    @Column(
            name = "content_type",
            length = 255
    )
    private String contentType;

    @Column(
            name = "http_etag",
            columnDefinition = "TEXT"
    )
    private String httpEtag;

    @Column(
            name = "http_last_modified",
            columnDefinition = "TEXT"
    )
    private String httpLastModified;

    @Column(
            name = "raw_sha256",
            nullable = false,
            length = 64
    )
    private String rawSha256;

    @Column(
            name = "content_sha256",
            nullable = false,
            length = 64
    )
    private String contentSha256;

    @Column(
            name = "original_content",
            nullable = false,
            columnDefinition = "BYTEA"
    )
    private byte[] originalContent;

    @Column(
            name = "normalized_content",
            nullable = false,
            columnDefinition = "TEXT"
    )
    private String normalizedContent;

    @Column(name = "superseded_at")
    private OffsetDateTime supersededAt;

    @Column(
            name = "created_at",
            nullable = false
    )
    private OffsetDateTime createdAt;

    protected SourceDocument() {
    }

    public SourceDocument(
            SourceRegistry sourceRegistry,
            int documentVersion,
            SourceIngestionData.Parsed parsed
    ) {
        this.sourceRegistry = sourceRegistry;

        this.documentVersion =
                documentVersion;

        this.status =
                SourceDocumentStatus.ACTIVE;

        this.organizationName =
                sourceRegistry.getOrganizationName();

        this.officialDomain =
                sourceRegistry.getOfficialDomain();

        this.canonicalUrl =
                sourceRegistry.getCanonicalUrl();

        this.resolvedUrl =
                parsed.resolvedUrl();

        this.title =
                parsed.title();

        this.publishedAt =
                parsed.publishedAt();

        this.retrievedAt =
                parsed.retrievedAt();

        this.lastCheckedAt =
                parsed.retrievedAt();

        this.contentType =
                parsed.contentType();

        this.httpEtag =
                parsed.httpEtag();

        this.httpLastModified =
                parsed.httpLastModified();

        this.rawSha256 =
                parsed.rawSha256();

        this.contentSha256 =
                parsed.contentSha256();

        this.originalContent =
                Arrays.copyOf(
                        parsed.originalContent(),
                        parsed.originalContent().length
                );

        this.normalizedContent =
                parsed.normalizedContent();

        this.createdAt =
                parsed.retrievedAt();
    }

    public UUID getId() {
        return id;
    }

    public SourceRegistry getSourceRegistry() {
        return sourceRegistry;
    }

    public int getDocumentVersion() {
        return documentVersion;
    }

    public SourceDocumentStatus getStatus() {
        return status;
    }

    public String getTitle() {
        return title;
    }

    public LocalDate getPublishedAt() {
        return publishedAt;
    }

    public OffsetDateTime getRetrievedAt() {
        return retrievedAt;
    }

    public OffsetDateTime getLastCheckedAt() {
        return lastCheckedAt;
    }

    public String getContentSha256() {
        return contentSha256;
    }

    public String getRawSha256() {
        return rawSha256;
    }

    public String getNormalizedContent() {
        return normalizedContent;
    }

    public byte[] getOriginalContent() {
        return Arrays.copyOf(
                originalContent,
                originalContent.length
        );
    }

    public void markChecked(
            OffsetDateTime checkedAt
    ) {
        if (
                status
                        != SourceDocumentStatus.ACTIVE
        ) {
            throw new IllegalStateException(
                    "Only active source documents can be checked"
            );
        }

        this.lastCheckedAt =
                checkedAt;
    }

    public void supersede(
            OffsetDateTime supersededAt
    ) {
        if (
                status
                        != SourceDocumentStatus.ACTIVE
        ) {
            throw new IllegalStateException(
                    "Only active source documents can be superseded"
            );
        }

        this.status =
                SourceDocumentStatus.SUPERSEDED;

        this.supersededAt =
                supersededAt;
    }
}