package com.financialhelper.ai.information;

import com.financialhelper.consultation.Consultation;
import com.financialhelper.consultation.ConsultationRepository;
import com.financialhelper.consultation.ConsultationScenario;
import com.financialhelper.guest.GuestSession;
import com.financialhelper.guest.GuestSessionService;
import com.financialhelper.retrieval.ConfirmedCaseSnapshotData;
import com.financialhelper.retrieval.ConsultationOfficialInformationResponse;
import com.financialhelper.retrieval.RetrievalCorpusSnapshotService;
import com.financialhelper.retrieval.ReviewedSourceApplicabilityService;
import com.financialhelper.source.ActiveRetrievalGenerationPersistenceService;
import com.financialhelper.source.RetrievalGeneration;
import com.financialhelper.source.RetrievalGenerationStatus;
import com.financialhelper.source.SourceDocument;
import com.financialhelper.source.SourceDocumentRepository;
import com.financialhelper.source.SourceDocumentStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class InformationEvidenceSnapshotServiceTest {
    @Mock GuestSessionService sessions;
    @Mock ConsultationRepository consultations;
    @Mock ActiveRetrievalGenerationPersistenceService active;
    @Mock RetrievalCorpusSnapshotService corpus;
    @Mock ReviewedSourceApplicabilityService reviews;
    @Mock SourceDocumentRepository documents;
    @Mock GuestSession session;
    @Mock Consultation consultation;
    @Mock RetrievalGeneration generation;
    @Mock SourceDocument document;

    private final UUID consultationId = UUID.randomUUID();
    private final UUID documentId = UUID.randomUUID();
    private final UUID supportId = UUID.randomUUID();
    private final UUID conditionId = UUID.randomUUID();
    private final UUID exceptionId = UUID.randomUUID();
    private final UUID generationId = UUID.randomUUID();
    private final UUID reviewId = UUID.randomUUID();
    private InformationEvidenceSnapshotService service;

    @BeforeEach
    void setUp() {
        service = new InformationEvidenceSnapshotService(sessions, consultations,
                active, corpus, reviews, documents);
    }

    @Test
    void approvedCandidateKeepsConditionExceptionAndRawHashWithoutRawSituation() {
        var response = found();
        currentOwnership();
        when(active.current()).thenReturn(Optional.of(generation));
        when(generation.getStatus()).thenReturn(RetrievalGenerationStatus.READY);
        when(generation.getId()).thenReturn(generationId);
        when(corpus.readyChunks(generationId)).thenReturn(ready());
        when(reviews.eligible(any(), any())).thenReturn(Map.of(supportId,
                new ReviewedSourceApplicabilityService.Match(reviewId, "COMMON", null, null,
                        LocalDate.of(2025, 1, 1), null, List.of("loc-condition"), List.of("loc-exception"))));
        when(documents.findById(documentId)).thenReturn(Optional.of(document));
        when(document.getStatus()).thenReturn(SourceDocumentStatus.ACTIVE);
        when(document.getDocumentVersion()).thenReturn(1);
        when(document.getCanonicalUrl()).thenReturn("https://official.example.invalid/doc");
        when(document.getRawSha256()).thenReturn("a".repeat(64));
        when(document.getId()).thenReturn(documentId);

        var snapshot = service.assemble(consultationId, "session", response, confirmed());

        assertThat(snapshot.evidence()).hasSize(1);
        assertThat(snapshot.evidence().getFirst().rawSha256()).isEqualTo("a".repeat(64));
        assertThat(snapshot.evidence().getFirst().conditions()).extracting(InformationEvidenceSnapshot.RelatedText::text)
                .containsExactly("조건 본문");
        assertThat(snapshot.evidence().getFirst().exceptions()).extracting(InformationEvidenceSnapshot.RelatedText::text)
                .containsExactly("예외 본문");
        assertThat(snapshot.confirmedFacts()).extracting(ConfirmedCaseSnapshotData.Fact::key)
                .containsExactly("institution", "productType", "incidentDate");
        assertThat(snapshot.caseApplicabilityStatus()).isEqualTo("NOT_DETERMINED");
    }

    @Test
    void missingRelatedExceptionFailsClosed() {
        var response = found();
        currentOwnership();
        when(active.current()).thenReturn(Optional.of(generation));
        when(generation.getStatus()).thenReturn(RetrievalGenerationStatus.READY);
        when(generation.getId()).thenReturn(generationId);
        when(corpus.readyChunks(generationId)).thenReturn(ready().subList(0, 2));
        when(reviews.eligible(any(), any())).thenReturn(Map.of(supportId,
                new ReviewedSourceApplicabilityService.Match(reviewId, "COMMON", null, null,
                        LocalDate.of(2025, 1, 1), null, List.of("loc-condition"), List.of("loc-exception"))));
        when(documents.findById(documentId)).thenReturn(Optional.of(document));
        when(document.getStatus()).thenReturn(SourceDocumentStatus.ACTIVE);
        when(document.getDocumentVersion()).thenReturn(1);
        when(document.getCanonicalUrl()).thenReturn("https://official.example.invalid/doc");
        when(document.getRawSha256()).thenReturn("a".repeat(64));

        assertThatThrownBy(() -> service.assemble(consultationId, "session", response, confirmed()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("condition or exception");
    }

    @Test
    void noFoundEvidenceKeepsGapWithoutClaimingApproval() {
        var response = new ConsultationOfficialInformationResponse(consultationId, 2, 3,
                "CARD_LOSS_UNAUTHORIZED_USE", null, "NO_MATCH", false,
                List.of("INSTITUTION"), List.of("NO_APPROVED_CORPUS"), List.of(), List.of());
        currentOwnership();

        var snapshot = service.assemble(consultationId, "session", response, confirmed());

        assertThat(snapshot.evidence()).isEmpty();
        assertThat(snapshot.sourceIntegrityStatus()).isEqualTo("NO_APPROVED_EVIDENCE");
        assertThat(snapshot.coverageGaps()).containsExactly("NO_APPROVED_CORPUS");
    }

    @Test
    void changedGenerationIsRejectedBeforeReadingCandidateCorpus() {
        currentOwnership();
        when(active.current()).thenReturn(Optional.of(generation));
        when(generation.getStatus()).thenReturn(RetrievalGenerationStatus.READY);
        when(generation.getId()).thenReturn(UUID.randomUUID());

        assertThatThrownBy(() -> service.assemble(consultationId, "session", found(), confirmed()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("generation changed");
        org.mockito.Mockito.verifyNoInteractions(corpus, reviews, documents);
    }

    @Test
    void scopeThatDiffersFromConfirmedFactsIsRejected() {
        currentOwnership();
        var original = found();
        var altered = new ConsultationOfficialInformationResponse(consultationId, 2, 3,
                original.scenario(), new ConsultationOfficialInformationResponse.Scope(
                "다른 기관", "PERSONAL_CREDIT_CARD", LocalDate.of(2026, 1, 1)),
                "FOUND", false, original.missingFacts(), original.coverageGaps(),
                original.limitations(), original.candidates());

        assertThatThrownBy(() -> service.assemble(consultationId, "session", altered, confirmed()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("scope differs");
        org.mockito.Mockito.verifyNoInteractions(corpus, reviews, documents);
    }

    @Test
    void missingReviewIsRejectedBeforeReadingDocument() {
        currentOwnership();
        when(active.current()).thenReturn(Optional.of(generation));
        when(generation.getStatus()).thenReturn(RetrievalGenerationStatus.READY);
        when(generation.getId()).thenReturn(generationId);
        when(corpus.readyChunks(generationId)).thenReturn(ready());
        when(reviews.eligible(any(), any())).thenReturn(Map.of());

        assertThatThrownBy(() -> service.assemble(consultationId, "session", found(), confirmed()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("review changed");
        org.mockito.Mockito.verifyNoInteractions(documents);
    }

    private void currentOwnership() {
        when(sessions.requireValidSession("session")).thenReturn(session);
        when(session.getId()).thenReturn(UUID.fromString("00000000-0000-0000-0000-000000000001"));
        when(consultations.findByIdAndGuestSession_Id(consultationId, session.getId()))
                .thenReturn(Optional.of(consultation));
        when(consultation.getCaseInputRevision()).thenReturn(2L);
        when(consultation.getFollowUpAnswerRevision()).thenReturn(3L);
        when(consultation.getScenario()).thenReturn(ConsultationScenario.CARD_LOSS_UNAUTHORIZED_USE);
    }

    private ConfirmedCaseSnapshotData confirmed() {
        return new ConfirmedCaseSnapshotData(UUID.randomUUID(), consultationId, 2, 3,
                List.of(new ConfirmedCaseSnapshotData.Fact("SITUATION", "situationText",
                                "민감한 상담 원문", null, "USER_STATED", null),
                        new ConfirmedCaseSnapshotData.Fact("TEXT", "institution",
                                "합성기관", null, "USER_ANSWERED", null),
                        new ConfirmedCaseSnapshotData.Fact("TEXT", "productType",
                                "PERSONAL_CREDIT_CARD", null, "USER_ANSWERED", null),
                        new ConfirmedCaseSnapshotData.Fact("DATE", "incidentDate",
                                "2026-01-01", null, "USER_ANSWERED", null)), List.of(), null);
    }

    private ConsultationOfficialInformationResponse found() {
        return new ConsultationOfficialInformationResponse(consultationId, 2, 3,
                "CARD_LOSS_UNAUTHORIZED_USE",
                new ConsultationOfficialInformationResponse.Scope("합성기관", "PERSONAL_CREDIT_CARD", LocalDate.of(2026, 1, 1)),
                "FOUND", false, List.of("INSTITUTION"), List.of(), List.of("DOCUMENT_CANDIDATE_ONLY"),
                List.of(new ConsultationOfficialInformationResponse.Candidate(documentId, 1, supportId,
                        generationId, "합성기관", "합성 문서", "https://official.example.invalid/doc",
                        null, null, "loc-support", "지원 본문", "COMMON", reviewId, null, null,
                        LocalDate.of(2025, 1, 1), null, List.of("loc-condition"), List.of("loc-exception"))));
    }

    private List<RetrievalCorpusSnapshotService.EligibleChunk> ready() {
        return List.of(chunk(supportId, "loc-support", "지원 본문"),
                chunk(conditionId, "loc-condition", "조건 본문"),
                chunk(exceptionId, "loc-exception", "예외 본문"));
    }

    private RetrievalCorpusSnapshotService.EligibleChunk chunk(UUID id, String locator, String body) {
        return new RetrievalCorpusSnapshotService.EligibleChunk(id, body, documentId, 1,
                LocalDate.of(2025, 1, 1), null, "{}", "합성기관", "합성 문서", null,
                null, null, locator, "https://official.example.invalid/doc", "synthetic-source");
    }
}
