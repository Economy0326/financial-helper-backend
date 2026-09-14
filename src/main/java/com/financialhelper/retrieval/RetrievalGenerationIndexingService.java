package com.financialhelper.retrieval;

import com.financialhelper.source.ActiveRetrievalGenerationPersistenceService;
import com.financialhelper.source.RetrievalGeneration;

import org.springframework.stereotype.Service;

import java.util.UUID;

/** Coordinates short DB phases with the potentially long external build. */
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

    /** Build externally, persist the result, then switch the singleton pointer. */
    public void buildAndActivate(UUID generationId) {
        RetrievalGeneration generation = persistence.inspect(generationId);
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
}
