package com.financialhelper.ai.report;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface ConsultationReportRepository
        extends JpaRepository<ConsultationReport, UUID> {

    Optional<ConsultationReport>
    findByConsultation_IdAndCaseInputRevisionAndFollowUpAnswerRevision(
            UUID consultationId,
            long caseInputRevision,
            long followUpAnswerRevision
    );

    Optional<ConsultationReport> findFirstByConsultation_IdOrderByGeneratedAtDesc(UUID consultationId);

    List<ConsultationReport> findByConsultation_IdInOrderByGeneratedAtDescIdDesc(Collection<UUID> consultationIds);

    Optional<ConsultationReport> findByConsultation_IdAndConsultation_Account_Id(
            UUID consultationId, UUID accountId);
}
