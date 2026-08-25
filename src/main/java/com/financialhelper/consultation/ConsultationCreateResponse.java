package com.financialhelper.consultation;

import java.util.UUID;

public record ConsultationCreateResponse(
        UUID consultationId,
        ConsultationStatus status,
        ConsultationStep currentStep
) {

    public static ConsultationCreateResponse from(
            Consultation consultation
    ) {
        return new ConsultationCreateResponse(
                consultation.getId(),
                consultation.getStatus(),
                consultation.getCurrentStep()
        );
    }
}