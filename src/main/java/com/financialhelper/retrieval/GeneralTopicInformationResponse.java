package com.financialhelper.retrieval;

import com.financialhelper.consultation.FinancialTopic;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Candidate discovery only. No financial applicability or answer is asserted. */
public record GeneralTopicInformationResponse(
        UUID consultationId, long caseInputRevision, FinancialTopic topic,
        LocalDate asOfDate, String status, List<String> coverageGaps,
        List<ConsultationOfficialInformationResponse.Candidate> candidates,
        GeneralTopicInformationRequest.TimeScope timeScope,
        long followUpAnswerRevision
) {
    public GeneralTopicInformationResponse(UUID consultationId, long caseInputRevision,
            FinancialTopic topic, LocalDate asOfDate, String status, List<String> coverageGaps,
            List<ConsultationOfficialInformationResponse.Candidate> candidates) {
        this(consultationId, caseInputRevision, topic, asOfDate, status, coverageGaps,
                candidates, null, 0);
    }
    public GeneralTopicInformationResponse {
        coverageGaps = List.copyOf(coverageGaps);
        candidates = List.copyOf(candidates);
    }
}
