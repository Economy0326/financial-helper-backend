package com.financialhelper.consultation;

import jakarta.validation.constraints.NotNull;

// PUT /api/v1/consultations/{id}/category
public record UpdateConsultationCategoryRequest(

        @NotNull
        ConsultationCategory category,

        ConsultationScenario scenario

) {
    public UpdateConsultationCategoryRequest(ConsultationCategory category) {
        this(category, null);
    }
}
