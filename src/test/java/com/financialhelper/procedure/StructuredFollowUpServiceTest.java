package com.financialhelper.procedure;

import com.financialhelper.retrieval.ConfirmedCaseSnapshotData;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

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
