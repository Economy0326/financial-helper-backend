package com.financialhelper.law;

import java.time.Instant;

/**
 * Validated law response with provenance. It remains pending human review;
 * Provider success alone never makes this an approved evidence record.
 */
public record LawEvidence(
        String statuteName,
        String lawIdentifier,
        String mst,
        String articleLocator,
        String promulgationDate,
        String effectiveDate,
        String canonicalUrl,
        String sourceUrl,
        String text,
        Instant retrievedAt,
        String providerVersion,
        String providerRevision,
        String toolName,
        String acquisitionKind,
        String applicabilityBasis,
        String validationStatus,
        String derivedUrl
) {

    /**
     * Compatibility constructor for callers that have no derived navigation URL.
     */
    public LawEvidence(
            String statuteName,
            String lawIdentifier,
            String mst,
            String articleLocator,
            String promulgationDate,
            String effectiveDate,
            String canonicalUrl,
            String sourceUrl,
            String text,
            Instant retrievedAt,
            String providerVersion,
            String providerRevision,
            String toolName,
            String acquisitionKind,
            String applicabilityBasis,
            String validationStatus
    ) {
        this(
                statuteName,
                lawIdentifier,
                mst,
                articleLocator,
                promulgationDate,
                effectiveDate,
                canonicalUrl,
                sourceUrl,
                text,
                retrievedAt,
                providerVersion,
                providerRevision,
                toolName,
                acquisitionKind,
                applicabilityBasis,
                validationStatus,
                null
        );
    }

    public LawEvidence {
        requireText(statuteName, "statuteName");
        requireText(lawIdentifier, "lawIdentifier");
        requireText(mst, "mst");
        requireText(articleLocator, "articleLocator");
        requireText(effectiveDate, "effectiveDate");
        requireText(text, "text");
        if (retrievedAt == null) throw new IllegalArgumentException("retrievedAt is required");
        requireText(providerVersion, "providerVersion");
        requireText(providerRevision, "providerRevision");
        requireText(toolName, "toolName");
        requireText(acquisitionKind, "acquisitionKind");
        requireText(applicabilityBasis, "applicabilityBasis");
        requireText(validationStatus, "validationStatus");
        if (isBlank(canonicalUrl) && isBlank(sourceUrl) && isBlank(derivedUrl)) {
            throw new IllegalArgumentException("at least one URL provenance field is required");
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
    }

    /** Compatibility alias retained for the Work 6 snapshot contract. */
    public String serverVersion() {
        return providerVersion;
    }

    /** Compatibility alias retained for the Work 6 snapshot contract. */
    public String serverCommit() {
        return providerRevision;
    }
}
