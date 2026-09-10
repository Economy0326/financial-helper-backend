package com.financialhelper.ai.followup;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record UpdateFollowUpAnswerRequest(

        @NotBlank
        @Size(max = 64)
        String answer

) {
}