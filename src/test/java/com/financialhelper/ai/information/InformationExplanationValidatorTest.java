package com.financialhelper.ai.information;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InformationExplanationValidatorTest {
    private final UUID support = UUID.randomUUID();
    private final UUID condition = UUID.randomUUID();
    private final UUID exception = UUID.randomUUID();
    private final InformationExplanationValidator validator = new InformationExplanationValidator();

    @Test
    void structuredOutputSchemaCanBeBuiltWithoutCallingProvider() {
        assertThat(com.openai.models.responses.ResponseCreateParams.builder()
                .model("synthetic-model").instructions("synthetic instructions")
                .input("synthetic input").text(InformationExplanationAiResult.class)
                .store(false).build()).isNotNull();
    }

    @Test
    void exactSelectionReturnsServerOwnedEvidence() {
        var evidence = evidence();
        var selected = new InformationExplanationAiResult(List.of(
                new InformationExplanationAiResult.Selection(support, "원문 근거", List.of(condition), List.of(exception))));
        assertThat(validator.validate(selected, snapshot(evidence))).containsExactly(evidence);
    }

    @Test
    void inventedStatementAndSourceAreRejected() {
        var snapshot = snapshot(evidence());
        assertThatThrownBy(() -> validator.validate(new InformationExplanationAiResult(List.of(
                new InformationExplanationAiResult.Selection(support, "환급이 확정됩니다", List.of(condition), List.of(exception)))), snapshot))
                .hasMessageContaining("differs");
        assertThatThrownBy(() -> validator.validate(new InformationExplanationAiResult(List.of(
                new InformationExplanationAiResult.Selection(UUID.randomUUID(), "원문 근거", List.of(condition), List.of(exception)))), snapshot))
                .hasMessageContaining("differs");
    }

    @Test
    void omittedConditionOrExceptionIsRejected() {
        var snapshot = snapshot(evidence());
        assertThatThrownBy(() -> validator.validate(new InformationExplanationAiResult(List.of(
                new InformationExplanationAiResult.Selection(support, "원문 근거", List.of(), List.of(exception)))), snapshot))
                .hasMessageContaining("differs");
        assertThatThrownBy(() -> validator.validate(new InformationExplanationAiResult(List.of(
                new InformationExplanationAiResult.Selection(support, "원문 근거", List.of(condition), List.of()))), snapshot))
                .hasMessageContaining("differs");
    }

    private InformationEvidenceSnapshot snapshot(InformationEvidenceSnapshot.Evidence evidence) {
        return new InformationEvidenceSnapshot(UUID.randomUUID(), 1, 2, "CARD_LOSS_UNAUTHORIZED_USE",
                UUID.randomUUID(), List.of(), List.of(), List.of(), List.of(evidence),
                "CURRENT_READY_SOURCE_AND_HASH", "V38_SCOPE_REVIEWED", "NOT_DETERMINED");
    }

    private InformationEvidenceSnapshot.Evidence evidence() {
        return new InformationEvidenceSnapshot.Evidence(UUID.randomUUID(), 1, "a".repeat(64), support,
                UUID.randomUUID(), "COMMON", "합성 기관", "합성 문서", "https://example.invalid/source",
                null, null, "support-locator", "원문 근거", null, null,
                LocalDate.of(2025, 1, 1), null,
                List.of(new InformationEvidenceSnapshot.RelatedText(condition, "condition-locator", "적용 조건")),
                List.of(new InformationEvidenceSnapshot.RelatedText(exception, "exception-locator", "예외")));
    }
}
