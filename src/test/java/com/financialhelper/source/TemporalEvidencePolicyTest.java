package com.financialhelper.source;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

class TemporalEvidencePolicyTest {
    private static final LocalDate START = LocalDate.of(2025, 9, 1);

    @Test void explicitEffectDoesNotApplyBeforeStart() {
        var policy = TemporalEvidencePolicy.EXPLICIT_EFFECTIVE;
        assertTrue(policy.usable(START, false, START, null, null, null));
        assertFalse(policy.usable(START.minusDays(1), true, START, null, null, null));
        assertFalse(policy.valid(null, null, null, null));
    }

    @Test void currentGuidanceRequiresBoundedIndependentCheck() {
        var policy = TemporalEvidencePolicy.CURRENT_GUIDANCE;
        assertFalse(policy.valid(null, null, null, null));
        assertTrue(policy.usable(START.plusDays(1), false, null, null, START, START.plusDays(2)));
        assertFalse(policy.usable(START.plusDays(3), false, null, null, START, START.plusDays(2)));
        assertFalse(policy.usable(START.plusDays(1), true, null, null, START, START.plusDays(2)));
    }

    @Test void historicalVersionAndContractNeedBoundedPastWindow() {
        for (var policy : new TemporalEvidencePolicy[] {
                TemporalEvidencePolicy.VERSION_EFFECTIVE,
                TemporalEvidencePolicy.HISTORICAL_FACT,
                TemporalEvidencePolicy.CONTRACT_DATE }) {
            assertFalse(policy.usable(START.plusDays(1), false, START, START.plusDays(2), null, null));
            assertTrue(policy.usable(START.plusDays(1), true, START, START.plusDays(2), null, null));
            assertFalse(policy.usable(START.plusDays(3), true, START, START.plusDays(2), null, null));
            assertFalse(policy.usable(START, true, START, null, null, null));
        }
        assertTrue(TemporalEvidencePolicy.VERSION_EFFECTIVE.usable(START.plusDays(1), false,
                START, null, START, START.plusDays(2)));
        assertFalse(TemporalEvidencePolicy.VERSION_EFFECTIVE.usable(START.plusDays(3), false,
                START, null, START, START.plusDays(2)));
    }

    @Test void dynamicCheckExpiresWithinThirtyDaysAndHoldNeverApplies() {
        var policy = TemporalEvidencePolicy.DYNAMIC_CHECK;
        assertFalse(policy.valid(null, null, START, START.plusDays(31)));
        assertTrue(policy.valid(null, null, START, START.plusDays(30)));
        assertFalse(TemporalEvidencePolicy.HOLD.usable(START, false, null, null, null, null));
    }
}
