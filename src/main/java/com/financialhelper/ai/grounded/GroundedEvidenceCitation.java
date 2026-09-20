package com.financialhelper.ai.grounded;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** typed citation reference이며 검증하지 않은 URL이나 문장을 담지 않는다. */
public class GroundedEvidenceCitation {
    @NotBlank
    @Size(max = 220)
    public String evidenceId;

    @NotBlank
    @Size(max = 220)
    public String locator;

    @Size(max = 240)
    public String label;
}
