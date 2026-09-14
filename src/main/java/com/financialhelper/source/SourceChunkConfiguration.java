package com.financialhelper.source;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * Immutable chunking definition shared by all chunks that use one version.
 */
@Entity
@Table(name = "source_chunk_configuration")
public class SourceChunkConfiguration {

    @Id
    @Column(
            name = "config_version",
            nullable = false,
            length = 100
    )
    private String configVersion;

    @Column(
            name = "config_json",
            nullable = false,
            columnDefinition = "TEXT"
    )
    private String configJson;

    @Column(
            name = "config_sha256",
            nullable = false,
            length = 64
    )
    private String configSha256;

    @Column(
            name = "created_at",
            nullable = false
    )
    private OffsetDateTime createdAt;

    protected SourceChunkConfiguration() {
    }

    public SourceChunkConfiguration(
            String configVersion,
            String configJson,
            OffsetDateTime createdAt
    ) {
        if (configVersion == null || configVersion.isBlank()) {
            throw new IllegalArgumentException(
                    "configVersion must not be blank"
            );
        }

        if (configVersion.length() > 100) {
            throw new IllegalArgumentException(
                    "configVersion is too long"
            );
        }

        this.configVersion =
                configVersion.trim();

        this.configJson =
                JsonObjectSupport.requireObject(
                        configJson,
                        "configJson"
                );

        this.configSha256 =
                SourceHashing.sha256(
                        this.configJson
                );

        this.createdAt =
                createdAt == null
                        ? OffsetDateTime.now(ZoneOffset.UTC)
                        : createdAt;
    }

    public String getConfigVersion() {
        return configVersion;
    }

    public String getConfigJson() {
        return configJson;
    }

    public String getConfigSha256() {
        return configSha256;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public boolean hasSameDefinition(
            String expectedConfigJson
    ) {
        return JsonObjectSupport.deepEquals(
                configJson,
                expectedConfigJson
        );
    }
}
