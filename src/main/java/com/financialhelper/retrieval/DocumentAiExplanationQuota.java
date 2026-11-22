package com.financialhelper.retrieval;

import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Short database reservations only; no provider calls or generated text are stored here. */
@Service
public class DocumentAiExplanationQuota {
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final int DAILY_LIMIT = 3;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    @org.springframework.beans.factory.annotation.Autowired
    public DocumentAiExplanationQuota(JdbcTemplate jdbc) { this(jdbc, Clock.systemUTC()); }
    DocumentAiExplanationQuota(JdbcTemplate jdbc, Clock clock) { this.jdbc = jdbc; this.clock = clock; }

    @Transactional
    public boolean reserve(UUID account, UUID snapshot, UUID request) {
        lockAccount(account);
        var now = OffsetDateTime.now(clock);
        var day = LocalDate.now(clock.withZone(SEOUL));
        if (jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM document_ai_explanation_attempt WHERE request_id=?)", Boolean.class, request)) return false;
        Integer active = jdbc.queryForObject("""
                SELECT count(*) FROM document_ai_explanation_attempt WHERE account_id=? AND quota_date=?
                  AND (status='SUCCEEDED' OR (status='RESERVED' AND expires_at>?))
                """, Integer.class, account, day, now);
        Integer recent = jdbc.queryForObject("SELECT count(*) FROM document_ai_explanation_attempt WHERE account_id=? AND created_at>?", Integer.class, account, now.minusMinutes(1));
        Integer dailyAttempts = jdbc.queryForObject("SELECT count(*) FROM document_ai_explanation_attempt WHERE account_id=? AND quota_date=?", Integer.class, account, day);
        if (active == null || recent == null || dailyAttempts == null || active >= DAILY_LIMIT || recent >= 6 || dailyAttempts >= 20) return false;
        jdbc.update("INSERT INTO document_ai_explanation_attempt(request_id,account_id,snapshot_id,quota_date,status,created_at,expires_at) VALUES (?,?,?,?,'RESERVED',?,?)",
                request, account, snapshot, day, now, now.plusMinutes(5));
        return true;
    }

    @Transactional
    public boolean succeed(UUID account, UUID request, java.util.function.BooleanSupplier evidenceStillEligible) {
        lockAccount(account);
        if (evidenceStillEligible == null || !evidenceStillEligible.getAsBoolean()) return false;
        var now = OffsetDateTime.now(clock);
        var day = LocalDate.now(clock.withZone(SEOUL));
        Integer active = jdbc.queryForObject("""
                SELECT count(*) FROM document_ai_explanation_attempt WHERE account_id=? AND quota_date=? AND request_id<>?
                  AND (status='SUCCEEDED' OR (status='RESERVED' AND expires_at>?))
                """, Integer.class, account, day, request, now);
        if (active == null || active >= DAILY_LIMIT) return false;
        return jdbc.update("""
                UPDATE document_ai_explanation_attempt SET status='SUCCEEDED',quota_date=?,finished_at=?
                WHERE request_id=? AND account_id=? AND status='RESERVED' AND expires_at>?
                """, day, now, request, account, now) == 1;
    }

    @Transactional
    public void fail(UUID account, UUID request) {
        jdbc.update("UPDATE document_ai_explanation_attempt SET status='FAILED',finished_at=? WHERE request_id=? AND account_id=? AND status='RESERVED'",
                OffsetDateTime.now(clock), request, account);
    }

    @Transactional(readOnly = true)
    public Status status(UUID account) {
        var day = LocalDate.now(clock.withZone(SEOUL));
        Integer used = jdbc.queryForObject("SELECT count(*) FROM document_ai_explanation_attempt WHERE account_id=? AND quota_date=? AND status='SUCCEEDED'", Integer.class, account, day);
        return new Status(Math.max(0, DAILY_LIMIT - (used == null ? DAILY_LIMIT : used)), DAILY_LIMIT,
                day.plusDays(1).atStartOfDay(SEOUL).toOffsetDateTime().toString());
    }
    private void lockAccount(UUID account) {
        if (jdbc.query("SELECT id FROM account WHERE id=? AND status='ACTIVE' FOR UPDATE", (rs, n) -> rs.getObject(1, UUID.class), account).size() != 1)
            throw new IllegalStateException("account unavailable");
    }
    public record Status(int remaining, int limit, String resetsAt) {}
}
