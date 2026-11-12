package com.financialhelper.retrieval;

import com.financialhelper.consultation.Consultation;
import com.financialhelper.consultation.ConsultationInputPrivacy;
import com.financialhelper.consultation.ConsultationNotFoundException;
import com.financialhelper.consultation.ConsultationRepository;
import com.financialhelper.consultation.FinancialTopic;
import com.financialhelper.guest.GuestSessionService;
import com.financialhelper.source.ActiveRetrievalGenerationPersistenceService;
import com.financialhelper.source.RetrievalGeneration;
import com.financialhelper.source.RetrievalGenerationStatus;
import com.financialhelper.source.VerifiedPdfMetadata;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Read-only discovery for confirmed general-information topics. Never prepares a Report. */
@Service
public class GeneralTopicInformationService {
    private final GuestSessionService sessions;
    private final ConsultationRepository consultations;
    private final ActiveRetrievalGenerationPersistenceService generations;
    private final RetrievalCorpusSnapshotService corpus;
    private final ReviewedSourceApplicabilityService reviews;
    private final OfficialSourceRetrievalService retrieval;

    public GeneralTopicInformationService(GuestSessionService sessions, ConsultationRepository consultations,
            ActiveRetrievalGenerationPersistenceService generations, RetrievalCorpusSnapshotService corpus,
            ReviewedSourceApplicabilityService reviews, OfficialSourceRetrievalService retrieval) {
        this.sessions = sessions;
        this.consultations = consultations;
        this.generations = generations;
        this.corpus = corpus;
        this.reviews = reviews;
        this.retrieval = retrieval;
    }

    public FinancialTopic confirmedTopic(UUID id, String token) {
        var session = sessions.requireValidSession(token);
        return owned(id, session.getId()).getConfirmedInformationTopic();
    }

