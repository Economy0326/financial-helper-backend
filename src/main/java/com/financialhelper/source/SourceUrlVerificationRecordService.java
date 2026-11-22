package com.financialhelper.source;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

/** Opt-in draft storage; no controller or approval flow invokes this service. */
@Service
@ConditionalOnProperty(name = "app.source-url-verification-records.enabled", havingValue = "true")
public class SourceUrlVerificationRecordService {
    private final OfficialSourceVerificationService verifier;
    private final SourceReviewerService authority;
    private final JdbcTemplate jdbc;
    private final SourceUrlPolicy urls;
    private final TransactionTemplate transaction;

    public SourceUrlVerificationRecordService(OfficialSourceVerificationService verifier, SourceReviewerService authority,
            JdbcTemplate jdbc, SourceUrlPolicy urls, PlatformTransactionManager manager) {
        this.verifier = verifier; this.authority = authority; this.jdbc = jdbc; this.urls = urls;
        this.transaction = new TransactionTemplate(manager);
    }

    /** Verification is outside any transaction; the insert rechecks the source under a short lock. */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public UUID verifyAndRecord(String sourceKey, UUID documentId) {
        UUID reviewer = authority.requireReviewer();
        Snapshot initial = snapshot(sourceKey, documentId, false);
        var result = verifier.verify(sourceKey, documentId);
        UUID id = UUID.randomUUID();
        transaction.executeWithoutResult(status -> {
            if (!reviewer.equals(authority.requireReviewer())) throw new IllegalStateException("Reviewer changed");
            Snapshot current = snapshot(sourceKey, documentId, true);
            if (!initial.equals(current) || !documentId.equals(result.sourceDocumentId())
                    || current.version() != result.documentVersion() || !current.raw().equals(result.storedRawSha256())
                    || !current.url().equals(result.officialUrl())) {
                throw new IllegalStateException("Source changed during URL verification");
            }
            urls.validate(current.url(), current.domain());
            safeToStore(current.url());
            boolean success = "RAW_BYTES_MATCH".equals(result.result());
            String finalUrl = result.finalUrl();
            if (finalUrl != null) {
                try { urls.validate(finalUrl, current.domain()); safeToStore(finalUrl); }
                catch (RuntimeException invalidUrl) {
                    if (success) throw invalidUrl;
                    finalUrl = null; // Do not persist an unsafe redirect or credentials.
                }
            }
            jdbc.update("""
                    INSERT INTO source_url_verification_attempt
                    (id,source_document_id,source_registry_id,document_version,stored_raw_sha256,stored_content_sha256,
                     requested_url,final_url,checked_at,valid_until,method,method_version,result,http_status,
                     download_succeeded,observed_raw_sha256,observed_content_sha256,source_identity_verified,
                     failure_code,legal_version_verification_required,reviewer_account_id)
                    VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                    """, id, documentId, current.registryId(), current.version(), current.raw(), current.content(),
                    current.url(), finalUrl, result.checkedAt(), result.checkedAt().plus(Duration.ofHours(1)),
                    result.method(), 1, result.result(), result.httpStatus(), result.downloadSucceeded(),
                    result.observedRawSha256(), result.observedContentSha256(), result.sourceIdentityVerified(),
                    success ? null : result.result(), result.legalVersionVerificationRequired(), reviewer);
        });
        return id;
    }

    private Snapshot snapshot(String key, UUID id, boolean lock) {
        List<Snapshot> rows = jdbc.query("""
                SELECT d.source_registry_id,d.document_version,d.raw_sha256,d.content_sha256,r.canonical_url,r.official_domain
                FROM source_document d JOIN source_registry r ON r.id=d.source_registry_id
                JOIN official_source_domain o ON o.domain=r.official_domain AND o.organization_name=r.organization_name
                WHERE d.id=? AND d.status='ACTIVE' AND r.source_key=? AND r.enabled AND o.enabled
                  AND d.canonical_url=r.canonical_url
                """ + (lock ? " FOR UPDATE OF d,r,o" : ""), (rs, row) -> new Snapshot(
                rs.getObject(1, UUID.class), rs.getInt(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6)), id, key);
        if (rows.size() != 1) throw new IllegalStateException("Active official source unavailable");
        return rows.getFirst();
    }

    private static void safeToStore(String value) {
        URI uri = URI.create(value);
        String query = uri.getRawQuery();
        if (uri.getRawUserInfo() != null || uri.getRawFragment() != null || query != null
                && query.toLowerCase(java.util.Locale.ROOT).matches(".*(?:token|password|secret|session|cookie|auth|api[_-]?key).*")) {
            throw new IllegalArgumentException("URL may contain credentials");
        }
    }

    private record Snapshot(UUID registryId, int version, String raw, String content, String url, String domain) {}
}
