package com.financialhelper.source;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

/** 단일 row의 atomic active-generation 전환을 수행한다. */
@Service
public class ActiveRetrievalGenerationPersistenceService {

    private final RetrievalGenerationRepository retrievalGenerationRepository;
    private final ActiveRetrievalGenerationRepository activeRepository;

    public ActiveRetrievalGenerationPersistenceService(
            RetrievalGenerationRepository retrievalGenerationRepository,
            ActiveRetrievalGenerationRepository activeRepository
    ) {
        this.retrievalGenerationRepository = retrievalGenerationRepository;
        this.activeRepository = activeRepository;
    }

    @Transactional
    public ActiveRetrievalGeneration activate(UUID generationId) {
        if (generationId == null) {
            throw new IllegalArgumentException("generationId must not be null");
        }

        RetrievalGeneration generation = retrievalGenerationRepository
                .findByIdForUpdate(generationId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "retrievalGeneration does not exist"
                ));

        if (generation.getStatus() != RetrievalGenerationStatus.READY) {
            throw new IllegalStateException(
                    "Only a READY retrieval generation can become active"
            );
        }

        OffsetDateTime switchedAt = OffsetDateTime.now(ZoneOffset.UTC);
        activeRepository.upsertSingleton(generationId, switchedAt);
        return activeRepository.findSingleton()
                .orElseThrow(() -> new IllegalStateException(
                        "active retrieval generation could not be persisted"
                ));
    }

    @Transactional(readOnly = true)
    public Optional<RetrievalGeneration> current() {
        return activeRepository.findSingleton()
                .map(ActiveRetrievalGeneration::getRetrievalGeneration);
    }
}
