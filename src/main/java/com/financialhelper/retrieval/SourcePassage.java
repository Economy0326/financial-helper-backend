package com.financialhelper.retrieval;

import java.util.List;
import java.util.UUID;

public record SourcePassage(
        UUID passageId,
        UUID candidateId,
        UUID sourceDocumentId,
        int documentVersion,
        List<UUID> sourceChunkIds,
        String text,
        String articleReference,
        String pageReference,
        String locator,
        String canonicalUrl
) {
    public SourcePassage {
        sourceChunkIds = sourceChunkIds == null ? List.of() : List.copyOf(sourceChunkIds);
    }
}
