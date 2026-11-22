package com.financialhelper.retrieval;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DocumentAiExplanationQuotaTimeTest {
    @Test void statusUsesKoreanMidnightRatherThanUtcMidnight() {
        var jdbc = mock(JdbcTemplate.class);
        UUID account = UUID.randomUUID();
        when(jdbc.queryForObject(anyString(), eq(Integer.class), eq(account), eq(LocalDate.of(2026, 11, 18)))).thenReturn(3);
        when(jdbc.queryForObject(anyString(), eq(Integer.class), eq(account), eq(LocalDate.of(2026, 11, 19)))).thenReturn(0);
        var before = new DocumentAiExplanationQuota(jdbc, Clock.fixed(Instant.parse("2026-11-18T14:59:59Z"), ZoneOffset.UTC)).status(account);
        var after = new DocumentAiExplanationQuota(jdbc, Clock.fixed(Instant.parse("2026-11-18T15:00:00Z"), ZoneOffset.UTC)).status(account);
        assertThat(before.remaining()).isZero();
        assertThat(before.resetsAt()).isEqualTo("2026-11-19T00:00+09:00");
        assertThat(after.remaining()).isEqualTo(3);
        assertThat(after.resetsAt()).isEqualTo("2026-11-20T00:00+09:00");
    }
}
