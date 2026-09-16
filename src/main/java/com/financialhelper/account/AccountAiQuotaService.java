package com.financialhelper.account;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

@Service
public class AccountAiQuotaService {
    private final AccountAiUsageRepository usageRepository;
    private final AccountSessionService sessionService;
    private final AccountProperties properties;

    public AccountAiQuotaService(AccountAiUsageRepository usageRepository,
                                 AccountSessionService sessionService,
                                 AccountProperties properties) {
        this.usageRepository = usageRepository;
        this.sessionService = sessionService;
        this.properties = properties;
    }

    @Transactional
    public void reserveIfAuthenticated() {
        Account account = sessionService.currentAccount().orElse(null);
        if (account == null) return;
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        AccountAiUsage usage = usageRepository.findByAccountId(account.getId()).orElse(null);
        if (usage == null) {
            usageRepository.save(new AccountAiUsage(account.getId(), now, 1, now));
            return;
        }
        if (!now.isBefore(usage.getWindowStartedAt().plus(properties.limits().aiQuotaWindow()))) {
            usage.reset(now, now);
            return;
        }
        if (usage.getAttemptCount() >= properties.limits().aiAttemptsPerWindow()) {
            throw new AiQuotaExceededException("ACCOUNT_AI_QUOTA_EXCEEDED", "AI 사용 한도를 초과했습니다.");
        }
        usage.increment(now);
    }
}
