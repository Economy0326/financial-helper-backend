package com.financialhelper.account;

import com.financialhelper.consultation.Consultation;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

@Service
public class AccountConsultationQuotaService {
    private final AccountConsultationStartRepository startRepository;
    private final AccountProperties properties;

    public AccountConsultationQuotaService(AccountConsultationStartRepository startRepository,
                                           AccountProperties properties) {
        this.startRepository = startRepository;
        this.properties = properties;
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
        if (count >= properties.limits().newConsultationLimit()) {
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
        OffsetDateTime next = used >= properties.limits().newConsultationLimit()
                ? startRepository.findOldestSince(account.getId(), since)
                .map(value -> value.plusDays(properties.limits().consultationWindowDays())).orElse(null)
                : null;
        return new QuotaStatus((int) used, properties.limits().newConsultationLimit(), next);
    }

    public record QuotaStatus(int used, int limit, OffsetDateTime nextAvailableAt) { }
}
