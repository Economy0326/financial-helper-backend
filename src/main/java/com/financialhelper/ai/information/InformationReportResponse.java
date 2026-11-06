package com.financialhelper.ai.information;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** Candidate-only information; status never asserts personal legal or financial applicability. */
public record InformationReportResponse(UUID consultationId, String status, String reason,
        OffsetDateTime generatedAt, InformationEvidenceSnapshot snapshot,
        List<InformationEvidenceSnapshot.Evidence> selectedEvidence) {
    public InformationReportResponse {
        selectedEvidence = List.copyOf(selectedEvidence);
    }
}
