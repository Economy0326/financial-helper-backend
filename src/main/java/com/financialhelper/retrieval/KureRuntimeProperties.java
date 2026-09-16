package com.financialhelper.retrieval;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "app.retrieval.kure")
public record KureRuntimeProperties(
        boolean enabled,
        String endpoint,
        Duration connectTimeout,
        Duration requestTimeout,
        String modelIdentifier,
        String modelRevision,
        String tokenizerIdentifier,
        String tokenizerRevision,
        int queryLength,
        int maxDocumentTokens,
        int tokenVectorDimension,
        String indexBackend,
        int batchSize
) {

    public KureRuntimeProperties {
        if (endpoint == null || endpoint.isBlank()) {
            throw new IllegalArgumentException("KURE runtime endpoint is required");
        }
        if (connectTimeout == null || connectTimeout.isZero()
                || connectTimeout.isNegative()) {
            throw new IllegalArgumentException(
                    "KURE runtime connectTimeout must be positive"
            );
        }
        if (requestTimeout == null || requestTimeout.isZero()
                || requestTimeout.isNegative()) {
            throw new IllegalArgumentException(
                    "KURE runtime requestTimeout must be positive"
            );
        }
        requireText(modelIdentifier, "modelIdentifier");
        requireText(modelRevision, "modelRevision");
        requireText(tokenizerIdentifier, "tokenizerIdentifier");
        requireText(tokenizerRevision, "tokenizerRevision");
        requirePositive(queryLength, "queryLength");
        requirePositive(maxDocumentTokens, "maxDocumentTokens");
        requirePositive(tokenVectorDimension, "tokenVectorDimension");
        requireText(indexBackend, "indexBackend");
        requirePositive(batchSize, "batchSize");
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
    }

    private static void requirePositive(int value, String name) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }
}
