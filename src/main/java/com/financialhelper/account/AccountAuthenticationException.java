package com.financialhelper.account;

import com.financialhelper.common.error.ApiException;
import org.springframework.http.HttpStatus;

public class AccountAuthenticationException extends ApiException {
    public AccountAuthenticationException(String code, String message, HttpStatus status) {
        super(status, code, message);
    }

    public static AccountAuthenticationException required() {
        return new AccountAuthenticationException(
                "GENERAL_CONSULTATION_LOGIN_REQUIRED",
                "일반 상담은 로그인 후 이용할 수 있습니다.",
                HttpStatus.UNAUTHORIZED
        );
    }

    public static AccountAuthenticationException unavailable() {
        return new AccountAuthenticationException(
                "SOCIAL_LOGIN_UNAVAILABLE",
                "현재 로그인 서비스를 이용할 수 없습니다.",
                HttpStatus.SERVICE_UNAVAILABLE
        );
    }

    public static AccountAuthenticationException invalidCallback() {
        return new AccountAuthenticationException(
                "INVALID_SOCIAL_LOGIN_CALLBACK",
                "로그인 확인에 실패했습니다.",
                HttpStatus.BAD_REQUEST
        );
    }
}
