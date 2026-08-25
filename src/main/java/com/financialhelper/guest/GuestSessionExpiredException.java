package com.financialhelper.guest;

import com.financialhelper.common.error.ApiException;
import org.springframework.http.HttpStatus;

public class GuestSessionExpiredException
        extends ApiException {

    public GuestSessionExpiredException() {
        super(
                HttpStatus.UNAUTHORIZED,
                "GUEST_SESSION_EXPIRED",
                "Guest Session이 만료되었거나 유효하지 않습니다."
        );
    }
}