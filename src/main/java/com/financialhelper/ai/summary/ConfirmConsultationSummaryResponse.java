package com.financialhelper.ai.summary;

import java.util.UUID;

public record ConfirmConsultationSummaryResponse(
        UUID consultationId,
        String nextStep
) {
}