package com.financialhelper.source;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SourceFoundationStateInvariantTest {

    private static final OffsetDateTime NOW =
            OffsetDateTime.parse("2026-09-14T00:00:00Z");

    @Test
    void invalidReviewTimestampDoesNotChangeApproval() {
        SourceChunk chunk = chunk("약관", 0, 2);
        assertThatThrownBy(() -> chunk.approve(null)).isInstanceOf(RuntimeException.class);
        assertThat(chunk.getReviewStatus()).isEqualTo(SourceChunkReviewStatus.PENDING);
        assertThat(chunk.getReviewedAt()).isNull();

        chunk.approve(NOW);
        assertThatThrownBy(() -> chunk.reject(null)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> chunk.revokeApproval(null)).isInstanceOf(RuntimeException.class);
        assertThat(chunk.getReviewStatus()).isEqualTo(SourceChunkReviewStatus.APPROVED);
        assertThat(chunk.getReviewedAt()).isEqualTo(NOW);
    }

    @Test
    void invalidGenerationTransitionDoesNotPartiallyMutateState() {
        RetrievalGeneration generation = generation();
        assertThatThrownBy(() -> generation.markProcessing(null)).isInstanceOf(RuntimeException.class);
        assertThat(generation.getStatus()).isEqualTo(RetrievalGenerationStatus.PENDING);
        generation.markProcessing(NOW);

        assertThatThrownBy(() -> generation.markReady(1, null)).isInstanceOf(RuntimeException.class);
        assertThat(generation.getStatus()).isEqualTo(RetrievalGenerationStatus.PROCESSING);
        assertThat(generation.getReadyChunkCount()).isZero();
        assertThat(generation.getReadyAt()).isNull();
        assertThatThrownBy(() -> generation.markFailed("test failure", null)).isInstanceOf(RuntimeException.class);
        assertThat(generation.getStatus()).isEqualTo(RetrievalGenerationStatus.PROCESSING);
        assertThat(generation.getFailureReason()).isNull();
    }

    @Test
    void invalidIndexCompletionDoesNotReplaceMetadataOrState() {
        SourceChunk chunk = chunk("약관", 0, 2);
        chunk.approve(NOW);
        RetrievalGeneration generation = generation();
        ReflectionTestUtils.setField(chunk, "id", UUID.randomUUID());
        ReflectionTestUtils.setField(generation, "id", UUID.randomUUID());
        SourceChunkIndexing indexing = new SourceChunkIndexing(chunk, generation, NOW);

        assertThatThrownBy(() -> indexing.markProcessing(null)).isInstanceOf(RuntimeException.class);
        assertThat(indexing.getIndexingStatus()).isEqualTo(SourceChunkIndexingStatus.PENDING);
        generation.markProcessing(NOW);
        indexing.markProcessing(NOW);
        assertThatThrownBy(() -> indexing.markReady(null, "{\"artifact\":\"changed\"}"))
                .isInstanceOf(RuntimeException.class);
        assertThat(indexing.getIndexingStatus()).isEqualTo(SourceChunkIndexingStatus.PROCESSING);
        assertThat(indexing.getIndexMetadataJson()).isEqualTo("{}");
        assertThat(indexing.getProcessedAt()).isNull();
        assertThatThrownBy(() -> indexing.markFailed("test failure", null)).isInstanceOf(RuntimeException.class);
        assertThat(indexing.getIndexingStatus()).isEqualTo(SourceChunkIndexingStatus.PROCESSING);
        assertThat(indexing.getFailureReason()).isNull();
    }

    @Test
    void offsetsUseUtf16AndNeverSplitSurrogatePairs() {
        String normalized = "앞😀뒤";
        SourceChunk valid = chunk(normalized, 1, 3);
        assertThat(valid.getBody()).isEqualTo("😀");
        assertThat(valid.getSourceEndOffset() - valid.getSourceStartOffset()).isEqualTo(2);
        assertThatThrownBy(() -> chunk(normalized, 1, 2)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> chunk(normalized, 2, 3)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void insertedTextCannotMasqueradeAsOriginalSourceSlice() {
        SourceDocument document = mock(SourceDocument.class);
        when(document.getNormalizedContent()).thenReturn("원문");
        SourceChunkData.Definition definition = new SourceChunkData.Definition(
                "test-v1", "{}", 0, "삽입된 제목 원문", null, null, null, null, 0, 2, "{}");
        assertThatThrownBy(() -> new SourceChunk(document,
                new SourceChunkConfiguration("test-v1", "{}", NOW), definition, NOW))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private SourceChunk chunk(String normalized, int start, int end) {
        SourceDocument document = mock(SourceDocument.class);
        when(document.getNormalizedContent()).thenReturn(normalized);
        return new SourceChunk(document, new SourceChunkConfiguration("test-v1", "{}", NOW),
                new SourceChunkData.Definition("test-v1", "{}", 0,
                        normalized.substring(start, end), null, null, null, null, start, end, "{}"), NOW);
    }

    private RetrievalGeneration generation() {
        return new RetrievalGeneration(new RetrievalGenerationData.Definition(
                "test-generation", "test-v1", "model", "revision", "tokenizer", "revision",
                "{}", "{}", "{}"), NOW);
    }
}
