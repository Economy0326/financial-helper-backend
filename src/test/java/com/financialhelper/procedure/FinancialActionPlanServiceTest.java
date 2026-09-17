package com.financialhelper.procedure;

import com.financialhelper.guest.GuestSessionService;
import com.financialhelper.consultation.ConsultationRepository;
import com.financialhelper.retrieval.ConfirmedCaseSnapshotData;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FinancialActionPlanServiceTest {
    @Test
    void unknownConditionDoesNotCreateAnAction() {
        ProcedureVersionService procedureService = mock(ProcedureVersionService.class);
        EvidenceBindingResolver resolver = mock(EvidenceBindingResolver.class);
        when(procedureService.isApplicable(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(true);
        when(resolver.resolve(org.mockito.ArgumentMatchers.anyList(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(new EvidenceBindingResolver.Resolution(List.of(), List.of()));
        FinancialActionPlanService service = service(procedureService, resolver);

        FinancialActionPlanData result = service.buildFromSnapshot(
                snapshot(), procedure(false), new CardCaseFacts(Map.of(
                        "institution", ProcedureVersionService.KB_INSTITUTION,
                        "productType", ProcedureVersionService.PERSONAL_CREDIT_CARD,
                        "cardLost", "TRUE",
                        "unauthorizedPayment", "TRUE",
                        "transactionType", "CREDIT_SALE",
                        "domestic", "TRUE",
                        "incidentDate", "2026-09-01"
                )));

        assertThat(result.status()).isEqualTo(PlanStatus.NEEDS_CLARIFICATION);
        assertThat(result.actions()).isEmpty();
        assertThat(result.unresolvedFacts()).contains("reported");
    }

    @Test
    void approvedProcedureProducesOrderedActionForExplicitFalseReport() {
        ProcedureVersionService procedureService = mock(ProcedureVersionService.class);
        EvidenceBindingResolver resolver = mock(EvidenceBindingResolver.class);
        when(procedureService.isApplicable(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(true);
        when(resolver.resolve(org.mockito.ArgumentMatchers.anyList(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(new EvidenceBindingResolver.Resolution(List.of(), List.of()));
        FinancialActionPlanService service = service(procedureService, resolver);

        FinancialActionPlanData result = service.buildFromSnapshot(
                snapshot(), procedure(true), new CardCaseFacts(Map.of(
                        "institution", ProcedureVersionService.KB_INSTITUTION,
                        "productType", ProcedureVersionService.PERSONAL_CREDIT_CARD,
                        "cardLost", "TRUE",
                        "unauthorizedPayment", "TRUE",
                        "transactionType", "CREDIT_SALE",
                        "domestic", "TRUE",
                        "reported", "FALSE",
                        "incidentDate", "2026-09-01"
                )));

        assertThat(result.status()).isEqualTo(PlanStatus.READY);
        assertThat(result.actions()).extracting(FinancialActionPlanData.Action::actionId)
                .containsExactly("report-loss");
    }

    @Test
    void unsupportedInstitutionFailsClosed() {
        ProcedureVersionService procedureService = mock(ProcedureVersionService.class);
        FinancialActionPlanService service = service(procedureService, mock(EvidenceBindingResolver.class));
        when(procedureService.isApplicable(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(true);

        FinancialActionPlanData result = service.buildFromSnapshot(
                snapshot(), procedure(true), new CardCaseFacts(Map.of(
                        "institution", "OTHER_CARD",
                        "productType", ProcedureVersionService.PERSONAL_CREDIT_CARD,
                        "incidentDate", "2026-09-01"
                )));

        assertThat(result.status()).isEqualTo(PlanStatus.UNSUPPORTED);
        assertThat(result.actions()).isEmpty();
        assertThat(result.coverageGaps()).contains("CARD_SCOPE_OUT_OF_SCOPE");
    }

    @Test
    void unapprovedProcedureCannotExecute() {
        ProcedureVersionService procedureService = mock(ProcedureVersionService.class);
        FinancialActionPlanService service = service(procedureService, mock(EvidenceBindingResolver.class));
        ProcedureVersionData draft = new ProcedureVersionData(
                UUID.randomUUID(), ProcedureVersionService.CARD_SCENARIO,
                ProcedureVersionService.KB_INSTITUTION, ProcedureVersionService.PERSONAL_CREDIT_CARD,
                2, ProcedureStatus.DRAFT, null, null, List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of(), List.of(), "draft", null, null);

        FinancialActionPlanData result = service.buildFromSnapshot(
                snapshot(), draft, new CardCaseFacts(Map.of()));

        assertThat(result.status()).isEqualTo(PlanStatus.FAILED);
        assertThat(result.coverageGaps()).containsExactly("PROCEDURE_NOT_APPROVED");
    }

    @Test
    void requiredFactMissingIsReturnedBeforeActionEvaluation() {
        ProcedureVersionService procedureService = mock(ProcedureVersionService.class);
        EvidenceBindingResolver resolver = mock(EvidenceBindingResolver.class);
        when(procedureService.isApplicable(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(true);
        FinancialActionPlanService service = service(procedureService, resolver);

        FinancialActionPlanData result = service.buildFromSnapshot(
                snapshot(), procedure(true), new CardCaseFacts(Map.of(
                        "institution", ProcedureVersionService.KB_INSTITUTION,
                        "productType", ProcedureVersionService.PERSONAL_CREDIT_CARD,
                        "incidentDate", "2026-09-01")));

        assertThat(result.status()).isEqualTo(PlanStatus.NEEDS_CLARIFICATION);
        assertThat(result.unresolvedFacts()).contains("reported");
        org.mockito.Mockito.verifyNoInteractions(resolver);
    }

    @Test
    void nonBlockingUnknownKeepsDefinitelyTrueActionAsPartialGuidance() {
        ProcedureVersionService procedureService = mock(ProcedureVersionService.class);
        EvidenceBindingResolver resolver = mock(EvidenceBindingResolver.class);
        when(procedureService.isApplicable(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(true);
        when(resolver.resolve(org.mockito.ArgumentMatchers.anyList(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(new EvidenceBindingResolver.Resolution(List.of(), List.of()));
        FinancialActionPlanService service = service(procedureService, resolver);

        FinancialActionPlanData result = service.buildFromSnapshot(
                snapshot(), procedure(true), new CardCaseFacts(Map.of(
                        "institution", ProcedureVersionService.KB_INSTITUTION,
                        "productType", ProcedureVersionService.PERSONAL_CREDIT_CARD,
                        "cardLost", "TRUE",
                        "unauthorizedPayment", "TRUE",
                        "transactionType", "CREDIT_SALE",
                        "domestic", "TRUE",
                        "reported", "FALSE")));

        assertThat(result.status()).isEqualTo(PlanStatus.NEEDS_CLARIFICATION);
        assertThat(result.actions()).extracting(FinancialActionPlanData.Action::actionId)
                .containsExactly("report-loss");
        assertThat(result.unresolvedFacts()).contains("incidentDate");
        assertThat(result.warnings()).contains("PARTIAL_GUIDANCE_ONLY");
    }

    @Test
    void breadthProcedureWithoutReviewedEvidenceFailsClosed() {
        ProcedureVersionService procedureService = mock(ProcedureVersionService.class);
        EvidenceBindingResolver resolver = mock(EvidenceBindingResolver.class);
        when(procedureService.isApplicable(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(true);
        FinancialActionPlanService service = service(procedureService, resolver);

        ProcedureVersionData breadth = new ProcedureVersionData(
                UUID.randomUUID(), "VOICE_PHISHING_SUSPICIOUS_TRANSFER",
                "GENERIC_FINANCIAL_INSTITUTION", "BANK_ACCOUNT", 1, ProcedureStatus.APPROVED,
                null, null, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of("source corpus pending"), "breadth", "test", null);

        FinancialActionPlanData result = service.buildFromSnapshot(
                snapshot(), breadth, new CardCaseFacts(Map.of("suspiciousTransfer", "TRUE")));

        assertThat(result.status()).isEqualTo(PlanStatus.NEEDS_CLARIFICATION);
        assertThat(result.actions()).isEmpty();
        assertThat(result.coverageGaps()).containsExactly("OFFICIAL_EVIDENCE_UNAVAILABLE");
    }

    @Test
    void breadthSafeActionSurvivesBlockingUnknownAsPartialGuidance() {
        ProcedureVersionService procedureService = mock(ProcedureVersionService.class);
        EvidenceBindingResolver resolver = mock(EvidenceBindingResolver.class);
        when(procedureService.isApplicable(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(true);
        when(resolver.resolve(org.mockito.ArgumentMatchers.anyList(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(new EvidenceBindingResolver.Resolution(
                        List.of(new FinancialActionPlanData.EvidenceBinding(
                                "fsc-2026-alert", UUID.randomUUID(), 1, List.of(UUID.randomUUID()),
                                null, null, "SAFE_GUIDANCE")), List.of()));
        FinancialActionPlanService service = service(procedureService, resolver);

        ConditionExpression suspicious = new ConditionExpression(
                null, "suspiciousTransfer", ConditionOperator.EQ, "TRUE", List.of());
        ProcedureVersionData procedure = new ProcedureVersionData(
                UUID.randomUUID(), "VOICE_PHISHING_SUSPICIOUS_TRANSFER",
                "GENERIC_FINANCIAL_INSTITUTION", "BANK_ACCOUNT", 1, ProcedureStatus.APPROVED,
                null, null,
                List.of(new ProcedureVersionData.RequiredFact("transferCompleted", "BOOLEAN", true),
                        new ProcedureVersionData.RequiredFact("suspiciousTransfer", "BOOLEAN", true)),
                List.of(new ProcedureVersionData.ConditionRule("voice-secure-contact", suspicious)),
                List.of(new ProcedureVersionData.ActionStep("voice-secure-contact", 1,
                        "금융회사 공식 채널 확인", "안전한 공식 채널로 문의합니다.", null, List.of())),
                List.of(),
                List.of(new ProcedureVersionData.EvidenceReference(
                        "fsc-2026-alert", 1, "hash", null, null, "SAFE_GUIDANCE", true)),
                List.of(), List.of(), List.of("송금 여부가 확인되면 추가 안내가 달라질 수 있다."),
                "test", "test", null);

        FinancialActionPlanData result = service.buildFromSnapshot(
                snapshot(), procedure, new CardCaseFacts(Map.of(
                        "suspiciousTransfer", "TRUE", "transferCompleted", "UNKNOWN")));

        assertThat(result.status()).isEqualTo(PlanStatus.NEEDS_CLARIFICATION);
        assertThat(result.actions()).extracting(FinancialActionPlanData.Action::actionId)
                .containsExactly("voice-secure-contact");
        assertThat(result.unresolvedFacts()).contains("transferCompleted");
        assertThat(result.warnings()).contains("PARTIAL_GUIDANCE_ONLY");
    }

    @Test
    void overseasTransactionIsOutsideTheInitialCardSlice() {
        ProcedureVersionService procedureService = mock(ProcedureVersionService.class);
        FinancialActionPlanService service = service(procedureService, mock(EvidenceBindingResolver.class));
        when(procedureService.isApplicable(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(true);

        FinancialActionPlanData result = service.buildFromSnapshot(
                snapshot(), procedure(true), new CardCaseFacts(Map.of(
                        "institution", ProcedureVersionService.KB_INSTITUTION,
                        "productType", ProcedureVersionService.PERSONAL_CREDIT_CARD,
                        "cardLost", "TRUE", "unauthorizedPayment", "TRUE",
                        "transactionType", "CREDIT_SALE", "domestic", "FALSE",
                        "reported", "FALSE", "incidentDate", "2026-09-01")));

        assertThat(result.status()).isEqualTo(PlanStatus.UNSUPPORTED);
        assertThat(result.coverageGaps()).contains("CARD_SCOPE_OUT_OF_SCOPE");
    }

    @Test
    void cardLoanTransactionIsOutsideTheInitialCardSlice() {
        ProcedureVersionService procedureService = mock(ProcedureVersionService.class);
        FinancialActionPlanService service = service(procedureService, mock(EvidenceBindingResolver.class));
        when(procedureService.isApplicable(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(true);

        FinancialActionPlanData result = service.buildFromSnapshot(
                snapshot(), procedure(true), new CardCaseFacts(Map.of(
                        "institution", ProcedureVersionService.KB_INSTITUTION,
                        "productType", ProcedureVersionService.PERSONAL_CREDIT_CARD,
                        "cardLost", "TRUE", "unauthorizedPayment", "TRUE",
                        "transactionType", "CARD_LOAN", "domestic", "TRUE",
                        "reported", "FALSE", "incidentDate", "2026-09-01")));

        assertThat(result.status()).isEqualTo(PlanStatus.UNSUPPORTED);
        assertThat(result.coverageGaps()).contains("CARD_SCOPE_OUT_OF_SCOPE");
    }

    private FinancialActionPlanService service(
            ProcedureVersionService procedureService,
            EvidenceBindingResolver resolver
    ) {
        return new FinancialActionPlanService(
                mock(ConsultationRepository.class), mock(GuestSessionService.class),
                mock(com.financialhelper.retrieval.ConfirmedCaseSnapshotService.class),
                mock(com.financialhelper.retrieval.ConfirmedCaseSnapshotRepository.class),
                mock(ProcedureVersionRepository.class), procedureService, resolver,
                mock(FinancialActionPlanRepository.class),
                tools.jackson.databind.json.JsonMapper.builder().build());
    }

    private ConfirmedCaseSnapshotData snapshot() {
        return new ConfirmedCaseSnapshotData(
                UUID.randomUUID(), UUID.randomUUID(), 1, 1, List.of(), List.of(), null);
    }

    private ProcedureVersionData procedure(boolean includeReported) {
        List<ProcedureVersionData.RequiredFact> required = new java.util.ArrayList<>(List.of(
                new ProcedureVersionData.RequiredFact("institution", "INSTITUTION", true),
                new ProcedureVersionData.RequiredFact("productType", "ENUM", true),
                new ProcedureVersionData.RequiredFact("incidentDate", "DATE", true)));
        if (includeReported) {
            required.add(new ProcedureVersionData.RequiredFact("reported", "BOOLEAN", true));
        }
        ConditionExpression report = new ConditionExpression("AND", null, null, null, List.of(
                new ConditionExpression(null, "cardLost", ConditionOperator.EQ, "TRUE", List.of()),
                new ConditionExpression(null, "reported", ConditionOperator.EQ, "FALSE", List.of())));
        return new ProcedureVersionData(
                UUID.randomUUID(), ProcedureVersionService.CARD_SCENARIO,
                ProcedureVersionService.KB_INSTITUTION, ProcedureVersionService.PERSONAL_CREDIT_CARD,
                1, ProcedureStatus.APPROVED, null, null, required,
                List.of(new ProcedureVersionData.ConditionRule("report-loss", report)),
                List.of(new ProcedureVersionData.ActionStep("report-loss", 1, "분실·도난 신고", "신고", "channel", List.of())),
                List.of(), List.of(), List.of(), List.of(), List.of(), "", "test", null);
    }
}
