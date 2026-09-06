package com.financialhelper.ai.report;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface ConsultationReportRepository
        extends JpaRepository<ConsultationReport, UUID> {

    Optional<ConsultationReport>
    findByConsultation_IdAndCaseInputRevisionAndFollowUpAnswerRevision(
            UUID consultationId,
            long caseInputRevision,
            long followUpAnswerRevision
    );
}