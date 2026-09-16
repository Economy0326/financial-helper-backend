package com.financialhelper.source;

/**
 * Persistence input for one retrieval unit.  Actual chunking and tokenizer
 * work belongs to a later task; this record only carries already prepared
 * values to the persistence boundary.
 */
public final class SourceChunkData {

    private SourceChunkData() {
    }

    public record Definition(
            String chunkConfigVersion,
            String chunkConfigJson,
            int sequence,
            String body,
            String parentSection,
            String articleReference,
            String pageReference,
            String locator,
            int sourceStartOffset,
            int sourceEndOffset,
            String representationMetadataJson
    ) {

        public Definition {
            if (representationMetadataJson == null) {
                representationMetadataJson = "{}";
            }
        }
    }
}
