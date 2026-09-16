package com.financialhelper.ai.followup;

import com.financialhelper.procedure.FollowUpQuestionSpec;
import com.financialhelper.procedure.StructuredFollowUpData;
import com.financialhelper.consultation.Consultation;
import com.financialhelper.consultation.ConsultationRepository;
import com.financialhelper.consultation.ConsultationStep;
import com.financialhelper.procedure.StructuredFollowUpService;
import com.financialhelper.retrieval.ConfirmedCaseSnapshotData;
import com.financialhelper.retrieval.ConfirmedCaseSnapshotService;

import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Adapter that connects Backend-owned WHAT to the existing follow-up store. */
@Service
public class ProcedureFollowUpService {
    private final FollowUpPersistenceService persistenceService;
    private final FollowUpQuestionRepository questionRepository;
    private final ConfirmedCaseSnapshotService snapshotService;
    private final StructuredFollowUpService specificationService;
    private final ConsultationRepository consultationRepository;
    private final tools.jackson.databind.json.JsonMapper jsonMapper;

    public ProcedureFollowUpService(
            FollowUpPersistenceService persistenceService,
            FollowUpQuestionRepository questionRepository,
            ConfirmedCaseSnapshotService snapshotService,
            StructuredFollowUpService specificationService,
            ConsultationRepository consultationRepository,
            tools.jackson.databind.json.JsonMapper jsonMapper
    ) {
        this.persistenceService = persistenceService;
        this.questionRepository = questionRepository;
        this.snapshotService = snapshotService;
        this.specificationService = specificationService;
        this.consultationRepository = consultationRepository;
        this.jsonMapper = jsonMapper;
    }

    public StructuredFollowUpStateResponse prepare(UUID consultationId, String rawToken) {
        refresh(consultationId, rawToken);
        List<FollowUpQuestion> current = currentQuestions(consultationId);
        return current.isEmpty()
                ? StructuredFollowUpStateResponse.complete(currentRevision(consultationId))
                : toState(current);
    }

    /** Backward-compatible adapter for the existing FollowUpController. */
    public FollowUpStateResponse prepareLegacy(UUID consultationId, String rawToken) {
        refresh(consultationId, rawToken);
        return persistenceService.getState(consultationId, rawToken, null);
    }

    public StructuredFollowUpStateResponse getState(UUID consultationId, String rawToken) {
        refresh(consultationId, rawToken);
        List<FollowUpQuestion> current = currentQuestions(consultationId);
        return current.isEmpty()
                ? StructuredFollowUpStateResponse.complete(currentRevision(consultationId))
                : toState(current);
    }

    /** Legacy endpoint adapter that still uses the existing response contract. */
    public FollowUpStateResponse getLegacyState(UUID consultationId, String rawToken) {
        return getLegacyState(consultationId, rawToken, null);
    }

    public FollowUpStateResponse getLegacyState(
            UUID consultationId,
            String rawToken,
            Integer questionNumber
    ) {
        refresh(consultationId, rawToken);
        return persistenceService.getState(consultationId, rawToken, questionNumber);
    }

    public StructuredFollowUpStateResponse answer(
            UUID consultationId,
            UUID questionId,
            String rawToken,
            UpdateFollowUpAnswerRequest request
    ) {
        persistenceService.saveAnswer(consultationId, questionId, rawToken, request.answer(), true);
        refresh(consultationId, rawToken);
        return getState(consultationId, rawToken);
    }

    private void refresh(UUID consultationId, String rawToken) {
        Consultation consultation = consultationRepository.findById(consultationId)
                .orElseThrow(() -> new IllegalArgumentException("consultation does not exist"));
        if (consultation.getCurrentStep() == ConsultationStep.SUMMARY) {
            return;
        }
        ConfirmedCaseSnapshotData snapshot = snapshotService.capture(consultationId);
        List<FollowUpQuestion> current = questionRepository
                .findByConsultation_IdAndCaseInputRevisionOrderBySequenceNoAsc(
                        consultationId, snapshot.caseInputRevision());
        if (current.stream().anyMatch(question -> !question.isAnswered())) {
            return;
        }
        Set<String> clarificationAsked = current.stream()
                .filter(FollowUpQuestion::isAnswered)
                .filter(question -> question.getQuestionIntent() != null
                        && question.getQuestionIntent().startsWith("CLARIFY_"))
                .map(FollowUpQuestion::getFactKey)
                .filter(java.util.Objects::nonNull)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        StructuredFollowUpData specification = specificationService.specify(
                snapshot, clarificationAsked);
        if (specification.questions().isEmpty()) {
            persistenceService.completeWithoutQuestions(
                    consultationId, rawToken, snapshot.caseInputRevision());
            return;
        }
        persistenceService.appendStructuredQuestionsIfCurrent(
                consultationId, rawToken, snapshot.caseInputRevision(),
                List.of(specification.questions().getFirst()));
    }

    private long currentRevision(UUID consultationId) {
        return consultationRepository.findById(consultationId)
                .map(Consultation::getCaseInputRevision)
                .orElse(0L);
    }

    private List<FollowUpQuestion> currentQuestions(UUID consultationId) {
        return consultationRepository.findById(consultationId)
                .map(consultation -> questionRepository
                        .findByConsultation_IdAndCaseInputRevisionOrderBySequenceNoAsc(
                                consultationId, consultation.getCaseInputRevision()))
                .orElseGet(List::of);
    }

    private StructuredFollowUpStateResponse toState(List<FollowUpQuestion> questions) {
        FollowUpQuestion target = questions.stream()
                .filter(question -> !question.isAnswered())
                .findFirst()
                .orElse(questions.getLast());
        if (target.isAnswered() && questions.stream().allMatch(FollowUpQuestion::isAnswered)) {
            return StructuredFollowUpStateResponse.complete(target.getCaseInputRevision());
        }
        List<FollowUpQuestionSpec.Option> options = readOptions(target);
        List<String> missingFacts = questions.stream()
                .filter(question -> !question.isAnswered())
                .map(FollowUpQuestion::getFactKey)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList();
        return StructuredFollowUpStateResponse.question(target, options, questions.size(), missingFacts);
    }

    private List<FollowUpQuestionSpec.Option> readOptions(FollowUpQuestion question) {
        try {
            FollowUpOptionsPayload payload = jsonMapper.readValue(
                    question.getOptionsJson(), FollowUpOptionsPayload.class);
            return payload.options().stream()
                    .map(option -> new FollowUpQuestionSpec.Option(
                            option.value(), option.label(), option.description()))
                    .toList();
        } catch (tools.jackson.core.JacksonException exception) {
            throw new IllegalStateException("structured follow-up options are invalid", exception);
        }
    }
}
