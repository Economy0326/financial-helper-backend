package com.financialhelper.ai.grounded;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Typed citation reference. It never carries an unvalidated URL or text. */
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
