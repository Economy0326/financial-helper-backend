package com.financialhelper.procedure;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record ProcedureVersionData(
        UUID id,
        String scenario,
        String institution,
        String productType,
        int version,
        ProcedureStatus status,
        LocalDate applicabilityStartDate,
        LocalDate applicabilityEndDate,
        List<RequiredFact> requiredFacts,
        List<ConditionRule> conditionRules,
        List<ActionStep> actionSteps,
        List<DocumentRequirement> documentRequirements,
        List<EvidenceReference> evidenceReferences,
        List<ReviewedContact> reviewedContacts,
        List<ReviewedValue> reviewedValues,
        List<String> conditionsExceptions,
        String reviewNotes,
        String reviewedBy,
        OffsetDateTime reviewedAt
) {
    public ProcedureVersionData {
        requiredFacts = requiredFacts == null ? List.of() : List.copyOf(requiredFacts);
        conditionRules = conditionRules == null ? List.of() : List.copyOf(conditionRules);
        actionSteps = actionSteps == null ? List.of() : List.copyOf(actionSteps);
        documentRequirements = documentRequirements == null ? List.of() : List.copyOf(documentRequirements);
        evidenceReferences = evidenceReferences == null ? List.of() : List.copyOf(evidenceReferences);
        reviewedContacts = reviewedContacts == null ? List.of() : List.copyOf(reviewedContacts);
        reviewedValues = reviewedValues == null ? List.of() : List.copyOf(reviewedValues);
        conditionsExceptions = conditionsExceptions == null ? List.of() : List.copyOf(conditionsExceptions);
    }

    public record RequiredFact(String key, String type, boolean requiredForDecision) {
    }

    public record ConditionRule(String actionId, ConditionExpression expression) {
    }

    public record ActionStep(
            String actionId,
            int order,
            String title,
            String description,
            String channelRef,
            List<String> evidenceRoles
    ) {
        public ActionStep {
            evidenceRoles = evidenceRoles == null ? List.of() : List.copyOf(evidenceRoles);
        }
    }

    public record DocumentRequirement(
            String documentId,
            String title,
            String status,
            ConditionExpression condition,
            String evidenceRef
    ) {
    }

    public record EvidenceReference(
            String sourceKey,
            int documentVersion,
            String expectedRawSha256,
            String articleReference,
            String pageReference,
            String role,
            boolean historicalApplicabilityRequired
    ) {
        public EvidenceReference(
                String sourceKey,
                int documentVersion,
                String expectedRawSha256,
                String articleReference,
                String pageReference,
                String role
        ) {
            this(sourceKey, documentVersion, expectedRawSha256, articleReference,
                    pageReference, role, true);
        }
    }

    public record ReviewedContact(String key, String value, String evidenceRef) {
    }

    public record ReviewedValue(String key, String value, String evidenceRef) {
    }
}
