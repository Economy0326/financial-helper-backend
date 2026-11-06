package com.financialhelper.ai.information;

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
import com.financialhelper.retrieval.ConsultationOfficialInformationResponse;
import com.financialhelper.retrieval.ConsultationOfficialInformationService;
import com.financialhelper.retrieval.KureRuntimeHttpClient;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doThrow;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Real HTTP security and PostgreSQL persistence; retrieval, AI and law clients are isolated. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class InformationReportApiIntegrationTest {
    private static final String URL = "/api/v1/consultations/{id}/information-report";
    @Autowired MockMvc mvc;
    @Autowired GuestSessionTokenService tokens;
    @Autowired GuestSessionRepository guests;
    @Autowired ConsultationRepository consultations;
    @Autowired InformationReportRepository reports;
    @MockitoBean ConsultationOfficialInformationService search;
    @MockitoBean InformationEvidenceSnapshotService snapshots;
    @MockitoBean InformationExplanationService explanation;
    @MockitoBean OpenAiStructuredClient openAi;
    @MockitoBean KureRuntimeHttpClient kure;
    @MockitoBean KoreanLawOpenApiClient law;
    private final List<UUID> consultationIds = new ArrayList<>();
    private final List<UUID> guestIds = new ArrayList<>();

    @AfterEach
    void cleanCreatedRows() {
        for (UUID id : consultationIds) {
            reports.findFirstByConsultation_IdOrderByGeneratedAtDesc(id).ifPresent(reports::delete);
            consultations.deleteById(id);
        }
        for (UUID id : guestIds) guests.deleteById(id);
        verifyNoInteractions(openAi, kure, law);
    }

    @Test
    void csrfAndOwnershipGuardPersistedReport() throws Exception {
        Fixture owner = fixture();
        Fixture other = fixture();
        stubReady(owner);
        mvc.perform(post(URL + "/prepare", owner.id()).cookie(owner.cookie())
                        .contentType("application/json").content(body(owner.revision())))
                .andExpect(status().isForbidden());
        mvc.perform(post(URL + "/prepare", owner.id()).with(csrf())
                        .contentType("application/json").content(body(owner.revision())))
                .andExpect(status().isUnauthorized());
        mvc.perform(post(URL + "/prepare", owner.id()).with(csrf()).cookie(other.cookie())
                        .contentType("application/json").content(body(owner.revision())))
                .andExpect(status().isNotFound());
        assertThat(reports.findFirstByConsultation_IdOrderByGeneratedAtDesc(owner.id())).isEmpty();
    }

    @Test
    void preparesAndReloadsStoredCandidateWithoutExternalClients() throws Exception {
        Fixture owner = fixture();
        stubReady(owner);
        mvc.perform(post(URL + "/prepare", owner.id()).with(csrf()).cookie(owner.cookie())
                        .contentType("application/json").content(body(owner.revision())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("READY_CANDIDATES"))
                .andExpect(jsonPath("$.explanations[0].mode").value("AI_SELECTED_TEMPLATE"));
        assertThat(reports.findFirstByConsultation_IdOrderByGeneratedAtDesc(owner.id())).isPresent();
        mvc.perform(get(URL, owner.id()).cookie(owner.cookie()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("READY_CANDIDATES"))
                .andExpect(jsonPath("$.explanations[0].easyText").value(
                        "공식 자료에서 확인한 내용(공통 정보): 원문 근거 이 자료가 내 상황에 적용되는지는 아직 확인되지 않았습니다."));
    }

    @Test
    void providerFailurePersistsOnlyExtractiveFallbackAndReloadsIt() throws Exception {
        Fixture owner = fixture();
        stubReady(owner);
        doThrow(new com.financialhelper.ai.AiProviderException("synthetic timeout"))
                .when(explanation).select(any());
        mvc.perform(post(URL + "/prepare", owner.id()).with(csrf()).cookie(owner.cookie())
                        .contentType("application/json").content(body(owner.revision())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.explanations[0].mode").value("EXTRACTIVE_FALLBACK"));
        mvc.perform(get(URL, owner.id()).cookie(owner.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.explanations[0].mode").value("EXTRACTIVE_FALLBACK"));
    }

    @Test
    void changedReviewOnReloadHidesStoredEvidence() throws Exception {
        Fixture owner = fixture();
        stubReady(owner);
        mvc.perform(post(URL + "/prepare", owner.id()).with(csrf()).cookie(owner.cookie())
                        .contentType("application/json").content(body(owner.revision())))
                .andExpect(status().isOk());
        doThrow(new IllegalStateException("synthetic review revoked"))
                .when(snapshots).assemble(any(), any(), any(), any());
        mvc.perform(get(URL, owner.id()).cookie(owner.cookie()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("STALE"))
                .andExpect(jsonPath("$.selectedEvidence").isEmpty())
                .andExpect(jsonPath("$.explanations").isEmpty());
    }

    private void stubReady(Fixture fixture) {
        UUID documentId = UUID.randomUUID();
        UUID chunkId = UUID.randomUUID();
        UUID generationId = UUID.randomUUID();
        var evidence = new InformationEvidenceSnapshot.Evidence(documentId, 1, "a".repeat(64),
                chunkId, UUID.randomUUID(), "COMMON", "합성 기관", "합성 문서", "https://example.invalid/source",
                null, null, "loc", "원문 근거", null, null, LocalDate.of(2025, 1, 1), null,
                List.of(), List.of());
        var snapshot = new InformationEvidenceSnapshot(fixture.id(), fixture.revision(), 0,
                "CARD_LOSS_UNAUTHORIZED_USE", generationId, List.of(), List.of(), List.of(),
                List.of(evidence), "CURRENT_READY_SOURCE_AND_HASH", "V38_SCOPE_REVIEWED", "NOT_DETERMINED");
        var response = new ConsultationOfficialInformationResponse(fixture.id(), fixture.revision(), 0,
                "CARD_LOSS_UNAUTHORIZED_USE", null, "FOUND", false, List.of(), List.of(), List.of(), List.of());
        when(search.searchUsingConfirmed(any(), any(), any(), any())).thenReturn(response);
        when(snapshots.assemble(any(), any(), any(), any())).thenReturn(snapshot);
        when(explanation.select(snapshot)).thenReturn(List.of(evidence));
    }

    private Fixture fixture() {
        String token = tokens.generateRawToken();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        GuestSession guest = guests.saveAndFlush(new GuestSession(tokens.hashToken(token), now, now.plusHours(1)));
        guestIds.add(guest.getId());
        Consultation consultation = new Consultation(guest, now);
        consultation.updateCategory(ConsultationCategory.CARD,
                ConsultationScenario.CARD_LOSS_UNAUTHORIZED_USE, now.plusSeconds(1));
        consultation.updateSituation("합성 카드 분실 상황", now.plusSeconds(2));
        consultation = consultations.saveAndFlush(consultation);
        consultationIds.add(consultation.getId());
        return new Fixture(consultation.getId(), consultation.getCaseInputRevision(),
                new Cookie(GuestSessionCookie.NAME, token));
    }

    private static String body(long revision) { return "{\"expectedCaseInputRevision\":" + revision + "}"; }
    private record Fixture(UUID id, long revision, Cookie cookie) {}
}
