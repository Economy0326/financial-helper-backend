package com.financialhelper.retrieval;

import com.financialhelper.consultation.Consultation;
import com.financialhelper.consultation.ConsultationNotFoundException;
import com.financialhelper.consultation.ConsultationRepository;
import com.financialhelper.consultation.ConsultationScenario;
import com.financialhelper.consultation.ConsultationScenarioResolver;
import com.financialhelper.guest.GuestSession;
import com.financialhelper.guest.GuestSessionService;
import com.financialhelper.procedure.CardCaseFactExtractor;
import com.financialhelper.procedure.CardCaseFacts;
import com.financialhelper.procedure.ProcedureVersionService;
import com.financialhelper.procedure.ScenarioCaseFactExtractor;
import com.financialhelper.source.ActiveRetrievalGenerationPersistenceService;
import com.financialhelper.source.RetrievalGeneration;
import com.financialhelper.source.RetrievalGenerationStatus;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/** ACTION Plan과 독립적인 공식 문서 후보 검색. 금융 절차의 사건 적용성은 판단하지 않는다. */
@Service
public class ConsultationOfficialInformationService {
    private static final String CANDIDATE_LIMITATION = "DOCUMENT_CANDIDATE_ONLY";
    private final GuestSessionService guestSessionService;
    private final ConsultationRepository consultationRepository;
    private final ConfirmedCaseSnapshotService snapshotService;
    private final OfficialSourceRetrievalService retrievalService;
    private final ActiveRetrievalGenerationPersistenceService activeGeneration;
    private final RetrievalCorpusSnapshotService corpusSnapshot;
    private final ReviewedSourceApplicabilityService applicability;

    public ConsultationOfficialInformationService(
            GuestSessionService guestSessionService,
            ConsultationRepository consultationRepository,
            ConfirmedCaseSnapshotService snapshotService,
            OfficialSourceRetrievalService retrievalService,
            ActiveRetrievalGenerationPersistenceService activeGeneration,
            RetrievalCorpusSnapshotService corpusSnapshot,
            ReviewedSourceApplicabilityService applicability
    ) {
        this.guestSessionService = guestSessionService;
        this.consultationRepository = consultationRepository;
        this.snapshotService = snapshotService;
        this.retrievalService = retrievalService;
        this.activeGeneration = activeGeneration;
        this.corpusSnapshot = corpusSnapshot;
        this.applicability = applicability;
    }

