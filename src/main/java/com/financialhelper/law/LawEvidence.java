package com.financialhelper.law;

import java.time.Instant;

/**
 * provenance를 포함한 검증된 법령 응답이다. 사람의 검토 전까지 대기 상태이며
 * provider 성공만으로 승인된 Evidence record가 되지 않는다.
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
     * 파생 navigation URL이 없는 caller를 위한 호환 생성자다.
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

    /** Work 6 snapshot contract 호환을 위해 유지하는 alias다. */
    public String serverVersion() {
        return providerVersion;
    }

    /** Work 6 snapshot contract 호환을 위해 유지하는 alias다. */
    public String serverCommit() {
        return providerRevision;
    }
}
