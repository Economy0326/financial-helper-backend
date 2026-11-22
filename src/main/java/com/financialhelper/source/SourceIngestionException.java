package com.financialhelper.source;

public class SourceIngestionException
        extends RuntimeException {

    private final String code;
    private final Integer httpStatus;
    private final String responseUrl;

    // 직접 발견한 오류
    public SourceIngestionException(
            String code,
            String message
    ) {
        super(message);

        this.code =
                code;
        this.httpStatus = null;
        this.responseUrl = null;
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
        this.httpStatus = null;
        this.responseUrl = null;
    }

    public SourceIngestionException(String code, String message, int httpStatus) {
        this(code, message, httpStatus, null);
    }

    public SourceIngestionException(String code, String message, int httpStatus, String responseUrl) {
        super(message);
        this.code = code;
        this.httpStatus = httpStatus;
        this.responseUrl = responseUrl;
    }

    public String getCode() {
        return code;
    }

    public Integer getHttpStatus() { return httpStatus; }
    public String getResponseUrl() { return responseUrl; }
}