    public ConsultationOfficialInformationResponse search(
            UUID consultationId, String rawToken, ConsultationOfficialInformationRequest request
    ) {
        GuestSession session = guestSessionService.requireValidSession(rawToken);
        Consultation consultation = owned(consultationId, session);
        if (!revisionsMatch(consultation, request)) {
            return stale(consultationId, consultation);
        }

        // capture() may reconcile question attribution and write a revisioned snapshot.
        // It completes its own short transaction before any remote retrieval begins.
        ConfirmedCaseSnapshotData snapshot = snapshotService.capture(consultationId);
        if (snapshot.caseInputRevision() != request.expectedCaseInputRevision()) {
            return stale(consultationId, owned(consultationId, session));
        }
        ConsultationScenario scenario = ConsultationScenarioResolver.resolve(consultation);
        CardCaseFacts facts = scenario == ConsultationScenario.CARD_LOSS_UNAUTHORIZED_USE
                ? CardCaseFactExtractor.fromSnapshot(snapshot)
                : ScenarioCaseFactExtractor.fromSnapshot(snapshot);
        String institution = known(facts.value("institution"));
        String product = known(facts.value("productType"));
        String dateValue = known(facts.value(scenario == ConsultationScenario.UNAUTHORIZED_ACCOUNT_TRANSFER
                ? "transactionDate" : "incidentDate"));
        LocalDate date = parseDate(dateValue);
        ConsultationOfficialInformationResponse.Scope scope =
                new ConsultationOfficialInformationResponse.Scope(institution, product, date);

        List<String> missing = new ArrayList<>();
        if (scenario == ConsultationScenario.UNKNOWN) missing.add("SCENARIO");
        if (institution == null) missing.add("INSTITUTION");
        if (scenario == ConsultationScenario.CARD_LOSS_UNAUTHORIZED_USE && product == null)
            missing.add("PRODUCT_TYPE");
        if (date == null) missing.add(scenario == ConsultationScenario.UNAUTHORIZED_ACCOUNT_TRANSFER
                ? "TRANSACTION_DATE" : "INCIDENT_DATE");
        if (missing.stream().anyMatch(value -> !"INSTITUTION".equals(value))) {
            return currentResult(consultationId, session, snapshot, scenario, scope,
                    "NEEDS_CLARIFICATION", missing, List.of());
        }

        if (scenario == ConsultationScenario.CARD_LOSS_UNAUTHORIZED_USE
                && !ProcedureVersionService.PERSONAL_CREDIT_CARD.equals(product)) {
            return currentResult(consultationId, session, snapshot, scenario, scope,
                    "NO_MATCH", List.of(), List.of("CARD_SCOPE_OUT_OF_SCOPE"));
        }
        String searchProduct = ProcedureVersionService.PERSONAL_CREDIT_CARD.equals(product)
                ? "개인 신용카드" : product;
        RetrievalGeneration before = activeGeneration.current().orElse(null);
        if (before == null || before.getStatus() != RetrievalGenerationStatus.READY) {
            return currentResult(consultationId, session, snapshot, scenario, scope,
                    "NO_MATCH", List.of(), List.of("NO_ACTIVE_READY_GENERATION"));
        }

        OfficialSearchRequest searchRequest = new OfficialSearchRequest(
                queryFor(scenario), scenario == ConsultationScenario.CARD_LOSS_UNAUTHORIZED_USE
                ? "CARD" : "FINANCIAL_FRAUD", institution, searchProduct, date,
                snapshot.facts(), 10, scenario.name());
        // No public search handle or unbound context exists while this call is in flight.
        Map<UUID, ReviewedSourceApplicabilityService.Match> reviewed = applicability.eligible(
                searchRequest, corpusSnapshot.readyChunks(before.getId()));
        OfficialSearchResponse found = retrievalService.searchReviewedCandidates(
                searchRequest, reviewed.keySet());
        Consultation current = owned(consultationId, session);
        RetrievalGeneration after = activeGeneration.current().orElse(null);
        if (!sameRevisions(current, snapshot) || after == null
                || after.getStatus() != RetrievalGenerationStatus.READY
                || !before.getId().equals(after.getId())) {
            return stale(consultationId, current);
        }

        Map<UUID, RetrievalCorpusSnapshotService.EligibleChunk> eligible = corpusSnapshot
                .readyChunks(after.getId()).stream()
                .collect(Collectors.toMap(RetrievalCorpusSnapshotService.EligibleChunk::sourceChunkId,
                        Function.identity()));
        Map<UUID, ReviewedSourceApplicabilityService.Match> currentReviews =
                applicability.eligible(searchRequest, List.copyOf(eligible.values()));
        for (OfficialEvidenceCandidate candidate : found.candidates()) {
            RetrievalCorpusSnapshotService.EligibleChunk chunk = eligible.get(candidate.sourceChunkId());
            if (!after.getId().equals(candidate.retrievalGenerationId()) || chunk == null
                    || !java.util.Objects.equals(reviewed.get(candidate.sourceChunkId()),
                            currentReviews.get(candidate.sourceChunkId()))
                    || !currentReviews.containsKey(candidate.sourceChunkId())
                    || !chunk.sourceDocumentId().equals(candidate.sourceDocumentId())
                    || chunk.documentVersion() != candidate.documentVersion()
                    || !java.util.Objects.equals(chunk.locator(), candidate.locator())
                    || !java.util.Objects.equals(chunk.canonicalUrl(), candidate.canonicalUrl())) {
                return stale(consultationId, current);
            }
        }
        Consultation finalConsultation = owned(consultationId, session);
        RetrievalGeneration finalGeneration = activeGeneration.current().orElse(null);
        if (!sameRevisions(finalConsultation, snapshot) || finalGeneration == null
                || finalGeneration.getStatus() != RetrievalGenerationStatus.READY
                || !before.getId().equals(finalGeneration.getId())) {
            return stale(consultationId, finalConsultation);
        }
        Map<UUID, ReviewedSourceApplicabilityService.Match> finalReviews =
                applicability.eligible(searchRequest, corpusSnapshot.readyChunks(finalGeneration.getId()));
        if (found.candidates().stream().anyMatch(candidate ->
                !java.util.Objects.equals(reviewed.get(candidate.sourceChunkId()),
                        finalReviews.get(candidate.sourceChunkId()))
                        || !finalReviews.containsKey(candidate.sourceChunkId()))) {
            return stale(consultationId, finalConsultation);
        }
        List<ConsultationOfficialInformationResponse.Candidate> candidates = found.candidates().stream()
                .map(candidate -> {
                    var match = finalReviews.get(candidate.sourceChunkId());
                    return new ConsultationOfficialInformationResponse.Candidate(
                        candidate.sourceDocumentId(), candidate.documentVersion(),
                        candidate.sourceChunkId(), candidate.retrievalGenerationId(),
                        candidate.organizationName(), candidate.title(), candidate.canonicalUrl(),
                        candidate.articleReference(), candidate.pageReference(), candidate.locator(),
                        candidate.body(), match.scopeKind(), match.reviewId(),
                        match.institution(), match.product(), match.start(), match.end(),
                        match.conditionLocators(), match.exceptionLocators());
                })
                .toList();
        List<String> gaps = new ArrayList<>(found.coverageGaps());
        if (institution == null) gaps.add("INSTITUTION_UNCONFIRMED");
        else if (finalReviews.values().stream().noneMatch(match ->
                "INSTITUTION_SPECIFIC".equals(match.scopeKind()))) {
            gaps.add("NO_APPROVED_INSTITUTION_CORPUS");
        }
        return result(consultationId, snapshot, scenario, scope, found.status().name(),
                found.degraded(), missing, gaps,
                candidates.isEmpty() ? List.of() : List.of(CANDIDATE_LIMITATION), candidates);
    }

