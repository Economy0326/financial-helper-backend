package com.financialhelper.retrieval;

import com.financialhelper.consultation.FinancialTopic;
import com.financialhelper.source.ActiveRetrievalGenerationPersistenceService;
import com.financialhelper.source.RetrievalGenerationStatus;
import com.financialhelper.source.OfficialInformationClaimSelector;
import org.springframework.stereotype.Service;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;

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
        return hasCurrentCommonCorpus(topic, null);
    }

    /** A reviewer document must not inherit another document's availability. */
    public boolean hasCurrentCommonCorpus(FinancialTopic topic, UUID documentId) {
        var generation = generations.current().orElse(null);
        if (generation == null || generation.getStatus() != RetrievalGenerationStatus.READY) return false;
        var ready = corpus.readyChunks(generation.getId());
        var eligible = reviews.eligibleTopic(topic, LocalDate.now(ZoneId.of("Asia/Seoul")), null, null, ready);
        if (topic == FinancialTopic.DEPOSITS_ACCOUNTS) return ready.stream().anyMatch(chunk ->
                eligible.containsKey(chunk.sourceChunkId())
                        && (documentId == null || documentId.equals(chunk.sourceDocumentId())));
        return ready.stream().anyMatch(chunk -> eligible.containsKey(chunk.sourceChunkId())
                && (documentId == null || documentId.equals(chunk.sourceDocumentId()))
                && OfficialInformationClaimSelector.sourceKeys().stream().anyMatch(key ->
                OfficialInformationClaimSelector.topic(key) == topic
                && OfficialInformationClaimSelector.officialUrl(key).equals(chunk.canonicalUrl())
                && OfficialInformationClaimSelector.label(key).equals(chunk.parentSection())));
    }
}
