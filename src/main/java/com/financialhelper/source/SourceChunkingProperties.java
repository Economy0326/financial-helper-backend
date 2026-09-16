package com.financialhelper.source;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.retrieval.chunking")
public record SourceChunkingProperties(
        int targetTokens,
        int maxTokens,
        int overlapTokens,
        String configVersion,
        String tokenizerIdentifier,
        String tokenizerRevision
) {

    public SourceChunkingProperties {
        if (targetTokens <= 0) {
            throw new IllegalArgumentException(
                    "chunking targetTokens must be positive"
            );
        }

        if (maxTokens < targetTokens) {
            throw new IllegalArgumentException(
                    "chunking maxTokens must be at least targetTokens"
            );
        }

        if (overlapTokens != 0) {
            throw new IllegalArgumentException(
                    "chunking overlapTokens must be zero until tokenizer-boundary overlap is implemented"
            );
        }

        if (configVersion == null || configVersion.isBlank()) {
            throw new IllegalArgumentException(
                    "chunking configVersion is required"
            );
        }

        if (tokenizerIdentifier == null || tokenizerIdentifier.isBlank()) {
            throw new IllegalArgumentException(
                    "chunking tokenizerIdentifier is required"
            );
        }

        if (tokenizerRevision == null || tokenizerRevision.isBlank()) {
            throw new IllegalArgumentException(
                    "chunking tokenizerRevision is required"
            );
        }
    }
}
