package com.financialhelper.procedure;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.OffsetDateTime;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
class ProcedureVersionIntegrationTest {
    @Autowired
    private ProcedureVersionService procedureVersionService;
    @Autowired
    private ProcedureVersionRepository procedureVersionRepository;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void selectsSeededCardBranchOnlyWithinItsScopeAndDate() {
        String branch = procedureVersionService.selectCardProcedureScenario(new CardCaseFacts(Map.of(
                "cardLost", "TRUE", "unauthorizedPayment", "FALSE")));
        assertThat(branch).isEqualTo(ProcedureVersionService.CARD_LOSS_ONLY_SCENARIO);
        assertThat(procedureVersionService.selectApplicable(branch,
                ProcedureVersionService.KB_INSTITUTION,
                ProcedureVersionService.PERSONAL_CREDIT_CARD,
                LocalDate.of(2026, 5, 12), true).procedure().scenario()).isEqualTo(branch);
        assertThat(procedureVersionService.selectApplicable(branch,
                ProcedureVersionService.KB_INSTITUTION,
                ProcedureVersionService.PERSONAL_CREDIT_CARD,
                LocalDate.of(2026, 5, 11), true).failure())
                .isEqualTo(ProcedureVersionService.SelectionFailure.NO_APPLICABLE_VERSION);
        assertThat(procedureVersionService.selectApplicable(branch,
                "OTHER_INSTITUTION", ProcedureVersionService.PERSONAL_CREDIT_CARD,
                LocalDate.of(2026, 5, 12), true).failure())
                .isEqualTo(ProcedureVersionService.SelectionFailure.NO_APPLICABLE_VERSION);
    }

    @Test
    void requiresApprovedDateForCardVoiceAndAccountButPreservesSingleUnboundedSmishing() {
        assertThat(procedureVersionService.selectApplicable(
                ProcedureVersionService.CARD_SCENARIO, ProcedureVersionService.KB_INSTITUTION,
                ProcedureVersionService.PERSONAL_CREDIT_CARD, null, true).failure())
                .isEqualTo(ProcedureVersionService.SelectionFailure.DATE_REQUIRED);
        assertThat(procedureVersionService.selectApplicable(
                "VOICE_PHISHING_SUSPICIOUS_TRANSFER", "GENERIC_FINANCIAL_INSTITUTION",
                "BANK_ACCOUNT", null, true).failure())
                .isEqualTo(ProcedureVersionService.SelectionFailure.DATE_REQUIRED);
        assertThat(procedureVersionService.selectApplicable(
                "UNAUTHORIZED_ACCOUNT_TRANSFER", "GENERIC_FINANCIAL_INSTITUTION",
                "BANK_ACCOUNT", null, true).failure())
                .isEqualTo(ProcedureVersionService.SelectionFailure.DATE_REQUIRED);
        assertThat(procedureVersionService.selectApplicable(
                "PERSONAL_INFO_SMISHING_MALICIOUS_APP", "GENERIC_FINANCIAL_INSTITUTION",
                "DIGITAL_FINANCIAL_SERVICE", null, false).selected()).isTrue();
    }

    @Test
    @Transactional
    void migrationAllowsLegacyAndActionScopedContractShapes() {
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from flyway_schema_history where version = '36' and success = true",
                Integer.class)).isEqualTo(1);
        for (String json : new String[]{"[]", "[{\"sourceKey\":\"legacy\"}]",
                "{\"contract\":\"ACTION_SCOPED\",\"references\":[]}",
                "{\"contract\":\"ACTION_SCOPED\",\"references\":[{}]}"}) {
            assertThat(procedureVersionRepository.saveAndFlush(draft(json)).getId()).isNotNull();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{}", "{\"contract\":\"LEGACY\",\"references\":[]}",
            "{\"contract\":\"ACTION_SCOPED\"}",
            "{\"contract\":\"ACTION_SCOPED\",\"references\":null}",
            "{\"contract\":\"ACTION_SCOPED\",\"references\":{}}",
            "\"text\"", "null"
    })
    @Transactional
    void migrationRejectsInvalidContractShapes(String json) {
        assertThatThrownBy(() -> procedureVersionRepository.saveAndFlush(draft(json)))
                .isInstanceOf(RuntimeException.class)
                .hasStackTraceContaining("ck_procedure_version_evidence_references_json");
    }

    private ProcedureVersion draft(String references) {
        OffsetDateTime now = OffsetDateTime.now();
        return new ProcedureVersion("SYNTHETIC_CONTRACT_" + UUID.randomUUID(),
                "TEST_INSTITUTION", "TEST_PRODUCT", 1, ProcedureStatus.DRAFT,
                null, null, "[]", "[]", "[]", "[]", references,
                "[]", "[]", "[]", null, null, null, now);
    }

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
        assertThat(procedure.evidenceContract()).isEqualTo(ProcedureVersionData.EvidenceContract.LEGACY);
        assertThat(procedure.evidenceReferences()).allSatisfy(reference -> {
            assertThat(reference.isLegacy()).isTrue();
            assertThat(reference.referenceId()).isNull();
            assertThat(reference.actionIds()).isNull();
        });
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
