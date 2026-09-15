package com.financialhelper.procedure;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(
        name = "procedure_version",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_procedure_version_identity",
                columnNames = {"scenario", "institution", "product_type", "version"}
        )
)
public class ProcedureVersion {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, length = 100)
    private String scenario;

    @Column(nullable = false, length = 200)
    private String institution;

    @Column(name = "product_type", nullable = false, length = 100)
    private String productType;

    @Column(nullable = false)
    private int version;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private ProcedureStatus status;

    @Column(name = "applicability_start_date")
    private LocalDate applicabilityStartDate;

    @Column(name = "applicability_end_date")
    private LocalDate applicabilityEndDate;

    @Column(name = "required_facts_json", nullable = false, columnDefinition = "TEXT")
    private String requiredFactsJson;

    @Column(name = "condition_rules_json", nullable = false, columnDefinition = "TEXT")
    private String conditionRulesJson;

    @Column(name = "action_steps_json", nullable = false, columnDefinition = "TEXT")
    private String actionStepsJson;

    @Column(name = "document_requirements_json", nullable = false, columnDefinition = "TEXT")
    private String documentRequirementsJson;

    @Column(name = "evidence_references_json", nullable = false, columnDefinition = "TEXT")
    private String evidenceReferencesJson;

    @Column(name = "reviewed_contacts_json", nullable = false, columnDefinition = "TEXT")
    private String reviewedContactsJson;

    @Column(name = "reviewed_values_json", nullable = false, columnDefinition = "TEXT")
    private String reviewedValuesJson;

    @Column(name = "conditions_exceptions_json", nullable = false, columnDefinition = "TEXT")
    private String conditionsExceptionsJson;

    @Column(name = "review_notes", columnDefinition = "TEXT")
    private String reviewNotes;

    @Column(name = "reviewed_by", length = 200)
    private String reviewedBy;

    @Column(name = "reviewed_at")
    private OffsetDateTime reviewedAt;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    protected ProcedureVersion() {
    }

    public ProcedureVersion(
            String scenario,
            String institution,
            String productType,
            int version,
            ProcedureStatus status,
            LocalDate applicabilityStartDate,
            LocalDate applicabilityEndDate,
            String requiredFactsJson,
            String conditionRulesJson,
            String actionStepsJson,
            String documentRequirementsJson,
            String evidenceReferencesJson,
            String reviewedContactsJson,
            String reviewedValuesJson,
            String conditionsExceptionsJson,
            String reviewNotes,
            String reviewedBy,
            OffsetDateTime reviewedAt,
            OffsetDateTime now
    ) {
        this.scenario = requireText(scenario, "scenario");
        this.institution = requireText(institution, "institution");
        this.productType = requireText(productType, "productType");
        if (version < 1) {
            throw new IllegalArgumentException("version must be positive");
        }
        this.version = version;
        this.status = status == null ? ProcedureStatus.DRAFT : status;
        this.applicabilityStartDate = applicabilityStartDate;
        this.applicabilityEndDate = applicabilityEndDate;
        this.requiredFactsJson = requireText(requiredFactsJson, "requiredFactsJson");
        this.conditionRulesJson = requireText(conditionRulesJson, "conditionRulesJson");
        this.actionStepsJson = requireText(actionStepsJson, "actionStepsJson");
        this.documentRequirementsJson = requireText(documentRequirementsJson, "documentRequirementsJson");
        this.evidenceReferencesJson = requireText(evidenceReferencesJson, "evidenceReferencesJson");
        this.reviewedContactsJson = requireText(reviewedContactsJson, "reviewedContactsJson");
        this.reviewedValuesJson = requireText(reviewedValuesJson, "reviewedValuesJson");
        this.conditionsExceptionsJson = requireText(conditionsExceptionsJson, "conditionsExceptionsJson");
        this.reviewNotes = reviewNotes;
        this.reviewedBy = reviewedBy;
        this.reviewedAt = reviewedAt;
        this.createdAt = now == null ? OffsetDateTime.now() : now;
        this.updatedAt = this.createdAt;
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    public UUID getId() { return id; }
    public String getScenario() { return scenario; }
    public String getInstitution() { return institution; }
    public String getProductType() { return productType; }
    public int getVersion() { return version; }
    public ProcedureStatus getStatus() { return status; }
    public LocalDate getApplicabilityStartDate() { return applicabilityStartDate; }
    public LocalDate getApplicabilityEndDate() { return applicabilityEndDate; }
    public String getRequiredFactsJson() { return requiredFactsJson; }
    public String getConditionRulesJson() { return conditionRulesJson; }
    public String getActionStepsJson() { return actionStepsJson; }
    public String getDocumentRequirementsJson() { return documentRequirementsJson; }
    public String getEvidenceReferencesJson() { return evidenceReferencesJson; }
    public String getReviewedContactsJson() { return reviewedContactsJson; }
    public String getReviewedValuesJson() { return reviewedValuesJson; }
    public String getConditionsExceptionsJson() { return conditionsExceptionsJson; }
    public String getReviewNotes() { return reviewNotes; }
    public String getReviewedBy() { return reviewedBy; }
    public OffsetDateTime getReviewedAt() { return reviewedAt; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }

    public boolean isApproved() {
        return status == ProcedureStatus.APPROVED;
    }
}
