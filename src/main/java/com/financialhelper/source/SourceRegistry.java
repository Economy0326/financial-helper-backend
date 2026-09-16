package com.financialhelper.source;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "source_registry")
public class SourceRegistry {

    @Id
    private UUID id;

    @Column(
            name = "source_key",
            nullable = false,
            unique = true,
            length = 64
    )
    private String sourceKey;

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

    @Column(
            name = "canonical_url",
            nullable = false,
            unique = true,
            columnDefinition = "TEXT"
    )
    private String canonicalUrl;

    @Enumerated(EnumType.STRING)
    @Column(
            name = "acquisition_type",
            nullable = false,
            length = 32
    )
    private SourceAcquisitionType acquisitionType;

    @Column(
            name = "enabled",
            nullable = false
    )
    private boolean enabled;

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

    // source마다 실제 본문 영역을 직접 지정할 수 있음
    // null이면 HtmlSourceDocumentParser의 기본 selector 전략을 사용
    @Column(
            name = "content_selector",
            length = 500
    )
    private String contentSelector;

    protected SourceRegistry() {
    }

    public UUID getId() {
        return id;
    }

    public String getSourceKey() {
        return sourceKey;
    }

    public String getOrganizationName() {
        return organizationName;
    }

    public String getOfficialDomain() {
        return officialDomain;
    }

    public String getCanonicalUrl() {
        return canonicalUrl;
    }

    public SourceAcquisitionType getAcquisitionType() {
        return acquisitionType;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public String getContentSelector() {
        return contentSelector;
    }

}