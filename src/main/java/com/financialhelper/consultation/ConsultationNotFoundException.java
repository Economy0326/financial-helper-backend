package com.financialhelper.consultation;

import com.financialhelper.common.error.ApiException;
import org.springframework.http.HttpStatus;

public class ConsultationNotFoundException
        extends ApiException {

    public ConsultationNotFoundException() {
        super(
                HttpStatus.NOT_FOUND,
                "CONSULTATION_NOT_FOUND",
                "상담을 찾을 수 없습니다."
        );
    }
}