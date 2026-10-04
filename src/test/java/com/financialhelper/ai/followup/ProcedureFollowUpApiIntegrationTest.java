package com.financialhelper.ai.followup;

import com.financialhelper.consultation.Consultation;
import com.financialhelper.consultation.ConsultationCategory;
import com.financialhelper.consultation.ConsultationRepository;
import com.financialhelper.guest.GuestSession;
import com.financialhelper.guest.GuestSessionCookie;
import com.financialhelper.guest.GuestSessionRepository;
import com.financialhelper.guest.GuestSessionTokenService;

import jakarta.servlet.http.Cookie;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.ZoneId;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ProcedureFollowUpApiIntegrationTest {
    @Autowired private MockMvc mockMvc;
    @Autowired private GuestSessionRepository guestSessionRepository;
    @Autowired private ConsultationRepository consultationRepository;
    @Autowired private FollowUpQuestionRepository questionRepository;
    @Autowired private com.financialhelper.retrieval.ConfirmedCaseSnapshotRepository snapshotRepository;
    @Autowired private GuestSessionTokenService tokenService;

    private Consultation consultation;
    private GuestSession guest;

    @AfterEach
    void cleanup() {
        if (consultation != null) {
            snapshotRepository.deleteByConsultation_Id(consultation.getId());
            questionRepository.deleteAll(questionRepository
                    .findByConsultation_IdAndCaseInputRevisionOrderBySequenceNoAsc(
                            consultation.getId(), consultation.getCaseInputRevision()));
            consultationRepository.deleteById(consultation.getId());
        }
        if (guest != null) {
            guestSessionRepository.deleteById(guest.getId());
        }
    }

    @Test
    void cardPrepareUsesBackendProcedureFactsAndDateEscapePath() throws Exception {
        String rawToken = tokenService.generateRawToken();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        guest = guestSessionRepository.save(new GuestSession(
                tokenService.hashToken(rawToken), now, now.plusHours(1)));
        consultation = new Consultation(guest, now);
        consultation.updateCategory(ConsultationCategory.CARD, now.plusSeconds(1));
        consultation.updateSituation(
                "KB국민카드 개인 본인 신용카드를 잃어버렸고 모르는 국내 신용판매 결제가 있어요.",
                now.plusSeconds(2));
        consultation = consultationRepository.saveAndFlush(consultation);
        Cookie cookie = new Cookie(GuestSessionCookie.NAME, rawToken);

        var prepare = mockMvc.perform(post(
                        "/api/v1/consultations/{id}/procedure-follow-up/prepare", consultation.getId())
                .cookie(cookie).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kind").value("question"))
                .andExpect(jsonPath("$.question.factKey").value("reported"))
                .andReturn();

        FollowUpQuestion question = questionRepository
                .findByConsultation_IdAndCaseInputRevisionOrderBySequenceNoAsc(
                        consultation.getId(), consultation.getCaseInputRevision())
                .getFirst();

        mockMvc.perform(put(
                        "/api/v1/consultations/{id}/procedure-follow-up/questions/{questionId}/answer",
                        consultation.getId(), question.getId())
                .cookie(cookie).with(csrf())
                .contentType("application/json")
                .content("{\"answer\":\"FALSE\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.question.factKey").value("incidentDate"))
                .andExpect(jsonPath("$.question.inputType").value("DATE"));

        FollowUpQuestion dateQuestion = questionRepository
                .findByConsultation_IdAndCaseInputRevisionOrderBySequenceNoAsc(
                        consultation.getId(), consultation.getCaseInputRevision())
                .get(1);

        mockMvc.perform(put(
                        "/api/v1/consultations/{id}/procedure-follow-up/questions/{questionId}/answer",
                        consultation.getId(), dateQuestion.getId())
                .cookie(cookie).with(csrf())
                .contentType("application/json")
                .content("{\"answer\":\"" + LocalDate.now(ZoneId.of("Asia/Seoul")).plusDays(1) + "\"}"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(put(
                        "/api/v1/consultations/{id}/follow-up/questions/{questionId}/answer",
                        consultation.getId(), dateQuestion.getId())
                .cookie(cookie).with(csrf())
                .contentType("application/json")
                .content("{\"answer\":\"" + LocalDate.now(ZoneId.of("Asia/Seoul")) + "\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void unknownProductReceivesOneClarificationThenMovesOn() throws Exception {
        String rawToken = tokenService.generateRawToken();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        guest = guestSessionRepository.save(new GuestSession(
                tokenService.hashToken(rawToken), now, now.plusHours(1)));
        consultation = new Consultation(guest, now);
        consultation.updateCategory(ConsultationCategory.CARD, now.plusSeconds(1));
        consultation.updateSituation("카드를 잃어버렸어요.", now.plusSeconds(2));
        consultation = consultationRepository.saveAndFlush(consultation);
        Cookie cookie = new Cookie(GuestSessionCookie.NAME, rawToken);

        mockMvc.perform(post(
                        "/api/v1/consultations/{id}/follow-up/prepare", consultation.getId())
                .cookie(cookie).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.question.inputType").value("INSTITUTION_SELECT"));

        FollowUpQuestion institution = questionRepository
                .findByConsultation_IdAndCaseInputRevisionOrderBySequenceNoAsc(
                        consultation.getId(), consultation.getCaseInputRevision()).getFirst();
        mockMvc.perform(put(
                        "/api/v1/consultations/{id}/follow-up/questions/{questionId}/answer",
                        consultation.getId(), institution.getId())
                .cookie(cookie).with(csrf())
                .contentType("application/json")
                .content("{\"answer\":\"KB_KOOKMIN_CARD\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.question.inputType").value("ENUM_SELECT"));

        FollowUpQuestion product = questionRepository
                .findByConsultation_IdAndCaseInputRevisionOrderBySequenceNoAsc(
                        consultation.getId(), consultation.getCaseInputRevision()).get(1);
        mockMvc.perform(put(
                        "/api/v1/consultations/{id}/follow-up/questions/{questionId}/answer",
                        consultation.getId(), product.getId())
                .cookie(cookie).with(csrf())
                .contentType("application/json")
                .content("{\"answer\":\"UNKNOWN\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.question.inputType").value("ENUM_SELECT"));

        FollowUpQuestion clarification = questionRepository
                .findByConsultation_IdAndCaseInputRevisionOrderBySequenceNoAsc(
                        consultation.getId(), consultation.getCaseInputRevision()).get(2);
        org.assertj.core.api.Assertions.assertThat(clarification.getQuestionIntent())
                .isEqualTo("CLARIFY_PRODUCT");

        mockMvc.perform(put(
                        "/api/v1/consultations/{id}/follow-up/questions/{questionId}/answer",
                        consultation.getId(), clarification.getId())
                .cookie(cookie).with(csrf())
                .contentType("application/json")
                .content("{\"answer\":\"UNKNOWN\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.question.inputType").value("YES_NO_UNKNOWN"));
    }

    @Test
    void unknownInstitutionStopsOnlyWhenItsProcedureBindingLeavesNoActionCandidateAndCanRecover() throws Exception {
        String rawToken = tokenService.generateRawToken();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        guest = guestSessionRepository.save(new GuestSession(
                tokenService.hashToken(rawToken), now, now.plusHours(1)));
        consultation = new Consultation(guest, now);
        consultation.updateCategory(ConsultationCategory.CARD, now.plusSeconds(1));
        consultation.updateSituation("카드를 잃어버렸어요.", now.plusSeconds(2));
        consultation = consultationRepository.saveAndFlush(consultation);
        Cookie cookie = new Cookie(GuestSessionCookie.NAME, rawToken);

        mockMvc.perform(post("/api/v1/consultations/{id}/procedure-follow-up/prepare", consultation.getId())
                        .cookie(cookie).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.question.factKey").value("institution"));
        FollowUpQuestion institution = currentQuestions().getFirst();

        mockMvc.perform(put("/api/v1/consultations/{id}/procedure-follow-up/questions/{questionId}/answer",
                        consultation.getId(), institution.getId())
                        .cookie(cookie).with(csrf()).contentType("application/json")
                        .content("{\"answer\":\"UNKNOWN\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kind").value("question"))
                .andExpect(jsonPath("$.question.questionIntent").value("CLARIFY_INSTITUTION"));

        FollowUpQuestion clarification = currentQuestions().get(1);
        mockMvc.perform(put("/api/v1/consultations/{id}/procedure-follow-up/questions/{questionId}/answer",
                        consultation.getId(), clarification.getId())
                        .cookie(cookie).with(csrf()).contentType("application/json")
                        .content("{\"answer\":\"UNKNOWN\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kind").value("insufficient_information"))
                .andExpect(jsonPath("$.blockingFact.key").value("institution"))
                .andExpect(jsonPath("$.blockingFact.label").isNotEmpty())
                .andExpect(jsonPath("$.question.id").value(clarification.getId().toString()));

        org.assertj.core.api.Assertions.assertThat(currentQuestions()).hasSize(2);

        mockMvc.perform(put("/api/v1/consultations/{id}/procedure-follow-up/questions/{questionId}/answer",
                        consultation.getId(), clarification.getId())
                        .cookie(cookie).with(csrf()).contentType("application/json")
                        .content("{\"answer\":\"KB_KOOKMIN_CARD\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kind").value("question"))
                .andExpect(jsonPath("$.question.factKey").value("productType"));
    }

    @Test
    void explicitUnsupportedCardBoundaryStopsAndSameQuestionCanRecover() throws Exception {
        String rawToken = tokenService.generateRawToken();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        guest = guestSessionRepository.save(new GuestSession(tokenService.hashToken(rawToken), now, now.plusHours(1)));
        consultation = new Consultation(guest, now);
        consultation.updateCategory(ConsultationCategory.CARD, now.plusSeconds(1));
        consultation.updateSituation("카드를 잃어버렸어요.", now.plusSeconds(2));
        consultation = consultationRepository.saveAndFlush(consultation);
        Cookie cookie = new Cookie(GuestSessionCookie.NAME, rawToken);

        mockMvc.perform(post("/api/v1/consultations/{id}/procedure-follow-up/prepare", consultation.getId())
                        .cookie(cookie).with(csrf()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.question.factKey").value("institution"));
        FollowUpQuestion institution = questionRepository
                .findByConsultation_IdAndCaseInputRevisionOrderBySequenceNoAsc(consultation.getId(), consultation.getCaseInputRevision()).getFirst();

        mockMvc.perform(put("/api/v1/consultations/{id}/procedure-follow-up/questions/{questionId}/answer", consultation.getId(), institution.getId())
                        .cookie(cookie).with(csrf()).contentType("application/json").content("{\"answer\":\"OTHER\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.kind").value("unsupported"))
                .andExpect(jsonPath("$.unsupportedReason").value("CARD_INSTITUTION_UNSUPPORTED"))
                .andExpect(jsonPath("$.question.id").value(institution.getId().toString()))
                .andExpect(jsonPath("$.totalQuestions").value(1));

        mockMvc.perform(put("/api/v1/consultations/{id}/procedure-follow-up/questions/{questionId}/answer", consultation.getId(), institution.getId())
                        .cookie(cookie).with(csrf()).contentType("application/json").content("{\"answer\":\"KB_KOOKMIN_CARD\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.kind").value("question"))
                .andExpect(jsonPath("$.question.factKey").value("productType"));
    }

    @Test
    void unsupportedProductKeepsQuestionAndSupportedCorrectionRecovers() throws Exception {
        BoundaryFixture fixture = boundaryFixture("productType", "ENUM_SELECT",
                "{\"options\":[{\"value\":\"PERSONAL_CREDIT_CARD\",\"label\":\"신용\",\"description\":\"\"},{\"value\":\"OTHER\",\"label\":\"기타\",\"description\":\"\"}]}");
        answer(fixture, "OTHER").andExpect(jsonPath("$.kind").value("unsupported"))
                .andExpect(jsonPath("$.unsupportedReason").value("CARD_PRODUCT_UNSUPPORTED"))
                .andExpect(jsonPath("$.question.id").value(fixture.question().getId().toString()));
        answer(fixture, "PERSONAL_CREDIT_CARD").andExpect(jsonPath("$.kind").value("question"));
    }

    @Test
    void overseasScopeKeepsItsCurrentQuestion() throws Exception {
        BoundaryFixture domestic = boundaryFixture("domestic", "YES_NO_UNKNOWN",
                "{\"options\":[{\"value\":\"TRUE\",\"label\":\"국내\",\"description\":\"\"},{\"value\":\"FALSE\",\"label\":\"해외\",\"description\":\"\"}]}");
        answer(domestic, "FALSE").andExpect(jsonPath("$.kind").value("unsupported"))
                .andExpect(jsonPath("$.unsupportedReason").value("CARD_TRANSACTION_SCOPE_UNSUPPORTED"));
    }

    @Test
    void unsupportedTransactionTypeKeepsItsCurrentQuestion() throws Exception {
        BoundaryFixture transaction = boundaryFixture("transactionType", "ENUM_SELECT",
                "{\"options\":[{\"value\":\"CREDIT_SALE\",\"label\":\"결제\",\"description\":\"\"},{\"value\":\"CARD_LOAN\",\"label\":\"대출\",\"description\":\"\"}]}");
        answer(transaction, "CARD_LOAN").andExpect(jsonPath("$.kind").value("unsupported"))
                .andExpect(jsonPath("$.unsupportedReason").value("CARD_TRANSACTION_SCOPE_UNSUPPORTED"))
                .andExpect(jsonPath("$.question.id").value(transaction.question().getId().toString()));
    }

    private BoundaryFixture boundaryFixture(String factKey, String inputType, String options) {
        String rawToken = tokenService.generateRawToken(); OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        guest = guestSessionRepository.save(new GuestSession(tokenService.hashToken(rawToken), now, now.plusHours(1)));
        consultation = new Consultation(guest, now); consultation.updateCategory(ConsultationCategory.CARD, now);
        consultation.updateSituation("카드를 잃어버렸어요.", now.plusSeconds(1)); consultation = consultationRepository.saveAndFlush(consultation);
        FollowUpQuestion question = questionRepository.save(new FollowUpQuestion(consultation, consultation.getCaseInputRevision(), 1,
                "질문", "", options, "BACKEND_PROCEDURE", now, factKey, inputType, true, "TEST"));
        return new BoundaryFixture(new Cookie(GuestSessionCookie.NAME, rawToken), question);
    }

    private org.springframework.test.web.servlet.ResultActions answer(BoundaryFixture fixture, String value) throws Exception {
        return mockMvc.perform(put("/api/v1/consultations/{id}/procedure-follow-up/questions/{questionId}/answer", consultation.getId(), fixture.question().getId())
                .cookie(fixture.cookie()).with(csrf()).contentType("application/json").content("{\"answer\":\"" + value + "\"}"));
    }

    private java.util.List<FollowUpQuestion> currentQuestions() {
        return questionRepository.findByConsultation_IdAndCaseInputRevisionOrderBySequenceNoAsc(
                consultation.getId(), consultation.getCaseInputRevision());
    }

    private record BoundaryFixture(Cookie cookie, FollowUpQuestion question) {}
}
