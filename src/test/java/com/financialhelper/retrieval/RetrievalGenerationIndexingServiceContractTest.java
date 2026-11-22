package com.financialhelper.retrieval;

import com.financialhelper.source.ActiveRetrievalGenerationPersistenceService;
import com.financialhelper.source.RetrievalGeneration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Synthetic contracts only: no Spring context, DB, network, KURE model or index files. */
class RetrievalGenerationIndexingServiceContractTest {
    private final RetrievalGenerationIndexingPersistenceService persistence = mock(RetrievalGenerationIndexingPersistenceService.class);
    private final KureRuntimeClient runtime = mock(KureRuntimeClient.class);
    private final KureRuntimeCompatibility compatibility = mock(KureRuntimeCompatibility.class);
    private final ActiveRetrievalGenerationPersistenceService active = mock(ActiveRetrievalGenerationPersistenceService.class);
    private final RetrievalGeneration generation = mock(RetrievalGeneration.class);
    private final UUID generationId = UUID.randomUUID();
    private final UUID chunkId = UUID.randomUUID();
    private final KureRuntimeClient.KureRuntimeBuildRequest request = new KureRuntimeClient.KureRuntimeBuildRequest(
            generationId, "synthetic", "model", "revision", "tokenizer", "revision", "chunk-config",
            "a".repeat(64), "{}", "{}", List.of(new KureRuntimeClient.KureRuntimeBuildRequest.Document(chunkId, "synthetic")));
    private final RetrievalGenerationIndexingPersistenceService.GenerationBuildPlan plan =
            new RetrievalGenerationIndexingPersistenceService.GenerationBuildPlan(generationId, Set.of(chunkId), request);
    private final KureRuntimeClient.KureRuntimeBuildResult result =
            new KureRuntimeClient.KureRuntimeBuildResult(generationId, List.of(chunkId), "{}");
    private final RetrievalGenerationIndexingService service = new RetrievalGenerationIndexingService(
            persistence, runtime, compatibility, active);

    @BeforeEach void arrange() {
        when(persistence.inspect(generationId)).thenReturn(generation);
        lenient().when(persistence.start(generationId)).thenReturn(plan);
        when(runtime.metadata()).thenReturn(mock(KureRuntimeClient.KureRuntimeMetadata.class));
    }

    @Test void successfulBuildActivatesOnlyAfterReadinessAndPersistenceComplete() {
        when(runtime.build(request)).thenReturn(result);
        when(runtime.readiness(generationId)).thenReturn(new KureRuntimeClient.KureRuntimeReadiness(generationId, true, 1, "{}"));
        service.buildAndActivate(generationId);
        InOrder order = inOrder(persistence, runtime, compatibility, active);
        order.verify(persistence).inspect(generationId);
        order.verify(runtime).metadata();
        order.verify(compatibility).requireCompatible(eq(generation), any());
        order.verify(persistence).start(generationId);
        order.verify(runtime).build(request);
        order.verify(runtime).readiness(generationId);
        order.verify(persistence).complete(plan, result);
        order.verify(active).activate(generationId);
        verify(persistence, never()).failIfProcessing(any(), any());
    }

    @Test void failedBuildDoesNotReplaceExistingActiveGeneration() {
        when(runtime.build(request)).thenThrow(new KureRuntimeException("synthetic build failure"));
        assertThatThrownBy(() -> service.buildAndActivate(generationId)).isInstanceOf(KureRuntimeException.class);
        verify(persistence).failIfProcessing(eq(generationId), anyString());
        verify(persistence, never()).complete(any(), any());
        verifyNoInteractions(active);
    }

    @Test void wrongGenerationCannotBeActivated() {
        when(runtime.build(request)).thenReturn(result);
        when(runtime.readiness(generationId)).thenReturn(
                new KureRuntimeClient.KureRuntimeReadiness(UUID.randomUUID(), true, 1, "{}"));
        assertThatThrownBy(() -> service.buildAndActivate(generationId)).isInstanceOf(KureRuntimeException.class);
        verify(persistence).failIfProcessing(eq(generationId), anyString());
        verify(persistence, never()).complete(any(), any());
        verifyNoInteractions(active);
    }

    @Test void incompleteIndexCannotBeActivated() {
        when(runtime.build(request)).thenReturn(result);
        when(runtime.readiness(generationId)).thenReturn(
                new KureRuntimeClient.KureRuntimeReadiness(generationId, true, 0, "{}"));
        assertThatThrownBy(() -> service.buildAndActivate(generationId)).isInstanceOf(KureRuntimeException.class);
        verify(persistence).failIfProcessing(eq(generationId), anyString());
        verify(persistence, never()).complete(any(), any());
        verifyNoInteractions(active);
    }

    @Test void failedPersistenceDoesNotActivateAnIndex() {
        when(runtime.build(request)).thenReturn(result);
        when(runtime.readiness(generationId)).thenReturn(new KureRuntimeClient.KureRuntimeReadiness(generationId, true, 1, "{}"));
        doThrow(new IllegalStateException("synthetic source changed")).when(persistence).complete(plan, result);
        assertThatThrownBy(() -> service.buildAndActivate(generationId)).isInstanceOf(IllegalStateException.class);
        verify(persistence).failIfProcessing(eq(generationId), anyString());
        verifyNoInteractions(active);
    }

    @Test void incompatibleRuntimeStopsBeforeIndexingAttemptOrPointerChange() {
        doThrow(new KureRuntimeException("synthetic incompatible runtime"))
                .when(compatibility).requireCompatible(eq(generation), any());
        assertThatThrownBy(() -> service.buildAndActivate(generationId)).isInstanceOf(KureRuntimeException.class);
        verify(persistence, never()).start(any());
        verify(runtime, never()).build(any());
        verifyNoInteractions(active);
    }
}
