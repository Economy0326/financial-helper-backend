package com.financialhelper.source;

public class SourceIngestionException
        extends RuntimeException {

    private final String code;

    // 직접 발견한 오류
    public SourceIngestionException(
            String code,
            String message
    ) {
        super(message);

        this.code =
                code;
    }

    // 다른 예외(하위 계층 예외)를 원인으로 감싸서 전달
    public SourceIngestionException(
            String code,
            String message,
            Throwable cause
    ) {
        super(
                message,
                cause
        );

        this.code =
                code;
    }

    public String getCode() {
        return code;
    }
}