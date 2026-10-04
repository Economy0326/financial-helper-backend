package com.financialhelper.consultation;

import jakarta.validation.constraints.NotNull;

public record ConfirmSuggestedScenarioRequest(@NotNull ConsultationScenario scenario, long expectedCaseInputRevision) {}
