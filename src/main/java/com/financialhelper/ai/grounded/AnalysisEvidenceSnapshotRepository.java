package com.financialhelper.ai.grounded;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface AnalysisEvidenceSnapshotRepository
        extends JpaRepository<AnalysisEvidenceSnapshot, UUID> {

    Optional<AnalysisEvidenceSnapshot> findByAnalysisJob_IdAndCaseInputRevisionAndFollowUpAnswerRevision(
            UUID analysisJobId, long caseInputRevision, long followUpAnswerRevision);

    @Query("select snapshot from AnalysisEvidenceSnapshot snapshot "
            + "join fetch snapshot.analysisJob "
            + "where snapshot.id = :id")
    Optional<AnalysisEvidenceSnapshot> findWithJobById(@Param("id") UUID id);
}
