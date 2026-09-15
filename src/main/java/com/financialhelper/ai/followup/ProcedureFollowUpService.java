package com.financialhelper.ai.followup;

import com.financialhelper.procedure.FollowUpQuestionSpec;
import com.financialhelper.procedure.StructuredFollowUpData;
import com.financialhelper.procedure.StructuredFollowUpService;
import com.financialhelper.retrieval.ConfirmedCaseSnapshotData;
import com.financialhelper.retrieval.ConfirmedCaseSnapshotService;

import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/** Adapter that connects Backend-owned WHAT to the existing follow-up store. */
@Service
public class ProcedureFollowUpService {
    private final FollowUpPersistenceService persistenceService;
    private final FollowUpQuestionRepository questionRepository;
    private final ConfirmedCaseSnapshotService snapshotService;
    private final StructuredFollowUpService specificationService;
    private final tools.jackson.databind.json.JsonMapper jsonMapper;

    public ProcedureFollowUpService(
            FollowUpPersistenceService persistenceService,
            FollowUpQuestionRepository questionRepository,
            ConfirmedCaseSnapshotService snapshotService,
            StructuredFollowUpService specificationService,
            tools.jackson.databind.json.JsonMapper jsonMapper
    ) {
        this.persistenceService = persistenceService;
        this.questionRepository = questionRepository;
        this.snapshotService = snapshotService;
        this.specificationService = specificationService;
        this.jsonMapper = jsonMapper;
    }

    public StructuredFollowUpStateResponse prepare(UUID consultationId, String rawToken) {
        FollowUpStateResponse existing = persistenceService.getState(consultationId, rawToken, null);
        List<FollowUpQuestion> current = currentQuestions(consultationId, existing);
        if (!current.isEmpty()) {
            return toState(current);
        }
        if ("complete".equals(existing.kind())) {
            return StructuredFollowUpStateResponse.complete(0L);
        }
        ConfirmedCaseSnapshotData snapshot = snapshotService.capture(consultationId);
        StructuredFollowUpData specification = specificationService.specify(snapshot);
        List<FollowUpQuestion> saved = persistenceService.saveStructuredQuestionsIfCurrent(
                consultationId, rawToken, snapshot.caseInputRevision(), specification.questions());
        if (saved.isEmpty()) {
            return StructuredFollowUpStateResponse.complete(snapshot.caseInputRevision());
        }
        return toState(saved);
    }

    /** Backward-compatible adapter for the existing FollowUpController. */
    public FollowUpStateResponse prepareLegacy(UUID consultationId, String rawToken) {
        prepare(consultationId, rawToken);
        return persistenceService.getState(consultationId, rawToken, null);
    }

    public StructuredFollowUpStateResponse getState(UUID consultationId, String rawToken) {
        FollowUpStateResponse existing = persistenceService.getState(consultationId, rawToken, null);
        List<FollowUpQuestion> current = currentQuestions(consultationId, existing);
        return current.isEmpty()
                ? StructuredFollowUpStateResponse.complete(0L)
                : toState(current);
    }

    public StructuredFollowUpStateResponse answer(
            UUID consultationId,
            UUID questionId,
            String rawToken,
            UpdateFollowUpAnswerRequest request
    ) {
        persistenceService.saveAnswer(consultationId, questionId, rawToken, request.answer());
        return getState(consultationId, rawToken);
    }

    private List<FollowUpQuestion> currentQuestions(UUID consultationId, FollowUpStateResponse existing) {
        if (existing.question() == null) {
            return List.of();
        }
        return questionRepository.findById(existing.question().id())
                .map(question -> questionRepository
                        .findByConsultation_IdAndCaseInputRevisionOrderBySequenceNoAsc(
                                consultationId, question.getCaseInputRevision()))
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
