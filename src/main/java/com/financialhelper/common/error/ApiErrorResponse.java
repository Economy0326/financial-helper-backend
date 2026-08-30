package com.financialhelper.common.error;

import java.util.List;

public record ApiErrorResponse(
        ApiError error
) {

    public record ApiError(
            String code,
            String message,
            List<FieldError> fieldErrors,
            String requestId
    ) {
    }

    public record FieldError(
            String field,
            String reason
    ) {
    }
}