package com.financialhelper.retrieval;

import com.financialhelper.ai.OpenAiStructuredClient;
import com.financialhelper.consultation.Consultation;
import com.financialhelper.consultation.ConsultationCategory;
import com.financialhelper.consultation.ConsultationRepository;
import com.financialhelper.consultation.ConsultationScenario;
import com.financialhelper.guest.GuestSession;
import com.financialhelper.guest.GuestSessionCookie;
import com.financialhelper.guest.GuestSessionRepository;
import com.financialhelper.guest.GuestSessionTokenService;
import com.financialhelper.law.KoreanLawOpenApiClient;
import com.financialhelper.source.ActiveRetrievalGenerationPersistenceService;
import com.financialhelper.source.RetrievalGeneration;
import com.financialhelper.source.RetrievalGenerationStatus;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Real Security, consultation ownership, snapshot and repositories; remote retrieval is isolated. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ConsultationOfficialInformationApiIntegrationTest {
    private static final String URL = "/api/v1/consultations/{id}/official-information/search";
    private static final String CARD_TEXT = "KB국민카드 개인 신용카드 분실 2026-01-02";

    @Autowired private MockMvc mvc;
    @Autowired private GuestSessionTokenService tokens;
    @Autowired private GuestSessionRepository guests;
    @Autowired private ConsultationRepository consultations;
    @Autowired private ConfirmedCaseSnapshotRepository snapshots;

    @MockitoBean private OfficialSourceRetrievalService retrieval;
    @MockitoBean private ActiveRetrievalGenerationPersistenceService generations;
    @MockitoBean private RetrievalCorpusSnapshotService corpus;
    @MockitoBean private ReviewedSourceApplicabilityService applicability;
    @MockitoBean private KureRuntimeHttpClient kure;
    @MockitoBean private KoreanLawOpenApiClient law;
    @MockitoBean private OpenAiStructuredClient openAi;

    private final List<UUID> consultationIds = new ArrayList<>();
    private final List<UUID> guestIds = new ArrayList<>();

    @AfterEach
    void removeOnlyCreatedRows() {
        for (UUID id : consultationIds) {
            snapshots.deleteByConsultation_Id(id);
            consultations.deleteById(id);
        }
        for (UUID id : guestIds) guests.deleteById(id);
        verifyNoInteractions(kure, law, openAi);
    }

    @Test
    void requiresCsrfBeforeCreatingSnapshot() throws Exception {
        Fixture fixture = consultation(CARD_TEXT);
        mvc.perform(post(URL, fixture.id()).cookie(fixture.cookie())
                        .contentType("application/json").content(body(fixture.revision())))
                .andExpect(status().isForbidden());
        assertThat(snapshots.findByConsultation_IdAndCaseInputRevisionAndFollowUpAnswerRevision(
                fixture.id(), fixture.revision(), 0L)).isEmpty();
        verifyNoInteractions(retrieval);
    }

    @Test
    void requiresSessionAndConsultationOwnershipBeforeSnapshot() throws Exception {
        Fixture fixture = consultation(CARD_TEXT);
        mvc.perform(post(URL, fixture.id()).with(csrf())
                        .contentType("application/json").content(body(fixture.revision())))
                .andExpect(status().isUnauthorized());
        Fixture other = consultation(CARD_TEXT);
        mvc.perform(post(URL, fixture.id()).with(csrf()).cookie(other.cookie())
                        .contentType("application/json").content(body(fixture.revision())))
                .andExpect(status().isNotFound());
        assertThat(snapshots.findByConsultation_IdAndCaseInputRevisionAndFollowUpAnswerRevision(
                fixture.id(), fixture.revision(), 0L)).isEmpty();
        verifyNoInteractions(retrieval);
    }

    @Test
    void staleRequestRevisionDoesNotCreateSnapshot() throws Exception {
        Fixture fixture = consultation(CARD_TEXT);
        mvc.perform(post(URL, fixture.id()).with(csrf()).cookie(fixture.cookie())
                        .contentType("application/json").content(body(fixture.revision() + 1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("STALE"))
                .andExpect(jsonPath("$.candidates.length()").value(0));
        assertThat(snapshots.findByConsultation_IdAndCaseInputRevisionAndFollowUpAnswerRevision(
                fixture.id(), fixture.revision(), 0L)).isEmpty();
        verifyNoInteractions(retrieval);
    }

    @Test
    void missingInstitutionWithoutCommonReviewReturnsNoMatch() throws Exception {
        Fixture fixture = consultation("개인 신용카드 분실 2026-01-02");
        readyGeneration();
        when(retrieval.searchReviewedCandidates(any(), any())).thenReturn(new OfficialSearchResponse(
                UUID.randomUUID(), RetrievalStatus.NO_MATCH, false, List.of(),
                List.of("NO_APPROVED_READY_CORPUS_FOR_SCOPE"), List.of()));
        mvc.perform(post(URL, fixture.id()).with(csrf()).cookie(fixture.cookie())
                        .contentType("application/json").content(body(fixture.revision())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("NO_MATCH"))
                .andExpect(jsonPath("$.missingFacts[0]").value("INSTITUTION"));
        assertThat(snapshots.findByConsultation_IdAndCaseInputRevisionAndFollowUpAnswerRevision(
                fixture.id(), fixture.revision(), 0L)).isPresent();
        verify(retrieval).searchReviewedCandidates(any(), any());
    }

    @Test
    void approvedCommonCandidateIsFoundWithoutInstitutionButKeepsMissingFact() throws Exception {
        Fixture fixture = consultation("개인 신용카드 분실 2026-01-02");
        RetrievalGeneration generation = readyGeneration();
        OfficialEvidenceCandidate candidate = candidate(generation.getId());
        var match = new ReviewedSourceApplicabilityService.Match(UUID.randomUUID(), "COMMON",
                null, "개인 신용카드", java.time.LocalDate.of(2025, 1, 1), null,
                List.of("page:1#condition"), List.of("page:1#exception"));
        when(applicability.eligible(any(), any())).thenReturn(java.util.Map.of(
                candidate.sourceChunkId(), match));
        when(retrieval.searchReviewedCandidates(any(), any())).thenReturn(new OfficialSearchResponse(
                UUID.randomUUID(), RetrievalStatus.FOUND, false, List.of(), List.of(),
                List.of(candidate)));
        when(corpus.readyChunks(generation.getId())).thenReturn(List.of(
                new RetrievalCorpusSnapshotService.EligibleChunk(candidate.sourceChunkId(),
                        "공식 원문", candidate.sourceDocumentId(), 1,
                        java.time.LocalDate.of(2025, 1, 1), null, null,
                        "공식 기관", "공통 문서", null, "제1조", "1", "page:1",
                        "https://example.invalid/official")));
        mvc.perform(post(URL, fixture.id()).with(csrf()).cookie(fixture.cookie())
                        .contentType("application/json").content(body(fixture.revision())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FOUND"))
                .andExpect(jsonPath("$.missingFacts[0]").value("INSTITUTION"))
                .andExpect(jsonPath("$.candidates[0].scopeKind").value("COMMON"))
                .andExpect(jsonPath("$.candidates[0].conditionLocators[0]").value("page:1#condition"));
    }

    @Test
    void commonCandidateDoesNotClaimInstitutionCorpusCoverage() throws Exception {
        Fixture fixture = consultation(CARD_TEXT);
        RetrievalGeneration generation = readyGeneration();
        OfficialEvidenceCandidate candidate = candidate(generation.getId());
        var match = new ReviewedSourceApplicabilityService.Match(UUID.randomUUID(), "COMMON",
                null, "개인 신용카드", java.time.LocalDate.of(2025, 1, 1), null,
                List.of(), List.of());
        when(applicability.eligible(any(), any())).thenReturn(java.util.Map.of(
                candidate.sourceChunkId(), match));
        when(retrieval.searchReviewedCandidates(any(), any())).thenReturn(new OfficialSearchResponse(
                UUID.randomUUID(), RetrievalStatus.FOUND, false, List.of(), List.of(),
                List.of(candidate)));
        when(corpus.readyChunks(generation.getId())).thenReturn(List.of(
                new RetrievalCorpusSnapshotService.EligibleChunk(candidate.sourceChunkId(),
                        "공식 원문", candidate.sourceDocumentId(), 1,
                        java.time.LocalDate.of(2025, 1, 1), null, null,
                        "공식 기관", "공통 문서", null, "제1조", "1", "page:1",
                        "https://example.invalid/official")));
        mvc.perform(post(URL, fixture.id()).with(csrf()).cookie(fixture.cookie())
                        .contentType("application/json").content(body(fixture.revision())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FOUND"))
                .andExpect(jsonPath("$.coverageGaps[0]").value("NO_APPROVED_INSTITUTION_CORPUS"))
                .andExpect(jsonPath("$.candidates[0].scopeKind").value("COMMON"));
    }

    @Test
    void revisionChangedDuringRetrievalDoesNotPublishCandidates() throws Exception {
        Fixture fixture = consultation(CARD_TEXT);
        readyGeneration();
        when(retrieval.searchReviewedCandidates(any(), any())).thenAnswer(invocation -> {
            Consultation current = consultations.findById(fixture.id()).orElseThrow();
            current.updateSituation(CARD_TEXT + " 이후 정정", OffsetDateTime.now(ZoneOffset.UTC));
            consultations.saveAndFlush(current);
            return new OfficialSearchResponse(UUID.randomUUID(), RetrievalStatus.FOUND,
                    false, List.of(), List.of(), List.of());
        });
        mvc.perform(post(URL, fixture.id()).with(csrf()).cookie(fixture.cookie())
                        .contentType("application/json").content(body(fixture.revision())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("STALE"))
                .andExpect(jsonPath("$.candidates.length()").value(0));
    }

    @Test
    void generationChangedDuringRetrievalDoesNotPublishCandidates() throws Exception {
        Fixture fixture = consultation(CARD_TEXT);
        RetrievalGeneration before = readyGeneration();
        RetrievalGeneration after = mock(RetrievalGeneration.class);
        when(after.getId()).thenReturn(UUID.randomUUID());
        when(after.getStatus()).thenReturn(RetrievalGenerationStatus.READY);
        when(generations.current()).thenReturn(Optional.of(before), Optional.of(after));
        when(retrieval.searchReviewedCandidates(any(), any())).thenReturn(new OfficialSearchResponse(
                UUID.randomUUID(), RetrievalStatus.FOUND, false, List.of(), List.of(), List.of()));
        mvc.perform(post(URL, fixture.id()).with(csrf()).cookie(fixture.cookie())
                        .contentType("application/json").content(body(fixture.revision())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("STALE"));
    }

    @Test
    void candidateRemovedFromApprovedCorpusIsNotPublished() throws Exception {
        Fixture fixture = consultation(CARD_TEXT);
        RetrievalGeneration generation = readyGeneration();
        OfficialEvidenceCandidate candidate = candidate(generation.getId());
        when(retrieval.searchReviewedCandidates(any(), any())).thenReturn(new OfficialSearchResponse(
                UUID.randomUUID(), RetrievalStatus.FOUND, false, List.of(), List.of(),
                List.of(candidate)));
        when(corpus.readyChunks(generation.getId())).thenReturn(List.of());
        mvc.perform(post(URL, fixture.id()).with(csrf()).cookie(fixture.cookie())
                        .contentType("application/json").content(body(fixture.revision())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("STALE"))
                .andExpect(jsonPath("$.candidates.length()").value(0));
    }

    @Test
    void candidateIsOnlyAFoundDocumentAndScopeComesFromConfirmedFacts() throws Exception {
        Fixture fixture = consultation(CARD_TEXT);
        RetrievalGeneration generation = readyGeneration();
        OfficialEvidenceCandidate candidate = candidate(generation.getId());
        when(retrieval.searchReviewedCandidates(any(), any())).thenAnswer(invocation -> {
            OfficialSearchRequest search = invocation.getArgument(0);
            assertThat(search.institution()).isEqualTo("㈜KB국민카드");
            assertThat(search.productType()).isEqualTo("개인 신용카드");
            return new OfficialSearchResponse(UUID.randomUUID(), RetrievalStatus.FOUND,
                    false, List.of(), List.of(), List.of(candidate));
        });
        when(corpus.readyChunks(generation.getId())).thenReturn(List.of(
                new RetrievalCorpusSnapshotService.EligibleChunk(candidate.sourceChunkId(),
                        "공식 원문", candidate.sourceDocumentId(), 1,
                        java.time.LocalDate.of(2025, 1, 1), null, null,
                        "㈜KB국민카드", "공식 문서", null, "제1조", "1", "page:1",
                        "https://example.invalid/official")));
        when(applicability.eligible(any(), any())).thenReturn(java.util.Map.of(
                candidate.sourceChunkId(), new ReviewedSourceApplicabilityService.Match(
                        UUID.randomUUID(), "INSTITUTION_SPECIFIC", "㈜KB국민카드",
                        "개인 신용카드", java.time.LocalDate.of(2025, 1, 1), null,
                        List.of(), List.of())));
        mvc.perform(post(URL, fixture.id()).with(csrf()).cookie(fixture.cookie())
                        .contentType("application/json").content(body(fixture.revision())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FOUND"))
                .andExpect(jsonPath("$.limitations[0]").value("DOCUMENT_CANDIDATE_ONLY"))
                .andExpect(jsonPath("$.candidates.length()").value(1));
    }

    @Test
    void retrievalFailureIsNotReportedAsNoMatch() throws Exception {
        Fixture fixture = consultation(CARD_TEXT);
        readyGeneration();
        when(retrieval.searchReviewedCandidates(any(), any())).thenReturn(new OfficialSearchResponse(
                UUID.randomUUID(), RetrievalStatus.TEMPORARILY_UNAVAILABLE, true,
                List.of(), List.of("SEMANTIC_BRANCH_UNAVAILABLE", "KEYWORD_BRANCH_UNAVAILABLE"),
                List.of()));
        mvc.perform(post(URL, fixture.id()).with(csrf()).cookie(fixture.cookie())
                        .contentType("application/json").content(body(fixture.revision())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("TEMPORARILY_UNAVAILABLE"))
                .andExpect(jsonPath("$.degraded").value(true))
                .andExpect(jsonPath("$.candidates.length()").value(0));
    }

    private static OfficialEvidenceCandidate candidate(UUID generationId) {
        return new OfficialEvidenceCandidate(UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), generationId, 1, 0.8, null, 0.03,
                "㈜KB국민카드", "공식 문서", 1, null, null, "제1조", "1",
                "page:1", "https://example.invalid/official", "공식 원문");
    }

    private RetrievalGeneration readyGeneration() {
        RetrievalGeneration generation = mock(RetrievalGeneration.class);
        when(generation.getId()).thenReturn(UUID.randomUUID());
        when(generation.getStatus()).thenReturn(RetrievalGenerationStatus.READY);
        when(generations.current()).thenReturn(Optional.of(generation));
        return generation;
    }

    private Fixture consultation(String situation) {
        String rawToken = tokens.generateRawToken();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        GuestSession guest = guests.saveAndFlush(new GuestSession(
                tokens.hashToken(rawToken), now, now.plusHours(1)));
        guestIds.add(guest.getId());
        Consultation consultation = new Consultation(guest, now);
        consultation.updateCategory(ConsultationCategory.CARD,
                ConsultationScenario.CARD_LOSS_UNAUTHORIZED_USE, now.plusSeconds(1));
        consultation.updateSituation(situation, now.plusSeconds(2));
        consultation = consultations.saveAndFlush(consultation);
        consultationIds.add(consultation.getId());
        return new Fixture(consultation.getId(), consultation.getCaseInputRevision(),
                new Cookie(GuestSessionCookie.NAME, rawToken));
    }

    private static String body(long revision) {
        return "{\"expectedCaseInputRevision\":" + revision + "}";
    }

    private record Fixture(UUID id, long revision, Cookie cookie) {}
}
