package com.financialhelper.retrieval;

import com.financialhelper.consultation.Consultation;
import com.financialhelper.consultation.ConsultationCategory;
import com.financialhelper.consultation.ConsultationNotFoundException;
import com.financialhelper.consultation.ConsultationRepository;
import com.financialhelper.guest.GuestSession;
import com.financialhelper.guest.GuestSessionService;
import com.financialhelper.source.ActiveRetrievalGenerationPersistenceService;
import com.financialhelper.source.RetrievalGeneration;
import com.financialhelper.source.RetrievalGenerationStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ConsultationOfficialInformationServiceTest {
    private final GuestSessionService sessions = mock(GuestSessionService.class);
    private final ConsultationRepository consultations = mock(ConsultationRepository.class);
    private final ConfirmedCaseSnapshotService snapshots = mock(ConfirmedCaseSnapshotService.class);
    private final OfficialSourceRetrievalService retrieval = mock(OfficialSourceRetrievalService.class);
    private final ActiveRetrievalGenerationPersistenceService generations =
            mock(ActiveRetrievalGenerationPersistenceService.class);
    private final RetrievalCorpusSnapshotService corpus = mock(RetrievalCorpusSnapshotService.class);
    private final ConsultationOfficialInformationService service = new ConsultationOfficialInformationService(
            sessions, consultations, snapshots, retrieval, generations, corpus);
    private final UUID consultationId = UUID.randomUUID();
    private final UUID guestId = UUID.randomUUID();
    private final UUID generationId = UUID.randomUUID();
    private final Consultation consultation = mock(Consultation.class);
    private final RetrievalGeneration generation = mock(RetrievalGeneration.class);
    private final ConsultationOfficialInformationRequest request =
            new ConsultationOfficialInformationRequest(2L);

    @BeforeEach
    void setUp() {
        GuestSession session = mock(GuestSession.class);
        when(session.getId()).thenReturn(guestId);
        when(sessions.requireValidSession("token")).thenReturn(session);
        when(consultations.findByIdAndGuestSession_Id(consultationId, guestId))
                .thenReturn(Optional.of(consultation));
        when(consultation.getCaseInputRevision()).thenReturn(2L);
        when(consultation.getFollowUpAnswerRevision()).thenReturn(3L);
        when(consultation.getCategory()).thenReturn(ConsultationCategory.CARD);
        when(consultation.getSituationText()).thenReturn(
                "KB국민카드 개인 신용카드 분실 2026-01-02");
        when(snapshots.capture(consultationId)).thenReturn(new ConfirmedCaseSnapshotData(
                UUID.randomUUID(), consultationId, 2L, 3L,
                List.of(new ConfirmedCaseSnapshotData.Fact("SITUATION", "situationText",
                        "KB국민카드 개인 신용카드 분실 2026-01-02", null, "USER_STATED", null)),
                List.of(), null));
        when(generation.getId()).thenReturn(generationId);
        when(generation.getStatus()).thenReturn(RetrievalGenerationStatus.READY);
        when(generations.current()).thenReturn(Optional.of(generation));
    }

    @Test
    void finds_only_a_document_candidate_from_server_derived_scope() {
        UUID doc = UUID.randomUUID();
        UUID chunk = UUID.randomUUID();
        OfficialEvidenceCandidate candidate = new OfficialEvidenceCandidate(UUID.randomUUID(), chunk,
                doc, generationId, 1, 0.8, null, 0.03, "㈜KB국민카드", "공식 문서", 1,
                null, null, "제1조", "1", "page:1", "https://example.invalid/official", "공식 원문");
        when(retrieval.searchCandidates(any())).thenAnswer(invocation -> {
            OfficialSearchRequest search = invocation.getArgument(0);
            assertThat(search.institution()).isEqualTo("㈜KB국민카드");
            assertThat(search.productType()).isEqualTo("개인 신용카드");
            assertThat(search.query()).doesNotContain("2026-01-02");
            return new OfficialSearchResponse(UUID.randomUUID(), RetrievalStatus.FOUND,
                    false, List.of(), List.of(), List.of(candidate));
        });
        when(corpus.readyChunks(generationId)).thenReturn(List.of(
                new RetrievalCorpusSnapshotService.EligibleChunk(chunk, "공식 원문", doc, 1,
                        java.time.LocalDate.of(2025, 1, 1), null, null, "㈜KB국민카드",
                        "공식 문서", null, "제1조", "1", "page:1",
                        "https://example.invalid/official")));

        var result = service.search(consultationId, "token", request);

        assertThat(result.status()).isEqualTo("FOUND");
        assertThat(result.candidates()).hasSize(1);
        assertThat(result.limitations()).containsExactly("DOCUMENT_CANDIDATE_ONLY");
    }

    @Test
    void rejects_unowned_consultation_before_snapshot_or_retrieval() {
        when(consultations.findByIdAndGuestSession_Id(consultationId, guestId))
                .thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.search(consultationId, "token", request))
                .isInstanceOf(ConsultationNotFoundException.class);
        verifyNoInteractions(snapshots, retrieval);
    }

    @Test
    void rejects_missing_authentication_before_ownership_or_snapshot() {
        when(sessions.requireValidSession("token"))
                .thenThrow(new com.financialhelper.guest.GuestSessionExpiredException());
        assertThatThrownBy(() -> service.search(consultationId, "token", request))
                .isInstanceOf(com.financialhelper.guest.GuestSessionExpiredException.class);
        verifyNoInteractions(consultations, snapshots, retrieval);
    }

    @Test
    void missing_institution_does_not_start_retrieval() {
        when(consultation.getSituationText()).thenReturn("개인 신용카드 분실 2026-01-02");
        when(snapshots.capture(consultationId)).thenReturn(new ConfirmedCaseSnapshotData(
                UUID.randomUUID(), consultationId, 2L, 3L,
                List.of(new ConfirmedCaseSnapshotData.Fact("SITUATION", "situationText",
                        "개인 신용카드 분실 2026-01-02", null, "USER_STATED", null)),
                List.of(), null));
        var result = service.search(consultationId, "token", request);
        assertThat(result.status()).isEqualTo("NEEDS_CLARIFICATION");
        assertThat(result.missingFacts()).contains("INSTITUTION");
        verifyNoInteractions(retrieval);
    }

    @Test
    void stale_revision_before_capture_does_not_create_a_snapshot() {
        when(consultation.getCaseInputRevision()).thenReturn(4L);
        assertThat(service.search(consultationId, "token", request).status()).isEqualTo("STALE");
        verifyNoInteractions(snapshots, retrieval);
    }

    @Test
    void revision_changed_during_search_never_returns_candidates() {
        AtomicInteger reads = new AtomicInteger();
        when(consultations.findByIdAndGuestSession_Id(consultationId, guestId)).thenAnswer(ignored -> {
            if (reads.incrementAndGet() == 2) when(consultation.getFollowUpAnswerRevision()).thenReturn(4L);
            return Optional.of(consultation);
        });
        when(retrieval.searchCandidates(any())).thenReturn(new OfficialSearchResponse(
                UUID.randomUUID(), RetrievalStatus.FOUND, false, List.of(), List.of(), List.of()));
        var result = service.search(consultationId, "token", request);
        assertThat(result.status()).isEqualTo("STALE");
        assertThat(result.candidates()).isEmpty();
    }

    @Test
    void generation_changed_during_search_never_returns_candidates() {
        RetrievalGeneration next = mock(RetrievalGeneration.class);
        when(next.getId()).thenReturn(UUID.randomUUID());
        when(next.getStatus()).thenReturn(RetrievalGenerationStatus.READY);
        when(generations.current()).thenReturn(Optional.of(generation), Optional.of(next));
        when(retrieval.searchCandidates(any())).thenReturn(new OfficialSearchResponse(
                UUID.randomUUID(), RetrievalStatus.FOUND, false, List.of(), List.of(), List.of()));
        assertThat(service.search(consultationId, "token", request).status()).isEqualTo("STALE");
    }

    @Test
    void retrieval_failure_is_not_reported_as_no_match() {
        when(retrieval.searchCandidates(any())).thenReturn(new OfficialSearchResponse(
                UUID.randomUUID(), RetrievalStatus.TEMPORARILY_UNAVAILABLE, true,
                List.of(), List.of("SEMANTIC_BRANCH_UNAVAILABLE", "KEYWORD_BRANCH_UNAVAILABLE"),
                List.of()));
        var result = service.search(consultationId, "token", request);
        assertThat(result.status()).isEqualTo("TEMPORARILY_UNAVAILABLE");
        assertThat(result.degraded()).isTrue();
        assertThat(result.coverageGaps()).contains("SEMANTIC_BRANCH_UNAVAILABLE");
    }

    @Test
    void unsupported_card_product_does_not_search_another_product() {
        when(snapshots.capture(consultationId)).thenReturn(new ConfirmedCaseSnapshotData(
                UUID.randomUUID(), consultationId, 2L, 3L,
                List.of(new ConfirmedCaseSnapshotData.Fact("SITUATION", "situationText",
                        "KB국민카드 체크카드 분실 2026-01-02", null, "USER_STATED", null),
                        new ConfirmedCaseSnapshotData.Fact("SHORT_TEXT", "productType",
                                "CHECK_CARD", null, "USER_ANSWERED", UUID.randomUUID())),
                List.of(), null));
        var result = service.search(consultationId, "token", request);
        assertThat(result.status()).isEqualTo("NO_MATCH");
        assertThat(result.coverageGaps()).containsExactly("CARD_SCOPE_OUT_OF_SCOPE");
        verifyNoInteractions(retrieval);
    }

    @Test
    void candidate_removed_from_approved_ready_corpus_is_not_published() {
        OfficialEvidenceCandidate candidate = new OfficialEvidenceCandidate(UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), generationId, 1, null, 1.0, 0.03,
                "㈜KB국민카드", "문서", 1, null, null, "제1조", "1", "page:1",
                "https://example.invalid/official", "본문");
        when(retrieval.searchCandidates(any())).thenReturn(new OfficialSearchResponse(
                UUID.randomUUID(), RetrievalStatus.FOUND, false, List.of(), List.of(),
                List.of(candidate)));
        when(corpus.readyChunks(generationId)).thenReturn(List.of());
        var result = service.search(consultationId, "token", request);
        assertThat(result.status()).isEqualTo("STALE");
        assertThat(result.candidates()).isEmpty();
    }

    @Test
    void partial_retrieval_failure_is_visible_with_candidates() {
        when(retrieval.searchCandidates(any())).thenReturn(new OfficialSearchResponse(
                UUID.randomUUID(), RetrievalStatus.FOUND, true, List.of(),
                List.of("SEMANTIC_BRANCH_UNAVAILABLE"), List.of()));
        var result = service.search(consultationId, "token", request);
        assertThat(result.status()).isEqualTo("FOUND");
        assertThat(result.degraded()).isTrue();
        assertThat(result.coverageGaps()).containsExactly("SEMANTIC_BRANCH_UNAVAILABLE");
    }
}
