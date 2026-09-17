package com.financialhelper.procedure;

import com.financialhelper.consultation.ConsultationScenario;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BreadthFollowUpPolicyTest {
    @Test
    void selectsVoicePhishingFactsWithoutUsingCardQuestions() {
        ProcedureVersionService procedures = mock(ProcedureVersionService.class);
        ProcedureVersionData procedure = procedure(ConsultationScenario.VOICE_PHISHING_SUSPICIOUS_TRANSFER,
                List.of(new ProcedureVersionData.RequiredFact("transferCompleted", "BOOLEAN", true),
                        new ProcedureVersionData.RequiredFact("suspiciousTransfer", "BOOLEAN", true)));
        when(procedures.requireApproved("VOICE_PHISHING_SUSPICIOUS_TRANSFER",
                "GENERIC_FINANCIAL_INSTITUTION", "BANK_ACCOUNT")).thenReturn(procedure);

        StructuredFollowUpData state = new StructuredFollowUpService(procedures)
                .specifyForScenario(ConsultationScenario.VOICE_PHISHING_SUSPICIOUS_TRANSFER,
                        Map.of("transferCompleted", "TRUE"), 1, Set.of());

        assertThat(state.missingFacts()).containsExactly("suspiciousTransfer");
        assertThat(state.questions()).singleElement()
                .extracting(FollowUpQuestionSpec::factKey)
                .isEqualTo("suspiciousTransfer");
    }

    @Test
    void repeatedUnknownDoesNotLoopAndOtherIsNotAProcedure() {
        ProcedureVersionService procedures = mock(ProcedureVersionService.class);
        ProcedureVersionData procedure = procedure(ConsultationScenario.UNAUTHORIZED_ACCOUNT_TRANSFER,
                List.of(new ProcedureVersionData.RequiredFact("unauthorizedTransaction", "BOOLEAN", true)));
        when(procedures.requireApproved("UNAUTHORIZED_ACCOUNT_TRANSFER",
                "GENERIC_FINANCIAL_INSTITUTION", "BANK_ACCOUNT")).thenReturn(procedure);

        StructuredFollowUpService service = new StructuredFollowUpService(procedures);
        StructuredFollowUpData first = service.specifyForScenario(
                ConsultationScenario.UNAUTHORIZED_ACCOUNT_TRANSFER,
                Map.of("unauthorizedTransaction", "UNKNOWN"), 1, Set.of());
        StructuredFollowUpData second = service.specifyForScenario(
                ConsultationScenario.UNAUTHORIZED_ACCOUNT_TRANSFER,
                Map.of("unauthorizedTransaction", "UNKNOWN"), 1, Set.of("unauthorizedTransaction"));

        assertThat(first.questions()).isEmpty();
        assertThat(second.questions()).isEmpty();
        assertThat(second.missingFacts()).containsExactly("unauthorizedTransaction");
    }

    @Test
    void selectsAccountTransferAndSmishingFactsFromTheirOwnProcedures() {
        ProcedureVersionService procedures = mock(ProcedureVersionService.class);
        ProcedureVersionData transfer = procedure(ConsultationScenario.UNAUTHORIZED_ACCOUNT_TRANSFER,
                List.of(new ProcedureVersionData.RequiredFact("unauthorizedTransaction", "BOOLEAN", true),
                        new ProcedureVersionData.RequiredFact("transactionType", "ENUM", true)));
        ProcedureVersionData smishing = procedure(ConsultationScenario.PERSONAL_INFO_SMISHING_MALICIOUS_APP,
                List.of(new ProcedureVersionData.RequiredFact("suspiciousLinkClicked", "BOOLEAN", true)));
        when(procedures.requireApproved("UNAUTHORIZED_ACCOUNT_TRANSFER",
                "GENERIC_FINANCIAL_INSTITUTION", "BANK_ACCOUNT")).thenReturn(transfer);
        when(procedures.requireApproved("PERSONAL_INFO_SMISHING_MALICIOUS_APP",
                "GENERIC_FINANCIAL_INSTITUTION", "DIGITAL_FINANCIAL_SERVICE")).thenReturn(smishing);

        StructuredFollowUpService service = new StructuredFollowUpService(procedures);
        StructuredFollowUpData transferState = service.specifyForScenario(
                ConsultationScenario.UNAUTHORIZED_ACCOUNT_TRANSFER,
                Map.of("unauthorizedTransaction", "TRUE"), 1, Set.of());
        StructuredFollowUpData smishingState = service.specifyForScenario(
                ConsultationScenario.PERSONAL_INFO_SMISHING_MALICIOUS_APP,
                Map.of(), 1, Set.of());

        assertThat(transferState.questions()).singleElement()
                .extracting(FollowUpQuestionSpec::factKey).isEqualTo("transactionType");
        assertThat(smishingState.questions()).singleElement()
                .extracting(FollowUpQuestionSpec::factKey).isEqualTo("suspiciousLinkClicked");
    }

    @Test
    void transferNotCompletedSkipsPostTransferQuestions() {
        ProcedureVersionService procedures = mock(ProcedureVersionService.class);
        ProcedureVersionData procedure = procedure(ConsultationScenario.VOICE_PHISHING_SUSPICIOUS_TRANSFER,
                List.of(new ProcedureVersionData.RequiredFact("transferCompleted", "BOOLEAN", true),
                        new ProcedureVersionData.RequiredFact("userInitiatedTransfer", "BOOLEAN", true),
                        new ProcedureVersionData.RequiredFact("reportedToFinancialInstitution", "BOOLEAN", true),
                        new ProcedureVersionData.RequiredFact("policeReported", "BOOLEAN", true),
                        new ProcedureVersionData.RequiredFact("suspiciousTransfer", "BOOLEAN", true)));
        when(procedures.requireApproved("VOICE_PHISHING_SUSPICIOUS_TRANSFER",
                "GENERIC_FINANCIAL_INSTITUTION", "BANK_ACCOUNT")).thenReturn(procedure);

        StructuredFollowUpData state = new StructuredFollowUpService(procedures)
                .specifyForScenario(ConsultationScenario.VOICE_PHISHING_SUSPICIOUS_TRANSFER,
                        Map.of("transferCompleted", "FALSE"), 1, Set.of());

        assertThat(state.missingFacts()).containsExactly("suspiciousTransfer");
        assertThat(state.questions()).singleElement()
                .extracting(FollowUpQuestionSpec::factKey)
                .isEqualTo("suspiciousTransfer");
    }

    @Test
    void authorizedAccountTransactionStopsUnauthorizedBranchQuestions() {
        ProcedureVersionService procedures = mock(ProcedureVersionService.class);
        ProcedureVersionData procedure = procedure(ConsultationScenario.UNAUTHORIZED_ACCOUNT_TRANSFER,
                List.of(new ProcedureVersionData.RequiredFact("unauthorizedTransaction", "BOOLEAN", true),
                        new ProcedureVersionData.RequiredFact("transactionType", "ENUM", true)));
        when(procedures.requireApproved("UNAUTHORIZED_ACCOUNT_TRANSFER",
                "GENERIC_FINANCIAL_INSTITUTION", "BANK_ACCOUNT")).thenReturn(procedure);

        StructuredFollowUpData state = new StructuredFollowUpService(procedures)
                .specifyForScenario(ConsultationScenario.UNAUTHORIZED_ACCOUNT_TRANSFER,
                        Map.of("unauthorizedTransaction", "FALSE"), 1, Set.of());

        assertThat(state.missingFacts()).isEmpty();
        assertThat(state.questions()).isEmpty();
    }

    private ProcedureVersionData procedure(ConsultationScenario scenario,
                                            List<ProcedureVersionData.RequiredFact> facts) {
        return new ProcedureVersionData(UUID.randomUUID(), scenario.name(),
                "GENERIC_FINANCIAL_INSTITUTION", "BANK_ACCOUNT", 1, ProcedureStatus.APPROVED,
                null, null, facts, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(), "test", "test", null);
    }
}
