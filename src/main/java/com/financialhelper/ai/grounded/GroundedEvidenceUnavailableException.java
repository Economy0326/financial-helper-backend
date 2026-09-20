package com.financialhelper.ai.grounded;

/** grounded CARD context를 구성할 수 없을 때의 fail-closed 신호다. */
public class GroundedEvidenceUnavailableException extends RuntimeException {
    public GroundedEvidenceUnavailableException(String message) {
        super(message);
    }

    public GroundedEvidenceUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
