package com.financialhelper.law;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.time.Duration;

/** 공식 Korean Law Open API의 server-side 설정이다. */
@ConfigurationProperties(prefix = "app.law-open-api")
public record KoreanLawOpenApiProperties(
        boolean enabled,
        String baseUrl,
        Duration connectTimeout,
        Duration requestTimeout,
        String lawOc,
        String providerVersion,
        String providerRevision
) {

    public KoreanLawOpenApiProperties {
        requireHttpUrl(baseUrl, "baseUrl");
        requirePositive(connectTimeout, "connectTimeout");
        requirePositive(requestTimeout, "requestTimeout");
        requireText(providerVersion, "providerVersion");
        requireText(providerRevision, "providerRevision");
    }

    private static void requireHttpUrl(String value, String name) {
        requireText(value, name);
        URI uri = URI.create(value);
        if (!"http".equalsIgnoreCase(uri.getScheme())
                && !"https".equalsIgnoreCase(uri.getScheme())) {
            throw new IllegalArgumentException(name + " must use HTTP(S)");
        }
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
