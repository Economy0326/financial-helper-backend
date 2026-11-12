package com.financialhelper.ai.information;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.Map;
import java.util.LinkedHashMap;

/** Candidate-only information; status never asserts personal legal or financial applicability. */
public record InformationReportResponse(UUID consultationId, String status, String reason,
        OffsetDateTime generatedAt, InformationEvidenceSnapshot snapshot,
        List<InformationEvidenceSnapshot.Evidence> selectedEvidence,
        List<InformationExplanation> explanations, Map<UUID, String> evidenceProvenance,
        List<InformationNextAction> nextActions) {
    /** No independent provenance record exists yet. A V38 review does not determine origin. */
    public InformationReportResponse(UUID consultationId, String status, String reason,
            OffsetDateTime generatedAt, InformationEvidenceSnapshot snapshot,
            List<InformationEvidenceSnapshot.Evidence> selectedEvidence, List<InformationExplanation> explanations) {
        this(consultationId, status, reason, generatedAt, snapshot, selectedEvidence, explanations,
                unknownProvenance(selectedEvidence), InformationNextAction.from(selectedEvidence));
    }
    public InformationReportResponse {
        selectedEvidence = List.copyOf(selectedEvidence);
        explanations = List.copyOf(explanations);
        evidenceProvenance = Map.copyOf(evidenceProvenance);
        nextActions = List.copyOf(nextActions);
    }

    private static Map<UUID, String> unknownProvenance(List<InformationEvidenceSnapshot.Evidence> evidence) {
        Map<UUID, String> result = new LinkedHashMap<>();
        evidence.forEach(item -> result.put(item.supportChunkId(), "UNKNOWN"));
        return result;
    }
}
