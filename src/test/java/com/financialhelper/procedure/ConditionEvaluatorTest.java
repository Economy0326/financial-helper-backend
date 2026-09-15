package com.financialhelper.procedure;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ConditionEvaluatorTest {
    @Test
    void usesThreeValuedSemanticsForUnknownFacts() {
        ConditionExpression reportedFalse = leaf("reported", ConditionOperator.EQ, "FALSE");
        ConditionExpression paymentTrue = leaf("unauthorizedPayment", ConditionOperator.EQ, "TRUE");
        CardCaseFacts unknownReported = new CardCaseFacts(Map.of("unauthorizedPayment", "TRUE"));

        assertThat(ConditionEvaluator.evaluate(reportedFalse, unknownReported))
                .isEqualTo(ConditionResult.UNKNOWN);
        assertThat(ConditionEvaluator.evaluate(new ConditionExpression(
                "AND", null, null, null, List.of(reportedFalse, paymentTrue)), unknownReported))
                .isEqualTo(ConditionResult.UNKNOWN);
        assertThat(ConditionEvaluator.evaluate(new ConditionExpression(
                "OR", null, null, null, List.of(reportedFalse, paymentTrue)), unknownReported))
                .isEqualTo(ConditionResult.TRUE);
    }

    @Test
    void neverTreatsUnknownAsFalseForNotEqual() {
        assertThat(ConditionEvaluator.evaluate(
                leaf("reported", ConditionOperator.NEQ, "TRUE"), new CardCaseFacts(Map.of())))
                .isEqualTo(ConditionResult.UNKNOWN);
    }

    private ConditionExpression leaf(String key, ConditionOperator operator, String expected) {
        return new ConditionExpression(null, key, operator, expected, List.of());
    }
}
