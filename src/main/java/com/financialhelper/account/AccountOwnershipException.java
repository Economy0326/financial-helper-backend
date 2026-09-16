package com.financialhelper.account;

import com.financialhelper.common.error.ApiException;
import org.springframework.http.HttpStatus;

public class AccountOwnershipException extends ApiException {
    public AccountOwnershipException() {
        super(HttpStatus.FORBIDDEN, "ACCOUNT_OWNERSHIP_REQUIRED", "이 상담에 접근할 권한이 없습니다.");
    }
}
