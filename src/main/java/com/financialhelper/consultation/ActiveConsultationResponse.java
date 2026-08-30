package com.financialhelper.consultation;

import java.time.OffsetDateTime;
import java.util.UUID;

public record ActiveConsultationResponse(
        UUID consultationId,
        ConsultationCategory category,
        ConsultationStatus status,
        ConsultationStep currentStep,
        OffsetDateTime updatedAt
) {

    public static ActiveConsultationResponse from(
            Consultation consultation
    ) {
        return new ActiveConsultationResponse(
                consultation.getId(),
                consultation.getCategory(),
                consultation.getStatus(),
                consultation.getCurrentStep(),
                consultation.getUpdatedAt()
        );
    }
}