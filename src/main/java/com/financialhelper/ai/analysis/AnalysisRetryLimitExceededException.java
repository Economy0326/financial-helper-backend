package com.financialhelper.ai.analysis;

import com.financialhelper.common.error.ApiException;

import org.springframework.http.HttpStatus;

public class AnalysisRetryLimitExceededException
        extends ApiException {

    public AnalysisRetryLimitExceededException() {
        super(
                HttpStatus.CONFLICT,
                "ANALYSIS_RETRY_LIMIT_EXCEEDED",
                "분석 재시도 횟수를 모두 사용했습니다."
        );
    }
}