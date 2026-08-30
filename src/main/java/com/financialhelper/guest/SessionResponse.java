package com.financialhelper.guest;

// GET /api/v1/session
public record SessionResponse(
        boolean guest,
        boolean hasActiveConsultation
) {
}