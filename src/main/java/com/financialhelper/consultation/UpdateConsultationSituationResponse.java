package com.financialhelper.consultation;

import java.util.UUID;

public record UpdateConsultationSituationResponse(
        UUID consultationId,
        ConsultationStep currentStep,
        long caseInputRevision,
        ScenarioAlignment scenarioAlignment,
        ConsultationScenario selectedScenario,
        ConsultationScenario suggestedScenario
) {

    public static UpdateConsultationSituationResponse from(
            Consultation consultation
    ) {
        ConsultationScenario selected = consultation.getScenario();
        return from(consultation, selected, ConsultationScenarioResolver.resolve(consultation));
    }

    public static UpdateConsultationSituationResponse from(
            Consultation consultation, ConsultationScenario selected, ConsultationScenario resolved
    ) {
        ScenarioAlignment alignment;
        ConsultationScenario suggested = null;
        if (resolved == ConsultationScenario.UNKNOWN) {
            alignment = ScenarioAlignment.NEEDS_CLARIFICATION;
        } else if (selected != null && selected != ConsultationScenario.UNKNOWN && selected != resolved) {
            alignment = ScenarioAlignment.SUPPORTED_SCENARIO_MISMATCH;
            suggested = resolved;
        } else {
            alignment = ScenarioAlignment.SELECTED_SCENARIO_MATCH;
        }
        return new UpdateConsultationSituationResponse(consultation.getId(), consultation.getCurrentStep(),
                consultation.getCaseInputRevision(),
                alignment, selected, suggested);
    }
}
