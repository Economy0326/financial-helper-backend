package com.financialhelper.ai.information;

import com.financialhelper.consultation.ConsultationRepository;
import com.financialhelper.consultation.ConsultationScenario;
import com.financialhelper.consultation.ConsultationScenarioResolver;
import com.financialhelper.guest.GuestSessionService;
import com.financialhelper.procedure.CardCaseFactExtractor;
import com.financialhelper.procedure.ScenarioCaseFactExtractor;
import com.financialhelper.retrieval.ConfirmedCaseSnapshotData;
import com.financialhelper.retrieval.ConsultationOfficialInformationResponse;
import com.financialhelper.retrieval.OfficialSearchRequest;
import com.financialhelper.retrieval.RetrievalCorpusSnapshotService;
import com.financialhelper.retrieval.ReviewedSourceApplicabilityService;
import com.financialhelper.source.ActiveRetrievalGenerationPersistenceService;
import com.financialhelper.source.RetrievalGenerationStatus;
import com.financialhelper.source.SourceDocumentRepository;
import com.financialhelper.source.SourceDocumentStatus;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.time.LocalDate;

/** Validates and assembles a detached information candidate snapshot; does not run search or AI. */
@Service
public class InformationEvidenceSnapshotService {
    private final GuestSessionService sessions;
    private final ConsultationRepository consultations;
    private final ActiveRetrievalGenerationPersistenceService activeGeneration;
    private final RetrievalCorpusSnapshotService corpus;
    private final ReviewedSourceApplicabilityService reviews;
    private final SourceDocumentRepository documents;

    public InformationEvidenceSnapshotService(GuestSessionService sessions, ConsultationRepository consultations,
            ActiveRetrievalGenerationPersistenceService activeGeneration,
            RetrievalCorpusSnapshotService corpus, ReviewedSourceApplicabilityService reviews,
            SourceDocumentRepository documents) {
        this.sessions = sessions;
        this.consultations = consultations;
        this.activeGeneration = activeGeneration;
        this.corpus = corpus;
        this.reviews = reviews;
        this.documents = documents;
    }

