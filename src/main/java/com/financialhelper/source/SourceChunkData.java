package com.financialhelper.source;

/**
 * 하나의 retrieval unit을 위한 persistence 입력이다. 실제 chunking과 tokenizer
 * 작업은 이후 task에서 처리하며 이 record는 준비된 값만 persistence 경계로 전달한다.
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
