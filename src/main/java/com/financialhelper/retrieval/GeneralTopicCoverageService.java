package com.financialhelper.retrieval;

import com.financialhelper.consultation.FinancialTopic;
import com.financialhelper.source.ActiveRetrievalGenerationPersistenceService;
import com.financialhelper.source.RetrievalGenerationStatus;
import org.springframework.stereotype.Service;
import java.time.LocalDate;
import java.time.ZoneId;

/** Presence of reviewed READY corpus, not a claim that a particular question is answerable. */
@Service
public class GeneralTopicCoverageService {
    private final ActiveRetrievalGenerationPersistenceService generations;
    private final RetrievalCorpusSnapshotService corpus;
    private final ReviewedSourceApplicabilityService reviews;

    public GeneralTopicCoverageService(ActiveRetrievalGenerationPersistenceService generations,
            RetrievalCorpusSnapshotService corpus, ReviewedSourceApplicabilityService reviews) {
        this.generations = generations;
        this.corpus = corpus;
        this.reviews = reviews;
    }

    public boolean hasCurrentCommonCorpus(FinancialTopic topic) {
        if (topic != FinancialTopic.DEPOSITS_ACCOUNTS) return false;
        var generation = generations.current().orElse(null);
        return generation != null && generation.getStatus() == RetrievalGenerationStatus.READY
                && !reviews.eligibleTopic(topic, LocalDate.now(ZoneId.of("Asia/Seoul")), null, null,
                        corpus.readyChunks(generation.getId())).isEmpty();
    }
}
