package com.financialhelper.source;

import org.junit.jupiter.api.Test;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import static org.assertj.core.api.Assertions.assertThat;

class CurrentGuidanceTimeBoundariesTest {
    @Test void verificationFreshnessIsExactAndIndependentOfClaimAndReadyWindows() {
        OffsetDateTime checked = OffsetDateTime.parse("2026-11-14T14:30:00+09:00");
        OffsetDateTime expires = checked.plusHours(1);
        OffsetDateTime readyUntil = checked.plusDays(3);
        assertThat(CurrentGuidanceTimeBoundaries.verificationFresh(checked, expires, expires.minusNanos(1))).isTrue();
        assertThat(CurrentGuidanceTimeBoundaries.verificationFresh(checked, expires, expires)).isFalse();
        assertThat(CurrentGuidanceTimeBoundaries.verificationFresh(checked, expires, checked.minusNanos(1))).isFalse();
        assertThat(CurrentGuidanceTimeBoundaries.readyUsable(checked, readyUntil, expires)).isTrue();
        assertThat(CurrentGuidanceTimeBoundaries.readyUsable(checked, readyUntil, readyUntil)).isFalse();
        assertThat(CurrentGuidanceTimeBoundaries.withinKnownClaimBounds(null, null, LocalDate.of(2026, 11, 14), false)).isTrue();
        assertThat(CurrentGuidanceTimeBoundaries.withinKnownClaimBounds(null, null, LocalDate.of(2020, 1, 1), true)).isFalse();
        assertThat(CurrentGuidanceTimeBoundaries.withinKnownClaimBounds(LocalDate.of(2025, 1, 1), null, LocalDate.of(2024, 12, 31), false)).isFalse();
    }
}
