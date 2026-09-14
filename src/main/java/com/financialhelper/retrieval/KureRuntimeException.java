package com.financialhelper.retrieval;

public class KureRuntimeException extends RuntimeException {

    public KureRuntimeException(String message) {
        super(message);
    }

    public KureRuntimeException(String message, Throwable cause) {
        super(message, cause);
    }
}