    public GeneralTopicInformationResponse search(UUID id, String token, GeneralTopicInformationRequest request) {
        var session = sessions.requireValidSession(token);
        Consultation consultation = owned(id, session.getId());
        if (consultation.getCaseInputRevision() != request.expectedCaseInputRevision())
            return result(id, consultation, null, "STALE", List.of("CASE_INPUT_REVISION_CHANGED"), List.of());
        FinancialTopic topic = consultation.getConfirmedInformationTopic();
        if (!consultation.isInformationIntake() || topic == null || consultation.getScenario() != null)
            return result(id, consultation, null, "NEEDS_TOPIC_CONFIRMATION", List.of(), List.of());
        if (topic != FinancialTopic.DEPOSITS_ACCOUNTS)
            return result(id, consultation, null, "UNSUPPORTED_FINANCIAL_TOPIC", List.of(), List.of());

        // Older stored text is checked again before any external retrieval boundary.
        ConsultationInputPrivacy.requireSafe(consultation.getSituationText());
        String query = DepositQueryPurpose.query(consultation.getSituationText());
        if (query == null)
            return result(id, consultation, null, "NEEDS_CLARIFICATION", List.of("QUESTION_SCOPE"), List.of());
        boolean historical = request.timeScope() == GeneralTopicInformationRequest.TimeScope.HISTORICAL;
        if (historical != (request.asOfDate() != null))
            return result(id, consultation, null, "NEEDS_CLARIFICATION", List.of("REFERENCE_DATE"), List.of());
        if (!historical && DepositQueryPurpose.hasHistoricalMarker(consultation.getSituationText()))
            return result(id, consultation, null, "NEEDS_CLARIFICATION", List.of("REFERENCE_DATE"), List.of());
        LocalDate asOf = historical ? request.asOfDate() : LocalDate.now(ZoneId.of("Asia/Seoul"));
        RetrievalGeneration before = generations.current().orElse(null);
        if (before == null || before.getStatus() != RetrievalGenerationStatus.READY)
            return result(id, consultation, asOf, "INSUFFICIENT_EVIDENCE",
                    List.of("NO_ACTIVE_READY_GENERATION"), List.of());

        // The only KURE payload is a fixed purpose phrase, never the stored question.
        OfficialSearchRequest scoped = new OfficialSearchRequest(query, topic.name(), null, null,
                null, List.of(), 10, null);
        Map<UUID, ReviewedSourceApplicabilityService.Match> topicEligible = reviews.eligibleTopic(
                topic, asOf, null, null, corpus.readyChunks(before.getId()));
        var scopedReady = corpus.readyChunks(before.getId()).stream()
                .filter(chunk -> VerifiedPdfMetadata.DEPOSIT_PDF_URL.equals(chunk.canonicalUrl()))
                .filter(chunk -> DepositQueryPurpose.questionNumbers(consultation.getSituationText())
                        .stream().anyMatch(question -> chunk.parentSection() != null
                                && chunk.parentSection().startsWith(question + ". ")))
                .map(RetrievalCorpusSnapshotService.EligibleChunk::sourceChunkId)
                .collect(Collectors.toSet());
        Map<UUID, ReviewedSourceApplicabilityService.Match> eligible = topicEligible.entrySet().stream()
                .filter(entry -> scopedReady.contains(entry.getKey()))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        if (eligible.isEmpty())
            return result(id, consultation, asOf, "INSUFFICIENT_EVIDENCE",
                    List.of("NO_APPROVED_TOPIC_EVIDENCE"), List.of());
        OfficialSearchResponse found = retrieval.searchReviewedCandidates(scoped, eligible.keySet());
        Consultation current = owned(id, session.getId());
        RetrievalGeneration after = generations.current().orElse(null);
        if (current.getCaseInputRevision() != consultation.getCaseInputRevision()
                || current.getFollowUpAnswerRevision() != consultation.getFollowUpAnswerRevision()
                || current.getConfirmedInformationTopic() != topic || after == null
                || after.getStatus() != RetrievalGenerationStatus.READY
                || !before.getId().equals(after.getId()))
            return result(id, current, asOf, "STALE", List.of("SOURCE_STATE_CHANGED"), List.of());
        var finalReady = corpus.readyChunks(after.getId());
        Map<UUID, ReviewedSourceApplicabilityService.Match> finalReviews = reviews.eligibleTopic(
                topic, asOf, null, null, finalReady);
        var readyById = finalReady.stream().collect(Collectors.toMap(
                RetrievalCorpusSnapshotService.EligibleChunk::sourceChunkId, Function.identity()));
        if (found.candidates().stream().anyMatch(candidate ->
                !after.getId().equals(candidate.retrievalGenerationId())
                || !Objects.equals(eligible.get(candidate.sourceChunkId()), finalReviews.get(candidate.sourceChunkId()))
                || !finalReviews.containsKey(candidate.sourceChunkId())
                || !sameCandidate(candidate, readyById.get(candidate.sourceChunkId()))))
            return result(id, current, asOf, "STALE", List.of("SOURCE_STATE_CHANGED"), List.of());
        List<ConsultationOfficialInformationResponse.Candidate> candidates = found.candidates().stream()
                .map(candidate -> {
                    var review = finalReviews.get(candidate.sourceChunkId());
                    return new ConsultationOfficialInformationResponse.Candidate(candidate.sourceDocumentId(),
                            candidate.documentVersion(), candidate.sourceChunkId(), candidate.retrievalGenerationId(),
                            candidate.organizationName(), candidate.title(), candidate.canonicalUrl(),
                            candidate.articleReference(), candidate.pageReference(), candidate.locator(),
                            candidate.body(), review.scopeKind(), review.reviewId(), review.institution(),
                            review.product(), review.start(), review.end(), review.conditionLocators(),
                            review.exceptionLocators());
                }).toList();
        String status = found.status() == RetrievalStatus.FOUND ? "READY_CANDIDATES"
                : found.status() == RetrievalStatus.TEMPORARILY_UNAVAILABLE ? "TEMPORARILY_UNAVAILABLE"
                : "INSUFFICIENT_EVIDENCE";
        return new GeneralTopicInformationResponse(id, current.getCaseInputRevision(), topic,
                asOf, status, found.coverageGaps(), candidates, request.timeScope(),
                current.getFollowUpAnswerRevision());
    }

    private Consultation owned(UUID id, UUID sessionId) {
        return consultations.findByIdAndGuestSession_Id(id, sessionId)
                .orElseThrow(ConsultationNotFoundException::new);
    }

    private static boolean sameCandidate(OfficialEvidenceCandidate candidate,
            RetrievalCorpusSnapshotService.EligibleChunk chunk) {
        return chunk != null && candidate.sourceDocumentId().equals(chunk.sourceDocumentId())
                && candidate.documentVersion() == chunk.documentVersion()
                && Objects.equals(candidate.locator(), chunk.locator())
                && Objects.equals(candidate.canonicalUrl(), chunk.canonicalUrl())
                && Objects.equals(candidate.body(), chunk.body());
    }

    private static GeneralTopicInformationResponse result(UUID id, Consultation c, LocalDate asOf,
            String status, List<String> gaps, List<ConsultationOfficialInformationResponse.Candidate> candidates) {
        return new GeneralTopicInformationResponse(id, c.getCaseInputRevision(),
                c.getConfirmedInformationTopic(), asOf, status, gaps, candidates);
    }
}
