package com.financialhelper.ai.report;

import com.financialhelper.ai.grounded.GroundedEvidenceCitation;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

public class ConsultationReportAiResult {

    @NotBlank
    @Size(max = 120)
    @JsonPropertyDescription(
            "A short Korean title for the solution report."
    )
    public String headline;

    @NotBlank
    @Size(max = 1500)
    @JsonPropertyDescription(
            "A concise Korean summary of the confirmed financial situation."
    )
    public String caseSummary;

    @NotNull
    @Valid
    public FirstAction firstAction;

    @NotNull
    @Size(min = 1, max = 6)
    @Valid
    public List<KeyIssue> keyIssues;

    @NotNull
    @Size(min = 1, max = 6)
    @Valid
    public List<ActionStep> actionSteps;

    @NotNull
    @Size(max = 6)
    @Valid
    public List<ActionConsequence> actionConsequences;

    @NotNull
    @Size(max = 8)
    @Valid
    public List<RequiredDocument> requiredDocuments;

    @NotNull
    @Size(max = 8)
    @Valid
    public List<FinancialTerm> terms;

    @NotNull
    @Valid
    public ComplaintDraft complaintDraft;

    /** Required for CARD grounded reports; empty for legacy reports. */
    @Size(max = 20)
    @Valid
    @JsonPropertyDescription(
            "Typed evidence IDs and locators from the supplied CARD evidence snapshot. Never invent URLs or legal citations."
    )
    public List<GroundedEvidenceCitation> evidenceCitations = List.of();


    public static class FirstAction {

        @Size(max = 120)
        public String actionId;

        @NotBlank
        @Size(max = 150)
        public String title;

        @NotBlank
        @Size(max = 600)
        public String description;
    }


    public static class KeyIssue {

        @NotBlank
        @Size(max = 150)
        public String title;

        @NotBlank
        @Size(max = 600)
        public String explanation;
    }


    public static class ActionStep {

        @Size(max = 120)
        public String actionId;

        @Min(1)
        @Max(6)
        public int order;

        @NotBlank
        @Size(max = 150)
        public String title;

        @NotBlank
        @Size(max = 700)
        public String description;
    }


    public static class ActionConsequence {

        @NotBlank
        @Size(max = 180)
        public String action;

        @NotBlank
        @Size(max = 500)
        public String consequence;
    }


    public static class RequiredDocument {

        @Size(max = 120)
        public String documentId;

        @NotBlank
        @Size(max = 160)
        public String name;

        @NotBlank
        @Size(max = 500)
        public String reason;
    }


    public static class FinancialTerm {

        @NotBlank
        @Size(max = 100)
        public String term;

        @NotBlank
        @Size(max = 500)
        public String explanation;
    }


    public static class ComplaintDraft {

        @NotBlank
        @Size(max = 200)
        public String subject;

        @NotBlank
        @Size(max = 3000)
        public String body;
    }
}
