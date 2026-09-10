package com.financialhelper.emergency;

import java.util.Optional;

public final class EmergencySelectionResult {

    private final EmergencySelectionResponse response;
    private final String rawTokenToSet;

    public EmergencySelectionResult(
            EmergencySelectionResponse response,
            // 기존 Guest 쿠키가 있으면 rawTokenToSet은 null
            // 아니면 새로운 raw-token
            String rawTokenToSet
    ) {
        this.response = response;
        this.rawTokenToSet = rawTokenToSet;
    }

    public EmergencySelectionResponse getResponse() {
        return response;
    }

    public Optional<String> getRawTokenToSet() {
        return Optional.ofNullable(rawTokenToSet);
    }
}