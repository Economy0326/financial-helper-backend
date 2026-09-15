package com.financialhelper.law;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/** Runtime configuration for the pinned external korean-law-mcp server. */
@ConfigurationProperties(prefix = "app.law-mcp")
public record KoreanLawMcpProperties(
        boolean enabled,
        String endpoint,
        Duration connectTimeout,
        Duration requestTimeout,
        String lawOc,
        String expectedServerVersion,
        String pinnedRepository,
        String pinnedTag,
        String pinnedCommit
) {

    public KoreanLawMcpProperties {
        requireText(endpoint, "endpoint");
        requirePositive(connectTimeout, "connectTimeout");
        requirePositive(requestTimeout, "requestTimeout");
        requireText(expectedServerVersion, "expectedServerVersion");
        requireText(pinnedRepository, "pinnedRepository");
        requireText(pinnedTag, "pinnedTag");
        requireText(pinnedCommit, "pinnedCommit");
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
    }

    private static void requirePositive(Duration value, String name) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }
}
