package com.financialhelper.consultation;

import java.util.Optional;

public final class ConsultationStartResult {

    private final ConsultationCreateResponse response;
    private final String rawTokenToSet;

    public ConsultationStartResult(
            ConsultationCreateResponse response,
            String rawTokenToSet
    ) {
        this.response = response;
        this.rawTokenToSet = rawTokenToSet;
    }

    public ConsultationCreateResponse getResponse() {
        return response;
    }

    public Optional<String> getRawTokenToSet() {
        return Optional.ofNullable(rawTokenToSet);
    }
}