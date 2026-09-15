package com.financialhelper.account;

import org.springframework.stereotype.Service;

import java.util.concurrent.atomic.AtomicLong;

@Service
public class GlobalAiBudgetGuard {
    private final AccountProperties properties;
    private final AtomicLong usedUnits = new AtomicLong();

    public GlobalAiBudgetGuard(AccountProperties properties) { this.properties = properties; }

    public void reserveRequestUnit() {
        if (!properties.limits().globalAiBudgetEnabled()) return;
        long limit = properties.limits().globalAiBudgetUnits();
        long next = usedUnits.incrementAndGet();
        if (limit > 0 && next > limit) {
            usedUnits.decrementAndGet();
            throw new AiQuotaExceededException("GLOBAL_AI_BUDGET_EXCEEDED", "현재 AI 요청 한도에 도달했습니다.");
        }
    }
}
