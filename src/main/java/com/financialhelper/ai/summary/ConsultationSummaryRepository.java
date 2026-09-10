package com.financialhelper.ai.summary;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface ConsultationSummaryRepository
        extends JpaRepository<ConsultationSummary, UUID> {

    Optional<ConsultationSummary>
    findByConsultation_IdAndCaseInputRevisionAndFollowUpAnswerRevision(
            UUID consultationId,
            long caseInputRevision,
            long followUpAnswerRevision
    );
}