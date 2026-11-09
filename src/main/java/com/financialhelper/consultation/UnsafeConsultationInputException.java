package com.financialhelper.consultation;

import com.financialhelper.common.error.ApiException;
import org.springframework.http.HttpStatus;

/** Never includes the rejected value or the matching fragment. */
public final class UnsafeConsultationInputException extends ApiException {
    public UnsafeConsultationInputException() {
        super(HttpStatus.BAD_REQUEST, "CONSULTATION_SENSITIVE_INPUT",
                "개인정보나 인증정보를 제외하고 다시 입력해 주세요.");
    }
}
