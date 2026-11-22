package com.financialhelper.retrieval;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** URL freshness and search availability are deliberately independent. */
@Component
public class SearchAvailabilityWindowGate {
    private final JdbcTemplate jdbc;
    private final boolean enabled;
    private final Clock clock;

    @Autowired
    public SearchAvailabilityWindowGate(JdbcTemplate jdbc,
            @Value("${app.source-url-verification-records.enabled:false}") boolean enabled) {
        this(jdbc, enabled, Clock.systemUTC());
    }

    public SearchAvailabilityWindowGate(JdbcTemplate jdbc, boolean enabled, Clock clock) {
        this.jdbc = jdbc; this.enabled = enabled; this.clock = clock;
    }

    public boolean allows(UUID reviewId) {
        if (!enabled) {
            // A disabled node must not silently use a newer contract created by another node.
            Boolean schemaPresent = jdbc.queryForObject(
                    "SELECT to_regclass('source_review_verification_link') IS NOT NULL", Boolean.class);
            if (!Boolean.TRUE.equals(schemaPresent)) return true;
            Boolean linked = jdbc.queryForObject("""
                    SELECT EXISTS (SELECT 1 FROM source_review_decision_audit a
                      JOIN source_review_verification_link l ON l.audit_id=a.id
                      WHERE a.review_id=? AND a.decision='APPROVED')
                    """, Boolean.class, reviewId);
            return !Boolean.TRUE.equals(linked);
        }
        OffsetDateTime now = OffsetDateTime.now(clock);
        List<Boolean> windows = jdbc.query("""
                SELECT w.valid_from <= ? AND w.valid_until > ?
                FROM source_review_decision_audit a
                JOIN source_review_verification_link l ON l.audit_id=a.id
                LEFT JOIN source_review_search_window w ON w.audit_id=l.audit_id
                WHERE a.review_id=? AND a.decision='APPROVED'
                """, (rs, row) -> rs.getBoolean(1),
                now, now, reviewId);
        // A linked approval without a window is not searchable. No link means legacy review.
        return windows.isEmpty() || windows.size() == 1 && Boolean.TRUE.equals(windows.getFirst());
    }
}
