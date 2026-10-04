package com.financialhelper.procedure;

/** Shared conservative eligibility view: an explicit UNKNOWN cannot be repaired by later facts. */
public final class ActionDependencyEvaluator {
    private ActionDependencyEvaluator() {}

    public static boolean hasCandidate(ProcedureVersionData procedure, CardCaseFacts facts) {
        return hasProcedureIdentityCandidate(procedure, facts)
                && procedure.conditionRules().stream()
                .anyMatch(rule -> canStillBecomeTrue(rule.expression(), facts));
    }

    /**
     * A ProcedureVersion bound to a concrete institution cannot lend
     * its actions to an unresolved institution. This is deliberately derived from
     * the reviewed ProcedureVersion binding rather than a scenario/fact stop
     * list: generic procedures have no such prerequisite.
     */
    public static boolean hasProcedureIdentityCandidate(
            ProcedureVersionData procedure,
            CardCaseFacts facts
    ) {
        return identityIsUsable(procedure, facts, "institution", procedure.institution(),
                "GENERIC_FINANCIAL_INSTITUTION");
    }

    public static boolean hasDefinitelyTrueAction(ProcedureVersionData procedure, CardCaseFacts facts) {
        return hasProcedureIdentityCandidate(procedure, facts)
                && procedure.conditionRules().stream()
                .anyMatch(rule -> ConditionEvaluator.evaluate(rule.expression(), facts)
                        == ConditionResult.TRUE);
    }

    private static boolean identityIsUsable(
            ProcedureVersionData procedure,
            CardCaseFacts facts,
            String factKey,
            String procedureValue,
            String genericValue
    ) {
        if (procedureValue == null || procedureValue.isBlank()
                || procedureValue.equals(genericValue)) {
            return true;
        }
        boolean required = procedure.requiredFacts().stream()
                .anyMatch(fact -> fact.requiredForDecision() && factKey.equals(fact.key()));
        return !required || facts.hasKnownValue(factKey);
    }

    private static boolean canStillBecomeTrue(ConditionExpression expression, CardCaseFacts facts) {
        if (expression == null) return false;
        if (expression.isGroup()) {
            return "AND".equals(expression.op())
                    ? expression.conditions().stream().allMatch(child -> canStillBecomeTrue(child, facts))
                    : expression.conditions().stream().anyMatch(child -> canStillBecomeTrue(child, facts));
        }
        String value = facts.value(expression.factKey());
        if ("UNKNOWN".equalsIgnoreCase(value)) return false;
        return ConditionEvaluator.evaluate(expression, facts) != ConditionResult.FALSE;
    }
}
