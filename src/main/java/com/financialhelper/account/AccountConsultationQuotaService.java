package com.financialhelper.account;

import com.financialhelper.consultation.Consultation;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

@Service
public class AccountConsultationQuotaService {
    private final AccountConsultationStartRepository startRepository;
    private final AccountProperties properties;
    private final JdbcTemplate jdbc;
    private final Environment environment;

    public AccountConsultationQuotaService(AccountConsultationStartRepository startRepository,
                                           AccountProperties properties) {
        this(startRepository, properties, null, null);
    }

    @Autowired
    public AccountConsultationQuotaService(AccountConsultationStartRepository startRepository,
                                           AccountProperties properties, JdbcTemplate jdbc,
                                           Environment environment) {
        this.startRepository = startRepository;
        this.properties = properties;
        this.jdbc = jdbc;
        this.environment = environment;
    }

    private int limitFor(Account account, OffsetDateTime now) {
        int ordinary = properties.limits().newConsultationLimit();
        if (account == null || jdbc == null || environment == null ||
                !java.util.Arrays.asList(environment.getActiveProfiles()).contains("official-local-demo") ||
                !"persistent".equals(environment.getProperty("FH_DEMO_STORAGE_MODE"))) return ordinary;
        return jdbc.query("""
                SELECT new_consultation_limit FROM local_demo_consultation_quota_override
                WHERE account_id = ? AND expires_at > ?
                """, result -> result.next() ? result.getInt(1) : ordinary, account.getId(), now);
    }

    @Transactional
    public void recordNewConsultation(Account account, Consultation consultation, OffsetDateTime now) {
        if (account == null || consultation == null) return;
        // 같은 consultation의 재시도는 멱등이며 rolling window의
        // 신규 시작 횟수를 추가로 소비하지 않는다.
        if (startRepository.existsByConsultation_Id(consultation.getId())) {
            return;
        }
        OffsetDateTime since = now.minusDays(properties.limits().consultationWindowDays());
        long count = startRepository.countSince(account.getId(), since);
        if (count >= limitFor(account, now)) {
            OffsetDateTime next = startRepository.findOldestSince(account.getId(), since)
                    .map(value -> value.plusDays(properties.limits().consultationWindowDays()))
                    .orElse(now.plusDays(properties.limits().consultationWindowDays()));
            throw new AccountConsultationQuotaExceededException(next);
        }
        startRepository.save(new AccountConsultationStart(account, consultation, now));
    }

    @Transactional(readOnly = true)
    public QuotaStatus status(Account account, OffsetDateTime now) {
        if (account == null) return new QuotaStatus(0, properties.limits().newConsultationLimit(), null);
        OffsetDateTime since = now.minusDays(properties.limits().consultationWindowDays());
        long used = startRepository.countSince(account.getId(), since);
        int limit = limitFor(account, now);
        OffsetDateTime next = used >= limit
                ? startRepository.findOldestSince(account.getId(), since)
                .map(value -> value.plusDays(properties.limits().consultationWindowDays())).orElse(null)
                : null;
        return new QuotaStatus((int) used, limit, next);
    }

    public record QuotaStatus(int used, int limit, OffsetDateTime nextAvailableAt) { }
}
