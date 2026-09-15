package com.financialhelper.common.error;

import java.util.List;
import java.time.OffsetDateTime;

public record ApiErrorResponse(
        ApiError error
) {

    public record ApiError(
            String code,
            String message,
            List<FieldError> fieldErrors,
            String requestId,
            OffsetDateTime nextAvailableAt
    ) {
    }

    public record FieldError(
            String field,
            String reason
    ) {
    }
}
