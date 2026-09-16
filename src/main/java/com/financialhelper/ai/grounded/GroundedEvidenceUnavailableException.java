package com.financialhelper.ai.grounded;

/** Fail-closed signal when a grounded CARD context cannot be assembled. */
public class GroundedEvidenceUnavailableException extends RuntimeException {
    public GroundedEvidenceUnavailableException(String message) {
        super(message);
    }

    public GroundedEvidenceUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
