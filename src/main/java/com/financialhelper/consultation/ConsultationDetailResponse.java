package com.financialhelper.consultation;

import java.time.OffsetDateTime;
import java.util.UUID;

public record ConsultationDetailResponse(
        UUID consultationId,
        ConsultationCategory category,
        ConsultationScenario scenario,
        String situationText,
        ConsultationStatus status,
        ConsultationStep currentStep,
        OffsetDateTime updatedAt
) {

    public static ConsultationDetailResponse from(
            Consultation consultation
    ) {
        return new ConsultationDetailResponse(
                consultation.getId(),
                consultation.getCategory(),
                consultation.getScenario(),
                consultation.getSituationText(),
                consultation.getStatus(),
                consultation.getCurrentStep(),
                consultation.getUpdatedAt()
        );
    }
}
