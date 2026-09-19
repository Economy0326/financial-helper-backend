package com.financialhelper.consultation;

import com.financialhelper.common.error.ApiException;
import org.springframework.http.HttpStatus;

/**
 * Raised before an analysis job is created when confirmed facts are outside
 * the currently approved ProcedureVersion scope.
 */
public class UnsupportedConsultationScopeException extends ApiException {
    public UnsupportedConsultationScopeException() {
        super(
                HttpStatus.UNPROCESSABLE_CONTENT,
                "CONSULTATION_SCOPE_UNSUPPORTED",
                "현재 확인된 내용은 지원 범위 밖입니다. 상담 내용을 다시 확인해 주세요."
        );
    }
}
