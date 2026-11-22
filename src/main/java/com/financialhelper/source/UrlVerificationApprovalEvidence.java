package com.financialhelper.source;

import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;

/** Preliminary evidence test only. The approval transaction must separately lock and validate its claim. */
final class UrlVerificationApprovalEvidence {
    private UrlVerificationApprovalEvidence() {}

    record Attempt(UUID documentId, UUID registryId, int version, String rawHash, String contentHash,
                   String requestedUrl, OffsetDateTime checkedAt, OffsetDateTime validUntil,
                   String result, boolean identityVerified, boolean legalVersionVerificationRequired) {}

    static boolean eligible(Attempt attempt, UUID documentId, UUID registryId, int version,
            String rawHash, String contentHash, String canonicalUrl, OffsetDateTime decisionAt) {
        return attempt != null && documentId != null && registryId != null && version > 0
                && rawHash != null && !rawHash.isBlank() && contentHash != null && !contentHash.isBlank()
                && canonicalUrl != null && !canonicalUrl.isBlank() && Objects.equals(attempt.documentId(), documentId)
                && Objects.equals(attempt.registryId(), registryId) && attempt.version() == version
                && Objects.equals(attempt.rawHash(), rawHash) && Objects.equals(attempt.contentHash(), contentHash)
                && Objects.equals(attempt.requestedUrl(), canonicalUrl)
                && CurrentGuidanceTimeBoundaries.verificationFresh(attempt.checkedAt(), attempt.validUntil(), decisionAt)
                && "RAW_BYTES_MATCH".equals(attempt.result()) && attempt.identityVerified()
                && !attempt.legalVersionVerificationRequired();
    }
}
