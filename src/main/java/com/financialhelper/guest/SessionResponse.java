package com.financialhelper.guest;

import java.util.UUID;

// GET /api/v1/session
public record SessionResponse(
        boolean guest,
        boolean hasActiveConsultation,
        boolean authenticated,
        UUID accountId,
        String provider
) {
    public SessionResponse(boolean guest, boolean hasActiveConsultation) {
        this(guest, hasActiveConsultation, false, null, null);
    }
}
