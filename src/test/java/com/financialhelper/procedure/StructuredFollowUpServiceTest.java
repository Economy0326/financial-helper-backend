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
        when(procedureService.requireApprovedCard()).thenReturn(procedure);

        StructuredFollowUpData result = new StructuredFollowUpService(procedureService)
                .specifyFromValues(Map.of("institution", "KB국민카드"), 3);

        assertThat(result.missingFacts()).containsExactly("reported");
        assertThat(result.questions()).singleElement().satisfies(question -> {
            assertThat(question.factKey()).isEqualTo("reported");
            assertThat(question.inputType()).isEqualTo(FollowUpInputType.YES_NO_UNKNOWN);
            assertThat(question.options()).extracting(FollowUpQuestionSpec.Option::value)
                    .containsExactly("TRUE", "FALSE", "UNKNOWN");
        });
    }

    @Test
    void keepsExplicitUnknownAsMissingWithoutRepeatingTheQuestion() {
        ProcedureVersionService procedureService = mock(ProcedureVersionService.class);
        ProcedureVersionData procedure = procedure(List.of(
                new ProcedureVersionData.RequiredFact("reported", "BOOLEAN", true)
        ));
        when(procedureService.requireApprovedCard()).thenReturn(procedure);

        StructuredFollowUpData result = new StructuredFollowUpService(procedureService)
                .specifyFromValues(Map.of("reported", "UNKNOWN"), 3);

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
        when(procedureService.requireApprovedCard()).thenReturn(procedure);

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
        when(procedureService.requireApprovedCard()).thenReturn(procedure);
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
