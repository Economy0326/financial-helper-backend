package com.financialhelper.retrieval;

import com.financialhelper.source.*;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class RetrievalCorpusGenericRightsTest {
    @Test void mixedMembershipWithoutPersistedClaimIsNotTreatedAsLegacy() {
        var repository = mock(SourceChunkIndexingRepository.class);
        var reviews = mock(DocumentClaimReviewerService.class);
        var mapping = mock(SourceChunkIndexing.class);
        var chunk = mock(SourceChunk.class);
        var generation = mock(RetrievalGeneration.class);
        UUID generationId = UUID.randomUUID(), chunkId = UUID.randomUUID();
        when(repository.findAllReadyByGenerationId(generationId)).thenReturn(List.of(mapping));
        when(mapping.getSourceChunk()).thenReturn(chunk);
        when(mapping.getRetrievalGeneration()).thenReturn(generation);
        when(generation.getMetadataJson()).thenReturn("{\"includeDocumentClaims\":true,\"chunkIds\":[\"" + UUID.randomUUID() + "\"]}");
        when(chunk.getId()).thenReturn(chunkId);
        when(chunk.getChunkConfigVersion()).thenReturn("different-extractor-version");
        when(reviews.isDocumentClaimChunk(chunkId)).thenReturn(false);
        assertThat(new RetrievalCorpusSnapshotService(repository, reviews).readyChunks(generationId)).isEmpty();
    }
    @Test void genericChunkIsExcludedWhenRightsAreWithdrawnOrValidatorUnavailable() {
        var repository = mock(SourceChunkIndexingRepository.class);
        var reviews = mock(DocumentClaimReviewerService.class);
        var indexing = mock(SourceChunkIndexing.class);
        var chunk = mock(SourceChunk.class);
        var document = mock(SourceDocument.class);
        var registry = mock(SourceRegistry.class);
        UUID generation = UUID.randomUUID(), chunkId = UUID.randomUUID();
        var generationEntity = mock(RetrievalGeneration.class);
        when(indexing.getRetrievalGeneration()).thenReturn(generationEntity);
        when(generationEntity.getId()).thenReturn(generation);
        when(repository.findAllReadyByGenerationId(generation)).thenReturn(List.of(indexing));
        when(indexing.getSourceChunk()).thenReturn(chunk);
        when(chunk.getId()).thenReturn(chunkId);
        when(chunk.getReviewStatus()).thenReturn(SourceChunkReviewStatus.APPROVED);
        when(chunk.getChunkConfigVersion()).thenReturn("generic-claim-v1");
        when(chunk.getSourceDocument()).thenReturn(document);
        when(document.getStatus()).thenReturn(SourceDocumentStatus.ACTIVE);
        when(document.getSourceRegistry()).thenReturn(registry);
        when(reviews.isDocumentClaimChunk(chunkId)).thenReturn(true);
        when(reviews.genericChunkMayBeDisplayedForGeneration(chunkId, generation)).thenReturn(true, false);
        var service = new RetrievalCorpusSnapshotService(repository, reviews);
        assertThat(service.readyChunks(generation)).hasSize(1);
        assertThat(service.readyChunks(generation)).isEmpty();
        assertThat(new RetrievalCorpusSnapshotService(repository).readyChunks(generation)).isEmpty();
    }
}
