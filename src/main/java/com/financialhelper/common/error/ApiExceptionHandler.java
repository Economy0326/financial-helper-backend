package com.financialhelper.common.error;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.List;
import java.util.UUID;

@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log =
            LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiErrorResponse> handleApiException(
            ApiException exception
    ) {
        String requestId =
                "req-" + UUID.randomUUID();

        // 로그에는 상담 원문이나 Raw Guset Token은 넣지 않는다
        log.warn(
                "API error requestId={}, code={}, status={}",
                requestId,
                exception.getCode(),
                exception.getStatus().value()
        );

        ApiErrorResponse response =
                new ApiErrorResponse(
                        new ApiErrorResponse.ApiError(
                                exception.getCode(),
                                exception.getMessage(),
                                List.of(),
                                requestId
                        )
                );

        return ResponseEntity
                .status(exception.getStatus())
                .body(response);
    }
}