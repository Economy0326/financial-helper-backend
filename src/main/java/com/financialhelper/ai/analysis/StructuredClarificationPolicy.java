package com.financialhelper.ai.analysis;

import java.util.Collection;
import java.util.Set;

/**
 * structured follow-up clarification과 1회 free-form Analysis 정보 보완을 분리한다.
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
