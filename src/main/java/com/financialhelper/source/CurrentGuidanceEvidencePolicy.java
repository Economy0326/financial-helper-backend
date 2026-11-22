package com.financialhelper.source;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Preliminary isolated policy. Inputs must come from locked, independently reviewed
 * official evidence; no API input or URL byte match alone establishes these facts.
 * This class is not wired to approval, READY, or production configuration.
 */
final class CurrentGuidanceEvidencePolicy {
    enum Kind { OFFICIAL_CURRENT_GUIDANCE, ARCHIVED_NOTICE, STATUTE, UNKNOWN }
    enum Result { ELIGIBLE, MISSING_BASIS, SCOPE_MISMATCH, UNTRUSTED_BASIS, EXPIRED_BASIS, POLICY_UNCONFIGURED }
    record Basis(UUID targetDocumentId, UUID targetChunkId, String targetRawHash,
                 UUID proofAuditId, UUID proofReviewId, UUID proofChunkId, String proofLocator,
                 Kind kind, boolean proofApprovedAndActive, boolean sameOfficialInstitution,
                 Instant observedAt, Instant proofUsableUntil) {}
    record Window(Instant from, Instant until) {}
    record Evaluation(Result result, Window window) {}

    private final Map<Kind, Duration> recheckPeriods;

    CurrentGuidanceEvidencePolicy(Map<Kind, Duration> recheckPeriods) {
        this.recheckPeriods = Map.copyOf(recheckPeriods);
        if (this.recheckPeriods.entrySet().stream().anyMatch(entry -> entry.getKey() != Kind.OFFICIAL_CURRENT_GUIDANCE
                || entry.getValue().isZero() || entry.getValue().isNegative()
                || entry.getValue().compareTo(Duration.ofDays(365)) > 0)) {
            throw new IllegalArgumentException("Invalid current guidance recheck period");
        }
    }

    Evaluation evaluate(UUID documentId, UUID chunkId, String rawHash, Basis basis, Instant now) {
        if (basis == null || now == null) return new Evaluation(Result.MISSING_BASIS, null);
        if (documentId == null || chunkId == null || rawHash == null || !rawHash.matches("[a-f0-9]{64}")
                || !Objects.equals(documentId, basis.targetDocumentId())
                || !Objects.equals(chunkId, basis.targetChunkId()) || !Objects.equals(rawHash, basis.targetRawHash())) {
            return new Evaluation(Result.SCOPE_MISMATCH, null);
        }
        if (basis.kind() != Kind.OFFICIAL_CURRENT_GUIDANCE || basis.proofAuditId() == null
                || basis.proofReviewId() == null || basis.proofChunkId() == null
                || basis.proofChunkId().equals(chunkId) || basis.proofLocator() == null
                || basis.proofLocator().isBlank() || !basis.proofApprovedAndActive() || !basis.sameOfficialInstitution()) {
            return new Evaluation(Result.UNTRUSTED_BASIS, null);
        }
        if (basis.observedAt() == null || basis.proofUsableUntil() == null || now.isBefore(basis.observedAt())
                || !now.isBefore(basis.proofUsableUntil())) {
            return new Evaluation(Result.EXPIRED_BASIS, null);
        }
        Duration period = recheckPeriods.get(basis.kind());
        if (period == null) return new Evaluation(Result.POLICY_UNCONFIGURED, null);
        Instant configuredEnd = now.plus(period);
        Instant end = configuredEnd.isBefore(basis.proofUsableUntil()) ? configuredEnd : basis.proofUsableUntil();
        return new Evaluation(Result.ELIGIBLE, new Window(now, end));
    }
}
