package com.financialhelper.consultation;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record UpdateConsultationSituationRequest(

        @NotBlank
        @Size(max = 1000)
        String situationText

) {
}