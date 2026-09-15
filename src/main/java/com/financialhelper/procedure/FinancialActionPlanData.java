package com.financialhelper.procedure;

import java.util.List;
import java.util.UUID;

public record FinancialActionPlanData(
        UUID id,
        UUID consultationId,
        UUID confirmedCaseSnapshotId,
        UUID procedureVersionId,
        String scenario,
        int procedureVersion,
        long caseInputRevision,
        long followUpAnswerRevision,
        PlanStatus status,
        List<Action> actions,
        List<Document> requiredDocuments,
        List<EvidenceBinding> evidence,
        List<String> unresolvedFacts,
        List<String> coverageGaps,
        List<String> warnings
) {
    public FinancialActionPlanData {
        actions = actions == null ? List.of() : List.copyOf(actions);
        requiredDocuments = requiredDocuments == null ? List.of() : List.copyOf(requiredDocuments);
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
        unresolvedFacts = unresolvedFacts == null ? List.of() : List.copyOf(unresolvedFacts);
        coverageGaps = coverageGaps == null ? List.of() : List.copyOf(coverageGaps);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }

    public record Action(
            String actionId,
            int order,
            String title,
            String description,
            ConditionResult conditionResult,
            String channelRef
    ) {
    }

    public record Document(
            String documentId,
            String title,
            String status,
            ConditionResult conditionResult,
            String evidenceRef
    ) {
    }

    public record EvidenceBinding(
            String sourceKey,
            UUID sourceDocumentId,
            int documentVersion,
            List<UUID> sourceChunkIds,
            String articleReference,
            String pageReference,
            String role
    ) {
        public EvidenceBinding {
            sourceChunkIds = sourceChunkIds == null ? List.of() : List.copyOf(sourceChunkIds);
        }
    }
}
