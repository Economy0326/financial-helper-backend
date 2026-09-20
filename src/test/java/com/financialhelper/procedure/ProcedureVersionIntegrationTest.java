package com.financialhelper.procedure;

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class ProcedureVersionIntegrationTest {
    @Autowired
    private ProcedureVersionService procedureVersionService;

    @Test
    void loadsOnlyApprovedCardProcedureSeed() {
        ProcedureVersionData procedure = procedureVersionService.requireApprovedCard();

        assertThat(procedure.status()).isEqualTo(ProcedureStatus.APPROVED);
        assertThat(procedure.scenario()).isEqualTo(ProcedureVersionService.CARD_SCENARIO);
        assertThat(procedure.institution()).isEqualTo(ProcedureVersionService.KB_INSTITUTION);
        assertThat(procedure.productType()).isEqualTo(ProcedureVersionService.PERSONAL_CREDIT_CARD);
        assertThat(procedure.requiredFacts()).extracting(ProcedureVersionData.RequiredFact::key)
                .contains("institution", "reported", "incidentDate");
        assertThat(procedure.actionSteps()).extracting(ProcedureVersionData.ActionStep::actionId)
                .contains("report-loss", "submit-compensation-request");
        assertThat(procedure.evidenceReferences()).hasSize(7);
    }

    @Test
    void selectsCardBreadthProceduresFromConfirmedFacts() {
        ProcedureVersionData lossOnly = procedureVersionService.requireApprovedCard(new CardCaseFacts(Map.of(
                "cardLost", "TRUE", "unauthorizedPayment", "FALSE")));
        assertThat(lossOnly.scenario()).isEqualTo(ProcedureVersionService.CARD_LOSS_ONLY_SCENARIO);
        assertThat(lossOnly.actionSteps()).extracting(ProcedureVersionData.ActionStep::actionId)
                .containsExactly("report-loss");

        ProcedureVersionData heldCard = procedureVersionService.requireApprovedCard(new CardCaseFacts(Map.of(
                "cardLost", "FALSE", "unauthorizedPayment", "TRUE")));
        assertThat(heldCard.scenario()).isEqualTo(ProcedureVersionService.CARD_HELD_UNAUTHORIZED_SCENARIO);

        ProcedureVersionData process = procedureVersionService.requireApprovedCard(new CardCaseFacts(Map.of(
                "cardLost", "TRUE", "unauthorizedPayment", "TRUE", "reported", "TRUE",
                "compensationStatus", "INVESTIGATING")));
        assertThat(process.scenario()).isEqualTo(ProcedureVersionService.CARD_COMPENSATION_PROCESS_SCENARIO);
        assertThat(process.actionSteps()).extracting(ProcedureVersionData.ActionStep::actionId)
                .containsExactly("confirm-compensation-application", "track-compensation-process");

        ProcedureVersionData result = procedureVersionService.requireApprovedCard(new CardCaseFacts(Map.of(
                "cardLost", "TRUE", "unauthorizedPayment", "TRUE", "reported", "TRUE",
                "compensationStatus", "RESULT_RECEIVED")));
        assertThat(result.scenario()).isEqualTo(ProcedureVersionService.CARD_COMPENSATION_RESULT_SCENARIO);
        assertThat(result.actionSteps()).extracting(ProcedureVersionData.ActionStep::actionId)
                .containsExactly("review-compensation-result", "request-result-review");
    }
}
