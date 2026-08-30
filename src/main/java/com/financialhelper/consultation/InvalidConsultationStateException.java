package com.financialhelper.consultation;

import com.financialhelper.common.error.ApiException;
import org.springframework.http.HttpStatus;

public class InvalidConsultationStateException
        extends ApiException {

    public InvalidConsultationStateException() {
        super(
                HttpStatus.CONFLICT,
                "INVALID_CONSULTATION_STATE",
                "현재 상담 상태에서는 요청을 처리할 수 없습니다."
        );
    }
}