package com.financialhelper.source;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Observations never overwrite the stored document or its review history. */
@Service
@EnableScheduling
public class DocumentChangeService {
    private final JdbcTemplate jdbc;
    private final SourceReviewerService authority;
    private final OfficialSourceVerificationService verifier;
    private final TransactionTemplate transaction;

    public DocumentChangeService(JdbcTemplate jdbc, SourceReviewerService authority,
            OfficialSourceVerificationService verifier, PlatformTransactionManager manager) {
        this.jdbc = jdbc; this.authority = authority; this.verifier = verifier;
        this.transaction = new TransactionTemplate(manager);
    }

    public Observation check(UUID documentId) {
        UUID reviewer = authority.requireReviewer();
        return observe(documentId, reviewer);
    }

    public Observation status(UUID documentId) {
        authority.requireReviewer();
        var rows = jdbc.query("SELECT checked_at,last_result,change_detected FROM document_change_observation WHERE source_document_id=?",
                (rs, n) -> new Observation(rs.getObject(1, OffsetDateTime.class), rs.getString(2), rs.getBoolean(3)), documentId);
        return rows.isEmpty() ? new Observation(null, "NOT_CHECKED", false) : rows.getFirst();
    }

    @Scheduled(initialDelayString = "${app.source-change.initial-delay-ms:86400000}", fixedDelayString = "${app.source-change.interval-ms:86400000}")
    public void checkRegisteredDocuments() {
        List<UUID> ids = jdbc.query("SELECT DISTINCT d.id FROM source_document d JOIN source_document_claim c ON c.source_document_id=d.id WHERE d.status='ACTIVE'",
                (rs, n) -> rs.getObject(1, UUID.class));
        for (UUID id : ids) {
            try { observe(id, null); } catch (RuntimeException failure) {
                org.slf4j.LoggerFactory.getLogger(DocumentChangeService.class).warn("Official source check failed: {}", failure.getClass().getSimpleName());
            }
        }
    }

    private Observation observe(UUID id, UUID reviewer) {
        var sources = jdbc.query("SELECT r.source_key,d.document_version,d.content_sha256,d.raw_sha256,r.canonical_url,r.official_domain FROM source_document d JOIN source_registry r ON r.id=d.source_registry_id WHERE d.id=? AND d.status='ACTIVE' AND r.enabled AND EXISTS (SELECT 1 FROM source_document_claim c WHERE c.source_document_id=d.id)",
                (rs, n) -> new Source(rs.getString(1), rs.getInt(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6)), id);
        if (sources.size() != 1) throw new IllegalStateException("Registered document unavailable");
        Source source = sources.getFirst();
        OfficialSourceVerificationService.Result result;
        boolean registeredText = Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM source_text_registration WHERE source_document_id=?)", Boolean.class, id));
        try { result = verifier.verifyRegistered(source.key(), id, registeredText); }
        catch (RuntimeException failure) { result = null; }
        String status = classify(source.content(), result);
        OffsetDateTime checkedAt = result == null ? OffsetDateTime.now(java.time.ZoneOffset.UTC) : result.checkedAt();
        return transaction.execute(tx -> {
            if (reviewer != null && !reviewer.equals(authority.requireReviewer())) throw new IllegalStateException("Reviewer changed during check");
            var locked = jdbc.query("""
                SELECT d.id FROM source_document d JOIN source_registry r ON r.id=d.source_registry_id
                JOIN official_source_domain o ON o.domain=r.official_domain AND o.organization_name=r.organization_name
                WHERE d.id=? AND d.status='ACTIVE' AND d.document_version=? AND d.content_sha256=? AND d.raw_sha256=?
                  AND r.enabled AND o.enabled AND r.source_key=? AND r.canonical_url=? AND r.official_domain=?
                FOR UPDATE OF d,r,o
                """, (rs, n) -> rs.getObject(1, UUID.class), id, source.version(), source.content(), source.raw(), source.key(), source.url(), source.domain());
            if (locked.size() != 1) throw new IllegalStateException("Document changed during check");
            var count = jdbc.update("""
                INSERT INTO document_change_observation(source_document_id,document_version,content_sha256,checked_at,last_result,change_detected)
                SELECT id,document_version,content_sha256,?,?,? FROM source_document
                WHERE id=? AND status='ACTIVE' AND document_version=? AND content_sha256=? AND raw_sha256=?
                  AND EXISTS (SELECT 1 FROM source_registry r JOIN official_source_domain o ON o.domain=r.official_domain AND o.organization_name=r.organization_name
                    WHERE r.id=source_document.source_registry_id AND r.enabled AND o.enabled AND r.source_key=? AND r.canonical_url=? AND r.official_domain=?)
                ON CONFLICT (source_document_id) DO UPDATE SET checked_at=GREATEST(document_change_observation.checked_at,EXCLUDED.checked_at),
                  last_result=CASE WHEN EXCLUDED.checked_at>=document_change_observation.checked_at THEN EXCLUDED.last_result ELSE document_change_observation.last_result END,
                  change_detected=document_change_observation.change_detected OR EXCLUDED.change_detected
                WHERE document_change_observation.document_version=EXCLUDED.document_version
                  AND document_change_observation.content_sha256=EXCLUDED.content_sha256
                """, checkedAt, status, "CHANGED".equals(status), id, source.version(), source.content(), source.raw(), source.key(), source.url(), source.domain());
            if (count != 1) throw new IllegalStateException("Document changed during check");
            return jdbc.queryForObject("SELECT checked_at,last_result,change_detected FROM document_change_observation WHERE source_document_id=?",
                    (rs, n) -> new Observation(rs.getObject(1, OffsetDateTime.class), rs.getString(2), rs.getBoolean(3)), id);
        });
    }

    static String classify(String content, OfficialSourceVerificationService.Result result) {
        return result == null ? "FAILED" : switch (result.result()) {
            case "RAW_BYTES_MATCH", "HTML_NORMALIZED_MATCH_RAW_CHANGED_REVIEW_REQUIRED", "CONTENT_CHANGED_REVIEW_REQUIRED", "FINAL_URL_CHANGED_REVIEW_REQUIRED" ->
                    result.observedContentSha256() == null ? "FAILED" : content.equals(result.observedContentSha256()) ? "UNCHANGED" : "CHANGED";
            default -> "FAILED";
        };
    }

    private record Source(String key, int version, String content, String raw, String url, String domain) {}
    public record Observation(OffsetDateTime checkedAt, String result, boolean changeDetected) {}
}
