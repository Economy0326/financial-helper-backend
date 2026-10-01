package com.financialhelper.consultation;

public record ConfirmSuggestedScenarioResponse(ConsultationScenario selectedScenario,
        ScenarioAlignment scenarioAlignment, long caseInputRevision, ConsultationStep nextStep) {}
