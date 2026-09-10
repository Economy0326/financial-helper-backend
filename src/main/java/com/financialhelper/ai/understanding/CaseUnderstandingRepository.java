package com.financialhelper.ai.understanding;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface CaseUnderstandingRepository
        extends JpaRepository<CaseUnderstanding, UUID> {

    Optional<CaseUnderstanding>
    findByConsultation_IdAndCaseInputRevision(
            UUID consultationId,
            long caseInputRevision
    );
}