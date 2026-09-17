package com.financialhelper.retrieval;

import com.financialhelper.ai.followup.FollowUpQuestion;
import com.financialhelper.ai.followup.FollowUpQuestionRepository;
import com.financialhelper.consultation.Consultation;
import com.financialhelper.consultation.ConsultationRepository;
import com.financialhelper.consultation.ConsultationScenarioResolver;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Builds immutable consultation-scoped context from user-owned inputs only. */
@Service
public class ConfirmedCaseSnapshotService {

    private final ConsultationRepository consultationRepository;
    private final FollowUpQuestionRepository followUpQuestionRepository;
    private final ConfirmedCaseSnapshotRepository snapshotRepository;
    private final JsonMapper jsonMapper;

    public ConfirmedCaseSnapshotService(
            ConsultationRepository consultationRepository,
            FollowUpQuestionRepository followUpQuestionRepository,
            ConfirmedCaseSnapshotRepository snapshotRepository,
            JsonMapper jsonMapper
    ) {
        this.consultationRepository = consultationRepository;
        this.followUpQuestionRepository = followUpQuestionRepository;
        this.snapshotRepository = snapshotRepository;
        this.jsonMapper = jsonMapper;
    }

    @Transactional
    public ConfirmedCaseSnapshotData capture(UUID consultationId) {
        if (consultationId == null) {
            throw new IllegalArgumentException("consultationId must not be null");
        }
        Consultation consultation = consultationRepository.findForUpdateById(consultationId)
                .orElseThrow(() -> new IllegalArgumentException("consultation does not exist"));
        long caseRevision = consultation.getCaseInputRevision();
        long answerRevision = consultation.getFollowUpAnswerRevision();
        return snapshotRepository
                .findByConsultation_IdAndCaseInputRevisionAndFollowUpAnswerRevision(
                        consultationId, caseRevision, answerRevision)
                .map(this::toData)
                .orElseGet(() -> saveSnapshot(consultation, caseRevision, answerRevision));
    }

    @Transactional(readOnly = true)
    public ConfirmedCaseSnapshotData latest(UUID consultationId) {
        if (consultationId == null) {
            throw new IllegalArgumentException("consultationId must not be null");
        }
        return snapshotRepository.findLatest(consultationId)
                .map(this::toData)
                .orElseThrow(() -> new IllegalArgumentException("confirmed case snapshot does not exist"));
    }

    private ConfirmedCaseSnapshotData saveSnapshot(
            Consultation consultation,
            long caseRevision,
            long answerRevision
    ) {
        List<ConfirmedCaseSnapshotData.Fact> facts = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        if (consultation.getCategory() == null) {
            missing.add("CATEGORY");
        } else {
            facts.add(new ConfirmedCaseSnapshotData.Fact(
                    "CATEGORY", "category", consultation.getCategory().name(),
                    null, "USER_SELECTED", null));
        }
        var resolvedScenario = ConsultationScenarioResolver.resolve(consultation);
        // Preserve the established CARD snapshot shape when the broad CARD
        // category already determines the legacy scenario.  Breadth scenarios
        // still carry an explicit scenario fact so downstream services can
        // select the matching procedure and scope safely.
        boolean legacyCardScenario = consultation.getCategory()
                == com.financialhelper.consultation.ConsultationCategory.CARD
                && resolvedScenario
                == com.financialhelper.consultation.ConsultationScenario.CARD_LOSS_UNAUTHORIZED_USE;
        if (resolvedScenario != com.financialhelper.consultation.ConsultationScenario.UNKNOWN
                && !legacyCardScenario) {
            facts.add(new ConfirmedCaseSnapshotData.Fact(
                    "SCENARIO", "scenario", resolvedScenario.name(),
                    null, "SYSTEM_RESOLVED_FROM_EXPLICIT_INPUT", null));
        } else if (resolvedScenario
                == com.financialhelper.consultation.ConsultationScenario.UNKNOWN) {
            missing.add("SCENARIO");
        }
        if (consultation.getSituationText() == null
                || consultation.getSituationText().isBlank()) {
            missing.add("SITUATION");
        } else {
            facts.add(new ConfirmedCaseSnapshotData.Fact(
                    "SITUATION", "situationText", consultation.getSituationText(),
                    null, "USER_STATED", null));
        }

        List<FollowUpQuestion> questions = followUpQuestionRepository
                .findByConsultation_IdAndCaseInputRevisionOrderBySequenceNoAsc(
                        consultation.getId(), caseRevision);
        for (FollowUpQuestion question : questions) {
            if (question.isAnswered() && question.getAnswerValue() != null
                    && !question.getAnswerValue().isBlank()) {
                String factKey = question.getFactKey() == null
                        ? "question-" + question.getSequenceNo()
                        : question.getFactKey();
                String factType = question.getInputType() == null
                        ? "FOLLOW_UP" : question.getInputType();
                String normalizedValue = "UNKNOWN".equalsIgnoreCase(question.getAnswerValue())
                        ? "UNKNOWN" : question.getAnswerValue();
                facts.add(new ConfirmedCaseSnapshotData.Fact(
                        factType, factKey, normalizedValue, question.getAnswerLabel(),
                        "USER_ANSWERED", question.getId()));
                if (question.getFactKey() != null && "UNKNOWN".equalsIgnoreCase(normalizedValue)) {
                    missing.add(question.getFactKey());
                }
            } else {
                missing.add(question.getFactKey() == null
                        ? "FOLLOW_UP_" + question.getSequenceNo()
                        : question.getFactKey());
            }
        }

        try {
            String factsJson = jsonMapper.writeValueAsString(facts);
            String missingJson = jsonMapper.writeValueAsString(missing);
            ConfirmedCaseSnapshot saved = snapshotRepository.saveAndFlush(
                    new ConfirmedCaseSnapshot(
                            consultation, caseRevision, answerRevision,
                            factsJson, missingJson,
                            OffsetDateTime.now(ZoneOffset.UTC)));
            return new ConfirmedCaseSnapshotData(
                    saved.getId(), consultation.getId(), caseRevision, answerRevision,
                    facts, missing, saved.getCreatedAt());
        } catch (JacksonException exception) {
            throw new IllegalStateException("could not serialize confirmed case snapshot", exception);
        }
    }

    private ConfirmedCaseSnapshotData toData(ConfirmedCaseSnapshot snapshot) {
        try {
            ConfirmedCaseSnapshotData.Fact[] facts = jsonMapper.readValue(
                    snapshot.getFactsJson(), ConfirmedCaseSnapshotData.Fact[].class);
            String[] missing = jsonMapper.readValue(
                    snapshot.getMissingFactsJson(), String[].class);
            return new ConfirmedCaseSnapshotData(
                    snapshot.getId(), snapshot.getConsultation().getId(),
                    snapshot.getCaseInputRevision(), snapshot.getFollowUpAnswerRevision(),
                    facts == null ? List.of() : List.of(facts),
                    missing == null ? List.of() : List.of(missing),
                    snapshot.getCreatedAt());
        } catch (JacksonException exception) {
            throw new IllegalStateException("stored confirmed case snapshot is invalid", exception);
        }
    }
}