    private Consultation owned(UUID consultationId, GuestSession session) {
        return consultationRepository.findByIdAndGuestSession_Id(consultationId, session.getId())
                .orElseThrow(ConsultationNotFoundException::new);
    }

    private ConsultationOfficialInformationResponse currentResult(
            UUID id, GuestSession session, ConfirmedCaseSnapshotData snapshot,
            ConsultationScenario scenario, ConsultationOfficialInformationResponse.Scope scope,
            String status, List<String> missing, List<String> gaps
    ) {
        Consultation current = owned(id, session);
        if (!sameRevisions(current, snapshot)) return stale(id, current);
        return result(id, snapshot, scenario, scope, status, false, missing, gaps,
                List.of(), List.of());
    }

    private static boolean revisionsMatch(Consultation current,
                                          ConsultationOfficialInformationRequest request) {
        return current.getCaseInputRevision() == request.expectedCaseInputRevision();
    }

    private static boolean sameRevisions(Consultation current, ConfirmedCaseSnapshotData snapshot) {
        return current.getCaseInputRevision() == snapshot.caseInputRevision()
                && current.getFollowUpAnswerRevision() == snapshot.followUpAnswerRevision();
    }

    private static String known(String value) {
        return value == null || value.isBlank() || "UNKNOWN".equalsIgnoreCase(value) ? null : value;
    }

    private static LocalDate parseDate(String value) {
        if (value == null) return null;
        try { return LocalDate.parse(value); }
        catch (DateTimeParseException exception) { return null; }
    }

    private static String queryFor(ConsultationScenario scenario) {
        return switch (scenario) {
            case CARD_LOSS_UNAUTHORIZED_USE -> "신용카드 분실 부정사용 공식 정보";
            case VOICE_PHISHING_SUSPICIOUS_TRANSFER -> "보이스피싱 의심 송금 공식 정보";
            case UNAUTHORIZED_ACCOUNT_TRANSFER -> "무단 계좌이체 공식 정보";
            case PERSONAL_INFO_SMISHING_MALICIOUS_APP -> "스미싱 악성 앱 개인정보 공식 정보";
            default -> throw new IllegalArgumentException("unsupported scenario");
        };
    }

    private static ConsultationOfficialInformationResponse stale(UUID id, Consultation current) {
        return new ConsultationOfficialInformationResponse(id, current.getCaseInputRevision(),
                current.getFollowUpAnswerRevision(), null, null, "STALE", false,
                List.of(), List.of(), List.of(), List.of());
    }

    private static ConsultationOfficialInformationResponse result(
            UUID id, ConfirmedCaseSnapshotData snapshot, ConsultationScenario scenario,
            ConsultationOfficialInformationResponse.Scope scope, String status, boolean degraded,
            List<String> missing, List<String> gaps, List<String> limitations,
            List<ConsultationOfficialInformationResponse.Candidate> candidates
    ) {
        return new ConsultationOfficialInformationResponse(id, snapshot.caseInputRevision(),
                snapshot.followUpAnswerRevision(), scenario.name(), scope, status, degraded,
                missing, gaps, limitations, candidates);
    }
}
