package com.financialhelper.consultation;

public record UpdateConsultationCategoryResponse(
        ConsultationStep currentStep
) {

    public static UpdateConsultationCategoryResponse from(
            Consultation consultation
    ) {
        return new UpdateConsultationCategoryResponse(
                consultation.getCurrentStep()
        );
    }
}