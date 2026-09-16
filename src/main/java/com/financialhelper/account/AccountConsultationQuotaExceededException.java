package com.financialhelper.account;

import com.financialhelper.common.error.ApiException;
import org.springframework.http.HttpStatus;

import java.time.OffsetDateTime;

public class AccountConsultationQuotaExceededException extends ApiException {
    private final OffsetDateTime nextAvailableAt;

    public AccountConsultationQuotaExceededException(OffsetDateTime nextAvailableAt) {
        super(HttpStatus.TOO_MANY_REQUESTS, "ACCOUNT_NEW_CONSULTATION_QUOTA_EXHAUSTED",
                "최근 상담 시작 한도에 도달했습니다. 기존 상담은 계속 이용할 수 있습니다.");
        this.nextAvailableAt = nextAvailableAt;
    }

    public OffsetDateTime getNextAvailableAt() { return nextAvailableAt; }
}
