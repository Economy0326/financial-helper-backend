package com.financialhelper.account;

import com.financialhelper.common.error.ApiException;
import org.springframework.http.HttpStatus;

public class InputLimitException extends ApiException {
    public InputLimitException(String field) {
        super(HttpStatus.PAYLOAD_TOO_LARGE, "INPUT_TOO_LARGE", field + " 입력이 허용 범위를 초과했습니다.");
    }
}
