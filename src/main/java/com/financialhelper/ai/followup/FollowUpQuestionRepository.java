package com.financialhelper.ai.followup;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface FollowUpQuestionRepository
        extends JpaRepository<FollowUpQuestion, UUID> {

    List<FollowUpQuestion>
    // 현재 상담 -> 현재 reivision -> sequence 오름차순으로 정렬
    findByConsultation_IdAndCaseInputRevisionOrderBySequenceNoAsc(
            UUID consultationId,
            long caseInputRevision
    );

    Optional<FollowUpQuestion>
    findByIdAndConsultation_IdAndCaseInputRevision(
            UUID questionId,
            UUID consultationId,
            long caseInputRevision
    );
}