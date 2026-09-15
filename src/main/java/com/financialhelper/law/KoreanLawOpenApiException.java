package com.financialhelper.law;

/** Fail-closed exception for the direct Korean Law Open API boundary. */
public class KoreanLawOpenApiException extends RuntimeException {

    public KoreanLawOpenApiException(String message) {
        super(message);
    }

    public KoreanLawOpenApiException(String message, Throwable cause) {
        super(message, cause);
    }
}
