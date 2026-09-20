package com.financialhelper.procedure;

import java.util.List;
import java.util.Locale;

/** 작은 Procedure DSL을 위한 결정적 3값 evaluator다. */
public final class ConditionEvaluator {
    private ConditionEvaluator() {
    }

    public static ConditionResult evaluate(
            ConditionExpression expression,
            CardCaseFacts facts
    ) {
        if (expression == null || facts == null) {
            return ConditionResult.UNKNOWN;
        }
        if (expression.op() != null && !expression.isGroup()) {
            return ConditionResult.UNKNOWN;
        }
        if (expression.isGroup()) {
            if (expression.conditions().isEmpty()) {
                return ConditionResult.UNKNOWN;
            }
            return "AND".equals(expression.op())
                    ? and(expression.conditions(), facts)
                    : or(expression.conditions(), facts);
        }
        if (expression.factKey() == null || expression.operator() == null
                || (expression.operator() != ConditionOperator.IS_UNKNOWN
                && expression.expectedValue() == null)) {
            return ConditionResult.UNKNOWN;
        }
        String actual = facts.value(expression.factKey());
        if (expression.operator() == ConditionOperator.IS_UNKNOWN) {
            return actual == null || "UNKNOWN".equalsIgnoreCase(actual)
                    ? ConditionResult.TRUE : ConditionResult.FALSE;
        }
        if (actual == null || "UNKNOWN".equalsIgnoreCase(actual)) {
            return ConditionResult.UNKNOWN;
        }
        boolean equal = normalize(actual).equals(normalize(expression.expectedValue()));
        return expression.operator() == ConditionOperator.EQ
                ? (equal ? ConditionResult.TRUE : ConditionResult.FALSE)
                : (equal ? ConditionResult.FALSE : ConditionResult.TRUE);
    }

    private static ConditionResult and(
            List<ConditionExpression> expressions,
            CardCaseFacts facts
    ) {
        boolean unknown = false;
        for (ConditionExpression expression : expressions) {
            ConditionResult result = evaluate(expression, facts);
            if (result == ConditionResult.FALSE) {
                return ConditionResult.FALSE;
            }
            unknown |= result == ConditionResult.UNKNOWN;
        }
        return unknown ? ConditionResult.UNKNOWN : ConditionResult.TRUE;
    }

    private static ConditionResult or(
            List<ConditionExpression> expressions,
            CardCaseFacts facts
    ) {
        boolean unknown = false;
        for (ConditionExpression expression : expressions) {
            ConditionResult result = evaluate(expression, facts);
            if (result == ConditionResult.TRUE) {
                return ConditionResult.TRUE;
            }
            unknown |= result == ConditionResult.UNKNOWN;
        }
        return unknown ? ConditionResult.UNKNOWN : ConditionResult.FALSE;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }
}
