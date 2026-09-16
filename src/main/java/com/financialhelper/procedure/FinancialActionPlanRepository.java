package com.financialhelper.procedure;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface FinancialActionPlanRepository extends JpaRepository<FinancialActionPlan, UUID> {
    Optional<FinancialActionPlan> findByConsultation_IdAndProcedureVersion_IdAndCaseInputRevisionAndFollowUpAnswerRevision(
            UUID consultationId,
            UUID procedureVersionId,
            long caseInputRevision,
            long followUpAnswerRevision
    );

    Optional<FinancialActionPlan> findTopByConsultation_IdOrderByCaseInputRevisionDescFollowUpAnswerRevisionDesc(
            UUID consultationId
    );
}
