package com.financialhelper.consultation;

import java.util.UUID;

public record UpdateConsultationSituationResponse(
        UUID consultationId,
        ConsultationStep currentStep
) {

    public static UpdateConsultationSituationResponse from(
            Consultation consultation
    ) {
        return new UpdateConsultationSituationResponse(
                consultation.getId(),
                consultation.getCurrentStep()
        );
    }
}