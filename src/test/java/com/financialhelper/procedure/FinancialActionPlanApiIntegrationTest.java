package com.financialhelper.procedure;

import com.financialhelper.consultation.Consultation;
import com.financialhelper.consultation.ConsultationCategory;
import com.financialhelper.consultation.ConsultationRepository;
import com.financialhelper.consultation.ConsultationScenario;
import com.financialhelper.guest.GuestSession;
import com.financialhelper.guest.GuestSessionCookie;
import com.financialhelper.guest.GuestSessionRepository;
import com.financialhelper.guest.GuestSessionTokenService;
import com.financialhelper.retrieval.ConfirmedCaseSnapshot;
import com.financialhelper.retrieval.ConfirmedCaseSnapshotRepository;

import jakarta.servlet.http.Cookie;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.json.JsonMapper;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class FinancialActionPlanApiIntegrationTest {
    @Autowired private MockMvc mockMvc;
    @Autowired private GuestSessionTokenService tokenService;
    @Autowired private GuestSessionRepository guestSessionRepository;
    @Autowired private ConsultationRepository consultationRepository;
    @Autowired private ProcedureVersionRepository procedureVersionRepository;
    @Autowired private ConfirmedCaseSnapshotRepository snapshotRepository;
    @Autowired private FinancialActionPlanRepository planRepository;
    @Autowired private JsonMapper jsonMapper;

    @Test
    void situationRevisionDoesNotExposePreviousFinancialActionPlan() throws Exception {
        Fixture fixture = createConsultation();
        FinancialActionPlan oldPlan = savePlan(fixture);
        long oldCaseRevision = fixture.consultation().getCaseInputRevision();
        long oldAnswerRevision = fixture.consultation().getFollowUpAnswerRevision();

        fixture.consultation().updateSituation("카드를 잃어버렸고 결제 내역도 확인했어요.", now());
        consultationRepository.saveAndFlush(fixture.consultation());
        assertThat(fixture.consultation().getCaseInputRevision()).isEqualTo(oldCaseRevision + 1);
        assertThat(fixture.consultation().getFollowUpAnswerRevision()).isZero();
        assertThat(planRepository.findById(oldPlan.getId())).isPresent();

        assertNoStalePlan(fixture, oldCaseRevision, oldAnswerRevision);
    }

    @Test
    void followUpAnswerRevisionDoesNotExposePreviousFinancialActionPlan() throws Exception {
        Fixture fixture = createConsultation();
        fixture.consultation().recordFollowUpAnswerChanged(now());
        consultationRepository.saveAndFlush(fixture.consultation());
        FinancialActionPlan oldPlan = savePlan(fixture);
        long oldCaseRevision = fixture.consultation().getCaseInputRevision();
        long oldAnswerRevision = fixture.consultation().getFollowUpAnswerRevision();

        // FollowUpPersistenceService.saveAnswer invokes this domain transition when an answer changes.
        fixture.consultation().recordFollowUpAnswerChanged(now());
        consultationRepository.saveAndFlush(fixture.consultation());
        assertThat(fixture.consultation().getCaseInputRevision()).isEqualTo(oldCaseRevision);
        assertThat(fixture.consultation().getFollowUpAnswerRevision()).isEqualTo(oldAnswerRevision + 1);
        assertThat(planRepository.findById(oldPlan.getId())).isPresent();

        assertNoStalePlan(fixture, oldCaseRevision, oldAnswerRevision);
    }

    @Test
    void getReturnsPlanForCurrentRevisionWhenOlderPlanAlsoExists() throws Exception {
        Fixture fixture = createConsultation();
        FinancialActionPlan oldPlan = savePlan(fixture);
        fixture.consultation().updateSituation("카드를 잃어버렸고 결제 내역도 확인했어요.", now());
        consultationRepository.saveAndFlush(fixture.consultation());
        FinancialActionPlan currentPlan = savePlan(fixture);

        MvcResult response = getPlan(fixture);
        assertThat(response.getResponse().getStatus()).isEqualTo(200);
        FinancialActionPlanData body = jsonMapper.readValue(
                response.getResponse().getContentAsString(), FinancialActionPlanData.class);
        assertThat(body.id()).isEqualTo(currentPlan.getId()).isNotEqualTo(oldPlan.getId());
        assertThat(body.caseInputRevision()).isEqualTo(fixture.consultation().getCaseInputRevision());
        assertThat(body.followUpAnswerRevision()).isEqualTo(fixture.consultation().getFollowUpAnswerRevision());
    }

    @Test
    void getWithoutAnyPlanPreservesExistingErrorContract() throws Exception {
        Fixture fixture = createConsultation();

        MvcResult response = getPlan(fixture);
        assertThat(response.getResponse().getStatus()).isEqualTo(500);
        assertThat(response.getResponse().getContentAsString())
                .contains("INTERNAL_SERVER_ERROR");
    }

    private void assertNoStalePlan(Fixture fixture, long oldCaseRevision,
                                   long oldAnswerRevision) throws Exception {
        MvcResult response = getPlan(fixture);
        int status = response.getResponse().getStatus();
        if (status == 200) {
            FinancialActionPlanData body = jsonMapper.readValue(
                    response.getResponse().getContentAsString(), FinancialActionPlanData.class);
            assertThat(body.caseInputRevision() == fixture.consultation().getCaseInputRevision()
                    && body.followUpAnswerRevision() == fixture.consultation().getFollowUpAnswerRevision())
                    .as("GET returned plan pair (%s,%s), current pair (%s,%s), previous pair (%s,%s)",
                            body.caseInputRevision(), body.followUpAnswerRevision(),
                            fixture.consultation().getCaseInputRevision(),
                            fixture.consultation().getFollowUpAnswerRevision(),
                            oldCaseRevision, oldAnswerRevision)
                    .isTrue();
        } else {
            assertThat(status).isEqualTo(500);
        }
    }

    private Fixture createConsultation() {
        String rawToken = tokenService.generateRawToken();
        OffsetDateTime now = now();
        GuestSession guest = guestSessionRepository.saveAndFlush(new GuestSession(
                tokenService.hashToken(rawToken), now, now.plusHours(1)));
        Consultation consultation = new Consultation(guest, now);
        consultation.updateCategory(ConsultationCategory.CARD,
                ConsultationScenario.CARD_LOSS_UNAUTHORIZED_USE, now.plusSeconds(1));
        consultation.updateSituation("카드를 잃어버렸어요.", now.plusSeconds(2));
        consultation = consultationRepository.saveAndFlush(consultation);
        return new Fixture(consultation, new Cookie(GuestSessionCookie.NAME, rawToken));
    }

    private FinancialActionPlan savePlan(Fixture fixture) {
        Consultation consultation = fixture.consultation();
        ProcedureVersion procedure = procedureVersionRepository.findLatestApproved(
                ProcedureVersionService.CARD_SCENARIO,
                ProcedureVersionService.KB_INSTITUTION,
                ProcedureVersionService.PERSONAL_CREDIT_CARD).orElseThrow();
        ConfirmedCaseSnapshot snapshot = snapshotRepository.saveAndFlush(new ConfirmedCaseSnapshot(
                consultation, consultation.getCaseInputRevision(),
                consultation.getFollowUpAnswerRevision(), "[]", "[]", now()));
        FinancialActionPlanData data = new FinancialActionPlanData(
                null, consultation.getId(), snapshot.getId(), procedure.getId(),
                procedure.getScenario(), procedure.getVersion(), snapshot.getCaseInputRevision(),
                snapshot.getFollowUpAnswerRevision(), PlanStatus.NEEDS_CLARIFICATION,
                List.of(), List.of(), List.of(), List.of("institution"), List.of(), List.of());
        return planRepository.saveAndFlush(new FinancialActionPlan(
                consultation, procedure, snapshot, data.status(), jsonMapper.writeValueAsString(data), now()));
    }

    private MvcResult getPlan(Fixture fixture) throws Exception {
        return mockMvc.perform(get("/api/v1/consultations/{consultationId}/financial-action-plan",
                        fixture.consultation().getId()).cookie(fixture.cookie()))
                .andReturn();
    }

    private static OffsetDateTime now() {
        return OffsetDateTime.now(ZoneOffset.UTC);
    }

    private record Fixture(Consultation consultation, Cookie cookie) {}
}
