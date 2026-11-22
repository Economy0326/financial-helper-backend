package com.financialhelper.source;

import com.financialhelper.common.error.ApiException;
import org.springframework.http.HttpStatus;

public class SourceReviewException extends ApiException {
    public SourceReviewException(HttpStatus status, String code) {
        super(status, code, "SOURCE_REVIEW_CURRENTNESS_UNVERIFIED".equals(code)
                ? "공식 안내의 현재 적용 범위를 확인할 근거가 부족합니다."
                : "공식 자료 검토 요청을 처리할 수 없습니다.");
    }
}
