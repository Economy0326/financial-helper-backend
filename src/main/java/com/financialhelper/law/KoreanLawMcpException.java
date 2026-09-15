package com.financialhelper.law;

/** Fail-closed error at the external MCP boundary. */
public class KoreanLawMcpException extends RuntimeException {

    public KoreanLawMcpException(String message) {
        super(message);
    }

    public KoreanLawMcpException(String message, Throwable cause) {
        super(message, cause);
    }
}
