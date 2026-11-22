package com.financialhelper.ai.information;

import com.financialhelper.consultation.ConsultationNotFoundException;
import com.financialhelper.consultation.ConsultationRepository;
import com.financialhelper.consultation.FinancialTopic;
import com.financialhelper.guest.GuestSessionService;
import com.financialhelper.retrieval.DepositQueryPurpose;
import com.financialhelper.retrieval.SixTopicQueryPurpose;
import com.financialhelper.retrieval.GeneralTopicInformationRequest;
import com.financialhelper.retrieval.GeneralTopicInformationResponse;
import com.financialhelper.retrieval.RetrievalCorpusSnapshotService;
import com.financialhelper.retrieval.ReviewedSourceApplicabilityService;
import com.financialhelper.source.ActiveRetrievalGenerationPersistenceService;
import com.financialhelper.source.RetrievalGenerationStatus;
import com.financialhelper.source.SourceDocumentRepository;
import com.financialhelper.source.SourceDocumentStatus;
import com.financialhelper.source.VerifiedPdfMetadata;
import com.financialhelper.source.OfficialInformationClaimSelector;
import com.financialhelper.source.SourceHashing;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Reassembles Topic evidence from current DB state, without running retrieval or an AI call. */
@Service
public class GeneralTopicEvidenceSnapshotService {
    private final GuestSessionService sessions;
    private final ConsultationRepository consultations;
    private final ActiveRetrievalGenerationPersistenceService generations;
    private final RetrievalCorpusSnapshotService corpus;
    private final ReviewedSourceApplicabilityService reviews;
    private final SourceDocumentRepository documents;

    public GeneralTopicEvidenceSnapshotService(GuestSessionService sessions, ConsultationRepository consultations,
            ActiveRetrievalGenerationPersistenceService generations, RetrievalCorpusSnapshotService corpus,
            ReviewedSourceApplicabilityService reviews, SourceDocumentRepository documents) {
        this.sessions = sessions;
        this.consultations = consultations;
        this.generations = generations;
        this.corpus = corpus;
        this.reviews = reviews;
        this.documents = documents;
    }

