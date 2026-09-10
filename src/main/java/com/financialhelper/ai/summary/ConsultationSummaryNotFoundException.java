package com.financialhelper.ai.summary;

import com.financialhelper.common.error.ApiException;

import org.springframework.http.HttpStatus;

public class ConsultationSummaryNotFoundException
        extends ApiException {

    public ConsultationSummaryNotFoundException() {
        super(
                HttpStatus.NOT_FOUND,
                "CONSULTATION_SUMMARY_NOT_FOUND",
                "상담 요약을 찾을 수 없습니다."
        );
    }
}