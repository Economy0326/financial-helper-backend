package com.financialhelper.ai.information;

import java.util.List;
import java.util.UUID;

/** Server-authored presentation of reviewed evidence; never an AI-authored financial conclusion. */
public record InformationExplanation(UUID supportChunkId, String mode, String easyText,
        List<InformationEvidenceSnapshot.RelatedText> conditions,
        List<InformationEvidenceSnapshot.RelatedText> exceptions) {
    public InformationExplanation {
        conditions = List.copyOf(conditions);
        exceptions = List.copyOf(exceptions);
    }
}