    public InformationEvidenceSnapshot assemble(UUID consultationId, String rawToken,
            ConsultationOfficialInformationResponse found, ConfirmedCaseSnapshotData confirmed) {
        Objects.requireNonNull(found, "search response is required");
        Objects.requireNonNull(confirmed, "confirmed facts are required");
        // Authenticate and check ownership before querying source documents or reviews.
        assertCurrent(consultationId, rawToken, found, null);
        if (!consultationId.equals(found.consultationId())
                || !consultationId.equals(confirmed.consultationId())
                || found.caseInputRevision() != confirmed.caseInputRevision()
                || found.followUpAnswerRevision() != confirmed.followUpAnswerRevision()) {
            throw new IllegalStateException("information input identity mismatch");
        }
        if ("STALE".equals(found.status())) throw new IllegalStateException("information search is stale");
        if (!found.candidates().isEmpty() && !"FOUND".equals(found.status())) {
            throw new IllegalStateException("information candidate status is inconsistent");
        }
        if (!found.candidates().isEmpty() && !scopeMatches(found, confirmed)) {
            throw new IllegalStateException("information scope differs from confirmed facts");
        }
        if (found.candidates().isEmpty() || !"FOUND".equals(found.status())) {
            assertCurrent(consultationId, rawToken, found, null);
            return new InformationEvidenceSnapshot(consultationId, found.caseInputRevision(),
                    found.followUpAnswerRevision(), found.scenario(), null, minimizedFacts(confirmed),
                    found.missingFacts(), found.coverageGaps(), List.of(),
                    "NO_APPROVED_EVIDENCE", "NOT_ESTABLISHED", "NOT_DETERMINED");
        }
        UUID generationId = found.candidates().getFirst().retrievalGenerationId();
        assertCurrent(consultationId, rawToken, found, generationId);
        var ready = corpus.readyChunks(generationId);
        Map<UUID, RetrievalCorpusSnapshotService.EligibleChunk> readyById = new HashMap<>();
        ready.forEach(chunk -> readyById.put(chunk.sourceChunkId(), chunk));
        var scope = found.scope();
        if (scope == null || found.scenario() == null || scope.incidentDate() == null) {
            throw new IllegalStateException("information scope is unavailable");
        }
        String searchProduct = "PERSONAL_CREDIT_CARD".equals(scope.productType())
                ? "개인 신용카드" : scope.productType();
        var request = new OfficialSearchRequest("reviewed information candidate", null,
                scope.institution(), searchProduct, scope.incidentDate(),
                confirmed.facts(), 10, found.scenario());
        Map<UUID, ReviewedSourceApplicabilityService.Match> eligible = reviews.eligible(request, ready);
        List<InformationEvidenceSnapshot.Evidence> evidence = new ArrayList<>();
        for (var candidate : found.candidates()) {
            if (!generationId.equals(candidate.retrievalGenerationId())) {
                throw new IllegalStateException("mixed retrieval generations");
            }
            var support = readyById.get(candidate.sourceChunkId());
            var review = eligible.get(candidate.sourceChunkId());
            if (support == null || review == null || !review.reviewId().equals(candidate.applicabilityReviewId())
                    || !support.sourceDocumentId().equals(candidate.sourceDocumentId())
                    || support.documentVersion() != candidate.documentVersion()
                    || !Objects.equals(support.body(), candidate.excerpt())
                    || !Objects.equals(support.locator(), candidate.locator())
                    || !Objects.equals(support.canonicalUrl(), candidate.canonicalUrl())
                    || !Objects.equals(support.organizationName(), candidate.organizationName())
                    || !Objects.equals(support.title(), candidate.title())
                    || !Objects.equals(support.articleReference(), candidate.articleReference())
                    || !Objects.equals(support.pageReference(), candidate.pageReference())
                    || !Objects.equals(review.scopeKind(), candidate.scopeKind())
                    || !Objects.equals(review.institution(), candidate.applicableInstitution())
                    || !Objects.equals(review.product(), candidate.applicableProduct())
                    || !Objects.equals(review.start(), candidate.applicabilityStartDate())
                    || !Objects.equals(review.end(), candidate.applicabilityEndDate())
                    || !Objects.equals(review.conditionLocators(), candidate.conditionLocators())
                    || !Objects.equals(review.exceptionLocators(), candidate.exceptionLocators())) {
                throw new IllegalStateException("information candidate identity or review changed");
            }
            var document = documents.findById(candidate.sourceDocumentId()).orElseThrow(() ->
                    new IllegalStateException("source document is unavailable"));
            if (document.getStatus() != SourceDocumentStatus.ACTIVE
                    || document.getDocumentVersion() != candidate.documentVersion()
                    || !Objects.equals(document.getCanonicalUrl(), candidate.canonicalUrl())
                    || document.getRawSha256() == null || !document.getRawSha256().matches("[0-9a-f]{64}")) {
                throw new IllegalStateException("source document identity changed");
            }
            evidence.add(new InformationEvidenceSnapshot.Evidence(document.getId(),
                    document.getDocumentVersion(), document.getRawSha256(), support.sourceChunkId(),
                    review.reviewId(), review.scopeKind(), support.organizationName(), support.title(),
                    support.canonicalUrl(), support.articleReference(), support.pageReference(),
                    support.locator(), support.body(), review.institution(), review.product(),
                    review.start(), review.end(), related(ready, support.sourceDocumentId(),
                    review.conditionLocators()), related(ready, support.sourceDocumentId(),
                    review.exceptionLocators())));
        }
        assertCurrent(consultationId, rawToken, found, generationId);
        if (!eligible.equals(reviews.eligible(request, corpus.readyChunks(generationId)))) {
            throw new IllegalStateException("information review changed during capture");
        }
        return new InformationEvidenceSnapshot(consultationId, found.caseInputRevision(),
                found.followUpAnswerRevision(), found.scenario(), generationId, minimizedFacts(confirmed),
                found.missingFacts(), found.coverageGaps(), evidence,
                "CURRENT_READY_SOURCE_AND_HASH", "V38_SCOPE_REVIEWED", "NOT_DETERMINED");
    }

