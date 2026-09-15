package com.financialhelper.account;

import com.financialhelper.common.error.ApiException;
import org.springframework.http.HttpStatus;

public class AiQuotaExceededException extends ApiException {
    public AiQuotaExceededException(String code, String message) {
        super(HttpStatus.TOO_MANY_REQUESTS, code, message);
    }
}
