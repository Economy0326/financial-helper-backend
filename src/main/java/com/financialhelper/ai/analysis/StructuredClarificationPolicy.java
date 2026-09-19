package com.financialhelper.ai.analysis;

import java.util.Collection;
import java.util.Set;

/**
 * Keeps structured follow-up clarification separate from the one-time
 * free-form Analysis information supplement.
 */
final class StructuredClarificationPolicy {

    private StructuredClarificationPolicy() {
    }

    static boolean isExhausted(
            Collection<String> unresolvedFacts,
            Set<String> clarificationAskedKeys
    ) {
        if (unresolvedFacts == null || unresolvedFacts.isEmpty()
                || clarificationAskedKeys == null) {
            return false;
        }
        return clarificationAskedKeys.containsAll(unresolvedFacts);
    }
}
