package com.financialhelper.ai.analysis;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class StructuredClarificationPolicyTest {

    @Test
    void exhaustedClarificationPreventsAnalysisSupplementForUnresolvedFact() {
        assertThat(StructuredClarificationPolicy.isExhausted(
                List.of("unauthorizedPayment"),
                Set.of("unauthorizedPayment")))
                .isTrue();
    }

    @Test
    void missingClarificationKeepsLegacySupplementAvailable() {
        assertThat(StructuredClarificationPolicy.isExhausted(
                List.of("unauthorizedPayment"),
                Set.of()))
                .isFalse();
    }

    @Test
    void everyUnresolvedFactMustHaveConsumedClarification() {
        assertThat(StructuredClarificationPolicy.isExhausted(
                List.of("unauthorizedPayment", "transactionType"),
                Set.of("unauthorizedPayment")))
                .isFalse();
    }
}
