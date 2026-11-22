package com.financialhelper.source;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/** Independent time boundaries; none of these methods infers one window from another. */
final class CurrentGuidanceTimeBoundaries {
    private CurrentGuidanceTimeBoundaries() {}

    static boolean verificationFresh(OffsetDateTime checkedAt, OffsetDateTime reusableUntil, OffsetDateTime now) {
        return checkedAt != null && reusableUntil != null && now != null
                && reusableUntil.isAfter(checkedAt) && !now.isBefore(checkedAt) && now.isBefore(reusableUntil);
    }

    /** Passing known bounds alone does not prove that a claim applies. */
    static boolean withinKnownClaimBounds(LocalDate start, LocalDate end, LocalDate questionDate, boolean historical) {
        if (historical || questionDate == null || start != null && end != null && end.isBefore(start)) return false;
        return (start == null || !questionDate.isBefore(start)) && (end == null || !questionDate.isAfter(end));
    }

    static boolean readyUsable(OffsetDateTime validFrom, OffsetDateTime validUntil, OffsetDateTime now) {
        return validFrom != null && validUntil != null && now != null
                && validUntil.isAfter(validFrom) && !now.isBefore(validFrom) && now.isBefore(validUntil);
    }
}
