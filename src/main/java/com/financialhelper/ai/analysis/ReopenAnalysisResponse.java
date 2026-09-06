package com.financialhelper.ai.analysis;

import java.util.UUID;

public record ReopenAnalysisResponse(
        UUID consultationId,
        String nextStep
) {
}