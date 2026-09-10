package com.financialhelper.ai.analysis;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface AnalysisJobRepository
        extends JpaRepository<AnalysisJob, UUID> {

    Optional<AnalysisJob>
    findByConsultation_IdAndCaseInputRevisionAndFollowUpAnswerRevision(
            UUID consultationId,
            long caseInputRevision,
            long followUpAnswerRevision
    );

    // 분석이 중복 실행되지 않게 LOCK 사용
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query(
            """
            select job
            from AnalysisJob job
            where job.id = :jobId
            """
    )
    Optional<AnalysisJob> findForUpdateById(
            @Param("jobId")
            UUID jobId
    );
}