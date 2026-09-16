package com.financialhelper.source;

public class SourceChunkingException extends RuntimeException {

    public SourceChunkingException(String message) {
        super(message);
    }

    public SourceChunkingException(String message, Throwable cause) {
        super(message, cause);
    }
}