    private void assertCurrent(UUID id, String rawToken,
            ConsultationOfficialInformationResponse found, UUID generationId) {
        var session = sessions.requireValidSession(rawToken);
        var consultation = consultations.findByIdAndGuestSession_Id(id, session.getId())
                .orElseThrow(() -> new IllegalStateException("consultation is unavailable"));
        if (consultation.getCaseInputRevision() != found.caseInputRevision()
                || consultation.getFollowUpAnswerRevision() != found.followUpAnswerRevision()) {
            throw new IllegalStateException("confirmed facts changed during capture");
        }
        if (found.scenario() != null && !found.scenario().equals(
                ConsultationScenarioResolver.resolve(consultation).name())) {
            throw new IllegalStateException("information scenario changed during capture");
        }
        if (generationId != null) {
            var current = activeGeneration.current().orElseThrow(() ->
                    new IllegalStateException("READY retrieval generation is unavailable"));
            if (current.getStatus() != RetrievalGenerationStatus.READY
                    || !generationId.equals(current.getId())) {
                throw new IllegalStateException("retrieval generation changed during capture");
            }
        }
    }

    private static List<InformationEvidenceSnapshot.RelatedText> related(
            List<RetrievalCorpusSnapshotService.EligibleChunk> ready, UUID documentId,
            List<String> locators) {
        List<InformationEvidenceSnapshot.RelatedText> result = new ArrayList<>();
        for (String locator : locators) {
            var matches = ready.stream().filter(chunk -> documentId.equals(chunk.sourceDocumentId())
                    && locator.equals(chunk.locator())).toList();
            if (matches.size() != 1) throw new IllegalStateException("related condition or exception is unavailable");
            var chunk = matches.getFirst();
            result.add(new InformationEvidenceSnapshot.RelatedText(chunk.sourceChunkId(), locator, chunk.body()));
        }
        return List.copyOf(result);
    }

    private static List<ConfirmedCaseSnapshotData.Fact> minimizedFacts(ConfirmedCaseSnapshotData snapshot) {
        // The raw situationText may contain account numbers or authentication data.
        return snapshot.facts().stream().filter(fact -> !"situationText".equals(fact.key()))
                .filter(fact -> "USER_ANSWERED".equals(fact.source())
                        || "USER_SELECTED".equals(fact.source())
                        || "SYSTEM_RESOLVED_FROM_EXPLICIT_INPUT".equals(fact.source()))
                .toList();
    }

    private static boolean scopeMatches(ConsultationOfficialInformationResponse found,
            ConfirmedCaseSnapshotData confirmed) {
        if (found.scope() == null || found.scenario() == null) return false;
        ConsultationScenario scenario;
        try { scenario = ConsultationScenario.valueOf(found.scenario()); }
        catch (IllegalArgumentException exception) { return false; }
        var extracted = scenario == ConsultationScenario.CARD_LOSS_UNAUTHORIZED_USE
                ? CardCaseFactExtractor.fromSnapshot(confirmed)
                : ScenarioCaseFactExtractor.fromSnapshot(confirmed);
        String dateKey = scenario == ConsultationScenario.UNAUTHORIZED_ACCOUNT_TRANSFER
                ? "transactionDate" : "incidentDate";
        LocalDate date;
        try { date = LocalDate.parse(extracted.value(dateKey)); }
        catch (RuntimeException exception) { date = null; }
        return Objects.equals(known(extracted.value("institution")), found.scope().institution())
                && Objects.equals(known(extracted.value("productType")), found.scope().productType())
                && Objects.equals(date, found.scope().incidentDate());
    }

    private static String known(String value) {
        return value == null || value.isBlank() || "UNKNOWN".equalsIgnoreCase(value) ? null : value;
    }
}
