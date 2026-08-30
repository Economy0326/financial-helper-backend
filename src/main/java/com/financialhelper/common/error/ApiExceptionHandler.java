package com.financialhelper.common.error;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.List;
import java.util.UUID;

@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log =
            LoggerFactory.getLogger(ApiExceptionHandler.class);

    // 비즈니스 / 상태 / 세션 등 애플리케이션 예외 처리
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

    // @Valid 검증 실패 처리 (@NotNull, @NotBlank 등)
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiErrorResponse>
    handleValidationException(
            MethodArgumentNotValidException exception
    ) {
        String requestId =
                "req-" + UUID.randomUUID();

        List<ApiErrorResponse.FieldError> fieldErrors =
                exception.getBindingResult()
                        .getFieldErrors()
                        .stream()
                        .map(error ->
                                new ApiErrorResponse.FieldError(
                                        error.getField(),
                                        validationReason(
                                                error.getCode()
                                        )
                                )
                        )
                        .toList();

        log.warn(
                "Validation error requestId={}, fieldCount={}",
                requestId,
                fieldErrors.size()
        );

        ApiErrorResponse response =
                new ApiErrorResponse(
                        new ApiErrorResponse.ApiError(
                                "VALIDATION_ERROR",
                                "입력 내용을 확인해 주세요.",
                                fieldErrors,
                                requestId
                        )
                );

        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(response);
    }

    // 잘못된 JSON 형식 또는 변환할 수 없는 값(Enum 등) 처리
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiErrorResponse>
    handleUnreadableMessage(
            HttpMessageNotReadableException exception
    ) {
        String requestId =
                "req-" + UUID.randomUUID();

        log.warn(
                "Invalid request body requestId={}",
                requestId
        );

        ApiErrorResponse response =
                new ApiErrorResponse(
                        new ApiErrorResponse.ApiError(
                                "VALIDATION_ERROR",
                                "입력 내용을 확인해 주세요.",
                                List.of(),
                                requestId
                        )
                );

        return ResponseEntity
                .badRequest()
                .body(response);
    }

    // PathVariable / RequestParam 타입 변환 실패 처리
    // ex) UUID 자리에 'abc'같은 잘못된 값이 들어온 경우
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiErrorResponse>
    handleTypeMismatch(
            MethodArgumentTypeMismatchException exception
    ) {
        String requestId =
                "req-" + UUID.randomUUID();

        log.warn(
                "Request parameter type mismatch requestId={}",
                requestId
        );

        ApiErrorResponse response =
                new ApiErrorResponse(
                        new ApiErrorResponse.ApiError(
                                "VALIDATION_ERROR",
                                "입력 내용을 확인해 주세요.",
                                List.of(),
                                requestId
                        )
                );

        return ResponseEntity
                .badRequest()
                .body(response);
    }

    // 위에서 처리하지 못한 예상 밖의 서버 오류
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiErrorResponse>
    handleUnexpectedException(
            Exception exception
    ) {
        String requestId =
                "req-" + UUID.randomUUID();

        log.error(
                "Unexpected API error requestId={}, type={}",
                requestId,
                exception.getClass().getSimpleName()
        );

        ApiErrorResponse response =
                new ApiErrorResponse(
                        new ApiErrorResponse.ApiError(
                                "INTERNAL_SERVER_ERROR",
                                "일시적인 오류가 발생했습니다.",
                                List.of(),
                                requestId
                        )
                );

        return ResponseEntity
                .status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(response);
    }

    // Bean Validation 에러 코드를 API fieldErrors의 reason 값으로 변환
    private String validationReason(
            String validationCode
    ) {
        if ("NotBlank".equals(validationCode)
                || "NotNull".equals(validationCode)) {
            return "REQUIRED";
        }

        return "VALIDATION_ERROR";
    }
}