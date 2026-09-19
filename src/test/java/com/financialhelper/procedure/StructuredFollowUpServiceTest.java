package com.financialhelper.procedure;

import com.financialhelper.retrieval.ConfirmedCaseSnapshotData;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class StructuredFollowUpServiceTest {
    @Test
    void asksOnlyForMissingFactsAndProvidesTypedUnknownPath() {
        ProcedureVersionService procedureService = mock(ProcedureVersionService.class);
        ProcedureVersionData procedure = procedure(List.of(
                new ProcedureVersionData.RequiredFact("institution", "INSTITUTION", true),
                new ProcedureVersionData.RequiredFact("reported", "BOOLEAN", true)
        ));
        when(procedureService.requireApprovedCard(org.mockito.ArgumentMatchers.any(CardCaseFacts.class)))
                .thenReturn(procedure);

        StructuredFollowUpData result = new StructuredFollowUpService(procedureService)
                .specifyFromValues(Map.of("institution", "KB국민카드"), 3);

        assertThat(result.missingFacts()).containsExactly("reported");
        assertThat(result.questions()).singleElement().satisfies(question -> {
            assertThat(question.factKey()).isEqualTo("reported");
            assertThat(question.inputType()).isEqualTo(FollowUpInputType.YES_NO_UNKNOWN);
            assertThat(question.options()).extracting(FollowUpQuestionSpec.Option::value)
                    .containsExactly("TRUE", "FALSE", "UNKNOWN");
            assertThat(question.options()).extracting(FollowUpQuestionSpec.Option::label)
                    .containsExactly("이미 신고했어요", "아직 신고하지 않았어요", "잘 모르겠어요");
        });
    }

    @Test
    void keepsExplicitUnknownAsMissingWithoutRepeatingTheQuestion() {
        ProcedureVersionService procedureService = mock(ProcedureVersionService.class);
        ProcedureVersionData procedure = procedure(List.of(
                new ProcedureVersionData.RequiredFact("reported", "BOOLEAN", true)
        ));
        when(procedureService.requireApprovedCard(org.mockito.ArgumentMatchers.any(CardCaseFacts.class)))
                .thenReturn(procedure);

        StructuredFollowUpData result = new StructuredFollowUpService(procedureService)
                .specifyFromValues(Map.of("reported", "UNKNOWN"), 3, Set.of("reported"));

        assertThat(result.missingFacts()).containsExactly("reported");
        assertThat(result.questions()).isEmpty();
    }

    @Test
    void skipsTransactionTypeWhenUnauthorizedPaymentIsExplicitlyFalse() {
        ProcedureVersionService procedureService = mock(ProcedureVersionService.class);
        ProcedureVersionData procedure = procedure(List.of(
                new ProcedureVersionData.RequiredFact("unauthorizedPayment", "BOOLEAN", true),
                new ProcedureVersionData.RequiredFact("transactionType", "ENUM", true),
                new ProcedureVersionData.RequiredFact("incidentDate", "DATE", true)
        ));
        when(procedureService.requireApprovedCard(org.mockito.ArgumentMatchers.any(CardCaseFacts.class)))
                .thenReturn(procedure);

        StructuredFollowUpData result = new StructuredFollowUpService(procedureService)
                .specifyFromValues(Map.of("unauthorizedPayment", "FALSE"), 4);

        assertThat(result.missingFacts()).containsExactly("incidentDate");
        assertThat(result.questions()).singleElement()
                .satisfies(question -> {
                    assertThat(question.factKey()).isEqualTo("incidentDate");
                    assertThat(question.inputType()).isEqualTo(FollowUpInputType.DATE);
                    assertThat(question.options()).extracting(FollowUpQuestionSpec.Option::value)
                            .containsExactly("UNKNOWN");
                });
    }

    @Test
    void unknownProductGetsOneClarificationAndThenStops() {
        ProcedureVersionService procedureService = mock(ProcedureVersionService.class);
        ProcedureVersionData procedure = procedure(List.of(
                new ProcedureVersionData.RequiredFact("productType", "ENUM", true)
        ));
        when(procedureService.requireApprovedCard(org.mockito.ArgumentMatchers.any(CardCaseFacts.class)))
                .thenReturn(procedure);
        StructuredFollowUpService service = new StructuredFollowUpService(procedureService);

        StructuredFollowUpData clarification = service.specifyFromValues(
                Map.of("productType", "UNKNOWN"), 3);
        assertThat(clarification.questions()).singleElement().satisfies(question -> {
            assertThat(question.factKey()).isEqualTo("productType");
            assertThat(question.questionIntent()).isEqualTo("CLARIFY_PRODUCT");
        });

        StructuredFollowUpData afterClarification = service.specifyFromValues(
                Map.of("productType", "UNKNOWN"), 3, Set.of("productType"));
        assertThat(afterClarification.questions()).isEmpty();
        assertThat(afterClarification.missingFacts()).containsExactly("productType");
    }

    @Test
    void unknownUnauthorizedPaymentGetsSimplerClarificationOnceThenStops() {
        ProcedureVersionService procedureService = mock(ProcedureVersionService.class);
        ProcedureVersionData procedure = procedure(List.of(
                new ProcedureVersionData.RequiredFact("unauthorizedPayment", "BOOLEAN", true)
        ));
        when(procedureService.requireApprovedCard(org.mockito.ArgumentMatchers.any(CardCaseFacts.class)))
                .thenReturn(procedure);
        StructuredFollowUpService service = new StructuredFollowUpService(procedureService);

        StructuredFollowUpData clarification = service.specifyFromValues(
                Map.of("unauthorizedPayment", "UNKNOWN"), 3);
        assertThat(clarification.questions()).singleElement().satisfies(question -> {
            assertThat(question.question()).contains("최근 카드 이용내역");
            assertThat(question.options()).extracting(FollowUpQuestionSpec.Option::label)
                    .containsExactly("기억나지 않는 결제가 있어요", "모두 제가 한 결제예요", "확인해도 잘 모르겠어요");
            assertThat(question.questionIntent()).isEqualTo("CLARIFY_UNAUTHORIZED_PAYMENT");
        });

        StructuredFollowUpData afterClarification = service.specifyFromValues(
                Map.of("unauthorizedPayment", "UNKNOWN"), 3, Set.of("unauthorizedPayment"));
        assertThat(afterClarification.questions()).isEmpty();
        assertThat(afterClarification.missingFacts()).containsExactly("unauthorizedPayment");
    }

    @Test
    void unknownIncidentDateGetsOneClarificationWithoutFreeFormSupplementFallback() {
        ProcedureVersionService procedureService = mock(ProcedureVersionService.class);
        ProcedureVersionData procedure = procedure(List.of(
                new ProcedureVersionData.RequiredFact("incidentDate", "DATE", true)
        ));
        when(procedureService.requireApprovedCard(org.mockito.ArgumentMatchers.any(CardCaseFacts.class)))
                .thenReturn(procedure);
        StructuredFollowUpService service = new StructuredFollowUpService(procedureService);

        StructuredFollowUpData clarification = service.specifyFromValues(
                Map.of("incidentDate", "UNKNOWN"), 3);
        assertThat(clarification.questions()).singleElement().satisfies(question -> {
            assertThat(question.inputType()).isEqualTo(FollowUpInputType.DATE);
            assertThat(question.questionIntent()).isEqualTo("CLARIFY_INCIDENT_DATE");
        });

        StructuredFollowUpData afterClarification = service.specifyFromValues(
                Map.of("incidentDate", "UNKNOWN"), 3, Set.of("incidentDate"));
        assertThat(afterClarification.questions()).isEmpty();
        assertThat(afterClarification.missingFacts()).containsExactly("incidentDate");
    }

    @Test
    void voiceTransferUnknownGetsOneEasyClarificationAndStops() {
        ProcedureVersionService procedureService = mock(ProcedureVersionService.class);
        ProcedureVersionData procedure = new ProcedureVersionData(
                java.util.UUID.randomUUID(),
                "VOICE_PHISHING_SUSPICIOUS_TRANSFER",
                "GENERIC_FINANCIAL_INSTITUTION", "BANK_ACCOUNT", 1,
                ProcedureStatus.APPROVED, null, null,
                List.of(new ProcedureVersionData.RequiredFact(
                        "transferCompleted", "BOOLEAN", true)),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(), "test", "test", null);
        when(procedureService.requireApproved(
                "VOICE_PHISHING_SUSPICIOUS_TRANSFER",
                "GENERIC_FINANCIAL_INSTITUTION", "BANK_ACCOUNT"))
                .thenReturn(procedure);
        StructuredFollowUpService service = new StructuredFollowUpService(procedureService);

        StructuredFollowUpData clarification = service.specifyForScenario(
                com.financialhelper.consultation.ConsultationScenario.VOICE_PHISHING_SUSPICIOUS_TRANSFER,
                Map.of("transferCompleted", "UNKNOWN"), 3, Set.of());
        assertThat(clarification.questions()).singleElement().satisfies(question -> {
            assertThat(question.question()).contains("실제로 돈을 보냈는지");
            assertThat(question.questionIntent()).isEqualTo("CLARIFY_TRANSFER_COMPLETED");
            assertThat(question.options()).extracting(FollowUpQuestionSpec.Option::value)
                    .containsExactly("TRUE", "FALSE", "UNKNOWN");
        });

        StructuredFollowUpData exhausted = service.specifyForScenario(
                com.financialhelper.consultation.ConsultationScenario.VOICE_PHISHING_SUSPICIOUS_TRANSFER,
                Map.of("transferCompleted", "UNKNOWN"), 3, Set.of("transferCompleted"));
        assertThat(exhausted.questions()).isEmpty();
        assertThat(exhausted.missingFacts()).containsExactly("transferCompleted");
    }

    private ProcedureVersionData procedure(List<ProcedureVersionData.RequiredFact> requiredFacts) {
        return new ProcedureVersionData(
                java.util.UUID.randomUUID(),
                ProcedureVersionService.CARD_SCENARIO,
                ProcedureVersionService.KB_INSTITUTION,
                ProcedureVersionService.PERSONAL_CREDIT_CARD,
                1,
                ProcedureStatus.APPROVED,
                null, null, requiredFacts, List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), "test", "test", null);
    }
}
