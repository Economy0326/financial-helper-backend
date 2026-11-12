package com.financialhelper.source;

import com.financialhelper.common.error.ApiException;
import org.springframework.http.HttpStatus;

public class SourceReviewException extends ApiException {
    public SourceReviewException(HttpStatus status, String code) {
        super(status, code, "공식 자료 검토 요청을 처리할 수 없습니다.");
    }
}
