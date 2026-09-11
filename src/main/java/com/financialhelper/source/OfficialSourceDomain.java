package com.financialhelper.source;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;

@Entity
@Table(name = "official_source_domain")
public class OfficialSourceDomain {

    @Id
    @Column(
            name = "domain",
            length = 255
    )
    private String domain;

    @Column(
            name = "organization_name",
            nullable = false,
            length = 150
    )
    private String organizationName;

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

    protected OfficialSourceDomain() {
    }

    public String getDomain() {
        return domain;
    }

    public boolean isEnabled() {
        return enabled;
    }
}