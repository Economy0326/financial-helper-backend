package com.financialhelper.source;

import java.time.LocalDate;

/** Claim-level time semantics. Publication, ingestion and decision dates never imply effect. */
public enum TemporalEvidencePolicy {
    EXPLICIT_EFFECTIVE, VERSION_EFFECTIVE, CURRENT_GUIDANCE,
    HISTORICAL_FACT, CONTRACT_DATE, DYNAMIC_CHECK, HOLD;

    public boolean valid(LocalDate start, LocalDate end, LocalDate verifiedOn, LocalDate validUntil) {
        if (end != null && start != null && end.isBefore(start)) return false;
        return switch (this) {
            case EXPLICIT_EFFECTIVE -> start != null;
            case VERSION_EFFECTIVE -> start != null && (end != null
                    || checked(verifiedOn, validUntil, 365));
            case HISTORICAL_FACT, CONTRACT_DATE -> start != null && end != null;
            case CURRENT_GUIDANCE, DYNAMIC_CHECK -> start == null && end == null
                    && checked(verifiedOn, validUntil, this == DYNAMIC_CHECK ? 30 : 365);
            case HOLD -> start == null && end == null && verifiedOn == null && validUntil == null;
        };
    }

    public boolean usable(LocalDate referenceDate, boolean historical, LocalDate start,
            LocalDate end, LocalDate verifiedOn, LocalDate validUntil) {
        if (referenceDate == null || !valid(start, end, verifiedOn, validUntil)) return false;
        return switch (this) {
            case EXPLICIT_EFFECTIVE -> start != null && !referenceDate.isBefore(start)
                    && (end == null || !referenceDate.isAfter(end));
            case VERSION_EFFECTIVE -> start != null && !referenceDate.isBefore(start)
                    && (end == null || !referenceDate.isAfter(end))
                    && (historical ? end != null : checked(verifiedOn, validUntil, 365)
                        && !referenceDate.isBefore(verifiedOn) && !referenceDate.isAfter(validUntil));
            case CONTRACT_DATE, HISTORICAL_FACT -> historical && start != null
                    && !referenceDate.isBefore(start) && end != null && !referenceDate.isAfter(end);
            case CURRENT_GUIDANCE, DYNAMIC_CHECK -> !historical && verifiedOn != null
                    && validUntil != null && !referenceDate.isBefore(verifiedOn)
                    && !referenceDate.isAfter(validUntil);
            case HOLD -> false;
        };
    }

    private static boolean checked(LocalDate on, LocalDate until, int maxDays) {
        return on != null && until != null && !until.isBefore(on)
                && !until.isAfter(on.plusDays(maxDays));
    }
}
