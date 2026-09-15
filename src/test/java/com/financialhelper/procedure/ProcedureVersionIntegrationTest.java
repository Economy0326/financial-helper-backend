package com.financialhelper.procedure;

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

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
}
