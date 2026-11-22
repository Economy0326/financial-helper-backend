package com.financialhelper.retrieval;

import com.financialhelper.source.ActiveRetrievalGenerationPersistenceService;
import com.financialhelper.source.RetrievalGeneration;

import org.springframework.stereotype.Service;

import java.util.UUID;

/** 짧은 DB 단계와 오래 걸릴 수 있는 외부 build를 조정한다. */
@Service
public class RetrievalGenerationIndexingService {

    private final RetrievalGenerationIndexingPersistenceService persistence;
    private final KureRuntimeClient runtime;
    private final KureRuntimeCompatibility compatibility;
    private final ActiveRetrievalGenerationPersistenceService activeGeneration;

    public RetrievalGenerationIndexingService(
            RetrievalGenerationIndexingPersistenceService persistence,
            KureRuntimeClient runtime,
            KureRuntimeCompatibility compatibility,
            ActiveRetrievalGenerationPersistenceService activeGeneration
    ) {
        this.persistence = persistence;
        this.runtime = runtime;
        this.compatibility = compatibility;
        this.activeGeneration = activeGeneration;
    }

    /** 외부에서 build하고 결과를 저장한 뒤 singleton pointer를 전환한다. */
    public void buildAndActivate(UUID generationId) {
        RetrievalGeneration generation = persistence.inspect(generationId);
        if (RetrievalGenerationIndexingPersistenceService.includesDocumentClaims(generation.getMetadataJson())) {
            throw new IllegalStateException("Document claim generations cannot be activated until final rights revalidation is implemented");
        }
        compatibility.requireCompatible(generation, runtime.metadata());

        RetrievalGenerationIndexingPersistenceService.GenerationBuildPlan plan =
                persistence.start(generationId);
        try {
            KureRuntimeClient.KureRuntimeBuildResult result = runtime.build(plan.request());
            KureRuntimeClient.KureRuntimeReadiness readiness =
                    runtime.readiness(generationId);
            if (!generationId.equals(readiness.generationId())
                    || !readiness.ready()
                    || readiness.documentCount() != plan.expectedDocumentIds().size()) {
                throw new KureRuntimeException(
                        "KURE runtime readiness does not cover the requested generation"
                );
            }
            persistence.complete(plan, result);
            activeGeneration.activate(generationId);
        } catch (RuntimeException exception) {
            persistence.failIfProcessing(generationId, exception.getMessage());
            throw exception;
        }
    }

    /** Builds an explicitly claimed candidate but never activates it. */
    public void buildPreparedMixed(UUID generationId) {
        RetrievalGeneration generation = persistence.inspect(generationId);
        if (!RetrievalGenerationIndexingPersistenceService.includesDocumentClaims(generation.getMetadataJson()))
            throw new IllegalStateException("Not a mixed candidate");
        compatibility.requireCompatible(generation, runtime.metadata());
        var plan = persistence.claimPreparedMixed(generationId);
        try {
            var result = runtime.build(plan.request());
            var readiness = runtime.readiness(generationId);
            if (!generationId.equals(readiness.generationId()) || !readiness.ready()
                    || readiness.documentCount() != plan.expectedDocumentIds().size())
                throw new KureRuntimeException("KURE runtime readiness does not cover the requested generation");
            persistence.complete(plan, result);
        } catch (RuntimeException exception) {
            try { persistence.cancelMixedAttempt(generationId); }
            catch (IllegalStateException alreadyCancelled) { exception.addSuppressed(alreadyCancelled); }
            throw exception;
        }
    }
}