    public InformationEvidenceSnapshot assemble(UUID id, String token, GeneralTopicInformationResponse found) {
        var session = sessions.requireValidSession(token);
        var consultation = consultations.findByIdAndGuestSession_Id(id, session.getId())
                .orElseThrow(ConsultationNotFoundException::new);
        boolean deposit = found.topic() == FinancialTopic.DEPOSITS_ACCOUNTS;
        SixTopicQueryPurpose.Match purpose = deposit ? null
                : SixTopicQueryPurpose.resolve(found.topic(), consultation.getSituationText());
        if (!id.equals(found.consultationId()) || !"READY_CANDIDATES".equals(found.status())
                || (!deposit && purpose == null)
                || consultation.getConfirmedInformationTopic() != found.topic()
                || consultation.getScenario() != null
                || consultation.getCaseInputRevision() != found.caseInputRevision()
                || consultation.getFollowUpAnswerRevision() != found.followUpAnswerRevision()
                || found.candidates().isEmpty() || found.asOfDate() == null
                || found.timeScope() == null
                || (found.timeScope() == GeneralTopicInformationRequest.TimeScope.CURRENT
                    && (!LocalDate.now(ZoneId.of("Asia/Seoul")).equals(found.asOfDate())
                        || deposit && DepositQueryPurpose.hasHistoricalMarker(consultation.getSituationText())))
                || (!deposit && found.timeScope() != GeneralTopicInformationRequest.TimeScope.CURRENT)) {
            throw new IllegalStateException("general topic input or reference date changed");
        }
        List<Integer> allowed = DepositQueryPurpose.questionNumbers(consultation.getSituationText());
        if (deposit && allowed.isEmpty()) throw new IllegalStateException("general topic question is not answerable");
        var generation = generations.current().orElseThrow(() ->
                new IllegalStateException("READY retrieval generation is unavailable"));
        if (generation.getStatus() != RetrievalGenerationStatus.READY)
            throw new IllegalStateException("retrieval generation is not READY");
        var ready = corpus.readyChunks(generation.getId());
        var readyById = new HashMap<UUID, RetrievalCorpusSnapshotService.EligibleChunk>();
        ready.forEach(chunk -> readyById.put(chunk.sourceChunkId(), chunk));
        Map<UUID, ReviewedSourceApplicabilityService.Match> eligible = reviews.eligibleTopic(
                found.topic(), found.asOfDate(),
                found.timeScope() == GeneralTopicInformationRequest.TimeScope.HISTORICAL,
                null, null, ready);
        List<InformationEvidenceSnapshot.Evidence> evidence = new ArrayList<>();
        for (var candidate : found.candidates()) {
            var support = readyById.get(candidate.sourceChunkId());
            var review = eligible.get(candidate.sourceChunkId());
            if (support == null || review == null
                    || !generation.getId().equals(candidate.retrievalGenerationId())
                    || !support.sourceDocumentId().equals(candidate.sourceDocumentId())
                    || support.documentVersion() != candidate.documentVersion()
                    || !Objects.equals(support.body(), candidate.excerpt())
                    || !Objects.equals(support.locator(), candidate.locator())
                    || !Objects.equals(support.pageReference(), candidate.pageReference())
                    || !Objects.equals(support.title(), candidate.title())
                    || !Objects.equals(support.organizationName(), candidate.organizationName())
                    || !Objects.equals(review.reviewId(), candidate.applicabilityReviewId())
                    || !Objects.equals(review.scopeKind(), candidate.scopeKind())
                    || !Objects.equals(review.conditionLocators(), candidate.conditionLocators())
                    || !Objects.equals(review.exceptionLocators(), candidate.exceptionLocators())
                    || (deposit && !VerifiedPdfMetadata.DEPOSIT_PDF_URL.equals(support.canonicalUrl()))
                    || support.parentSection() == null || (deposit && support.pageReference() == null)
                    || support.locator() == null) {
                throw new IllegalStateException("general topic evidence identity changed");
            }
            Integer question = deposit ? allowed.stream()
                    .filter(number -> support.parentSection().startsWith(number + ". ")
                        && support.body().startsWith(number + ". ")).findFirst().orElseThrow(() ->
                        new IllegalStateException("evidence is outside the question scope")) : null;
            var document = documents.findById(candidate.sourceDocumentId()).orElseThrow(() ->
                    new IllegalStateException("source document is unavailable"));
            if (document.getStatus() != SourceDocumentStatus.ACTIVE
                    || document.getDocumentVersion() != candidate.documentVersion()
                    || (deposit && (!VerifiedPdfMetadata.DEPOSIT_RAW_SHA256.equals(document.getRawSha256())
                        || !VerifiedPdfMetadata.DEPOSIT_PDF_URL.equals(document.getCanonicalUrl())
                        || !"금융위원회".equals(document.getOrganizationName())
                        || !"예금보호한도 상향 관련 주요 QA".equals(document.getTitle())
                        || !LocalDate.of(2025, 7, 22).equals(document.getPublishedAt())))
                    || (!deposit && (!OfficialInformationClaimSelector.officialUrl(purpose.sourceKey())
                            .equals(document.getCanonicalUrl())
                        || !SourceHashing.sha256(document.getOriginalContent()).equals(document.getRawSha256())
                        || OfficialInformationClaimSelector.select(document, "verification", "{}")
                            .stream().noneMatch(definition ->
                                definition.body().equals(support.body())
                                && definition.locator().equals(support.locator())
                                && definition.parentSection().equals(support.parentSection()))))) {
                throw new IllegalStateException("official PDF version or metadata changed");
            }
            evidence.add(new InformationEvidenceSnapshot.Evidence(document.getId(),
                    document.getDocumentVersion(), document.getRawSha256(), support.sourceChunkId(),
                    review.reviewId(), review.scopeKind(), support.organizationName(), support.title(),
                    support.canonicalUrl(), support.articleReference(), support.pageReference(),
                    support.locator(), support.body(), review.institution(), review.product(),
                    review.start(), review.end(), related(ready, support.sourceDocumentId(),
                    review.conditionLocators()), related(ready, support.sourceDocumentId(),
                    review.exceptionLocators()), question,
                    deposit ? VerifiedPdfMetadata.DEPOSIT_POST_URL : null));
        }
        var latest = consultations.findByIdAndGuestSession_Id(id, session.getId())
                .orElseThrow(ConsultationNotFoundException::new);
        var after = generations.current().orElseThrow(() -> new IllegalStateException("generation changed"));
        if (latest.getCaseInputRevision() != found.caseInputRevision()
                || latest.getFollowUpAnswerRevision() != found.followUpAnswerRevision()
                || latest.getConfirmedInformationTopic() != found.topic()
                || !after.getId().equals(generation.getId())
                || !eligible.equals(reviews.eligibleTopic(found.topic(), found.asOfDate(),
                        found.timeScope() == GeneralTopicInformationRequest.TimeScope.HISTORICAL, null, null,
                        corpus.readyChunks(generation.getId())))) {
            throw new IllegalStateException("general topic evidence changed during capture");
        }
        return new InformationEvidenceSnapshot(id, found.caseInputRevision(), found.followUpAnswerRevision(),
                null, generation.getId(), List.of(), List.of(), found.coverageGaps(), evidence,
                "CURRENT_READY_SOURCE_AND_HASH", "V38_SCOPE_REVIEWED", "GENERAL_INFORMATION_ONLY",
                found.topic().name(), found.asOfDate());
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
}
