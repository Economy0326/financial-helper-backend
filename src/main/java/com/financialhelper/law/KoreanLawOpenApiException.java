package com.financialhelper.law;

/** Direct Korean Law Open API 경계의 fail-closed 예외다. */
public class KoreanLawOpenApiException extends RuntimeException {

    public KoreanLawOpenApiException(String message) {
        super(message);
    }

    public KoreanLawOpenApiException(String message, Throwable cause) {
        super(message, cause);
    }
}
