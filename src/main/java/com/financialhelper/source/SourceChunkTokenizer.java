package com.financialhelper.source;

/**
 * Counts document tokens using the same tokenizer that the semantic runtime
 * uses for indexing.  Implementations must fail when that runtime is
 * unavailable; character length is not a token-count fallback.
 */
public interface SourceChunkTokenizer {

    int countDocumentTokens(String text);

    default String identifier() {
        return "unknown";
    }

    default String revision() {
        return "unknown";
    }
}
