package com.financialhelper.ai.followup;

import com.financialhelper.procedure.FollowUpQuestionSpec;
import com.financialhelper.procedure.StructuredFollowUpData;
import com.financialhelper.consultation.Consultation;
import com.financialhelper.consultation.ConsultationRepository;
import com.financialhelper.consultation.ConsultationStep;
import com.financialhelper.consultation.InvalidConsultationStateException;
import com.financialhelper.procedure.StructuredFollowUpService;
import com.financialhelper.retrieval.ConfirmedCaseSnapshotData;
import com.financialhelper.retrieval.ConfirmedCaseSnapshotService;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import com.financialhelper.consultation.ConsultationScenario;
import com.financialhelper.consultation.ConsultationScenarioResolver;
import com.financialhelper.procedure.CardCaseFactExtractor;
import com.financialhelper.procedure.ScenarioCaseFactExtractor;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.PersistenceContext;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Backend가 결정한 내용을 기존 follow-up store에 연결하는 adapter다. */
@Service
public class ProcedureFollowUpService {
    private final FollowUpPersistenceService persistenceService;
    private final FollowUpQuestionRepository questionRepository;
    private final ConfirmedCaseSnapshotService snapshotService;
    private final StructuredFollowUpService specificationService;
    private final ConsultationRepository consultationRepository;
    private final tools.jackson.databind.json.JsonMapper jsonMapper;
    @PersistenceContext private EntityManager entityManager;

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

    @Transactional
    public StructuredFollowUpStateResponse prepare(UUID consultationId, String rawToken) {
        String blocked = refresh(consultationId, rawToken, lockedAnswerRevision(consultationId, rawToken));
        if (blocked != null) return blockedState(consultationId, blocked);
        StructuredFollowUpStateResponse unsupported = unsupportedState(consultationId);
        if (unsupported != null) return unsupported;
        StructuredFollowUpStateResponse insufficient = insufficientState(consultationId);
        if (insufficient != null && "transactionDate".equals(insufficient.blockingFact().key())) {
            persistenceService.getState(consultationId, rawToken, null);
            return insufficient;
        }
        List<FollowUpQuestion> current = currentQuestions(consultationId);
        return current.isEmpty()
                ? StructuredFollowUpStateResponse.complete(currentRevision(consultationId))
                : toState(current);
    }

    /** 기존 FollowUpController를 위한 backward-compatible adapter다. */
    @Transactional
    public FollowUpStateResponse prepareLegacy(UUID consultationId, String rawToken) {
        String blocked = refresh(consultationId, rawToken, lockedAnswerRevision(consultationId, rawToken));
        if (blocked != null) return FollowUpStateResponse.versionBlocked(blocked);
        return persistenceService.getState(consultationId, rawToken, null);
    }

    @Transactional
    public StructuredFollowUpStateResponse getState(UUID consultationId, String rawToken) {
        String blocked = refresh(consultationId, rawToken, lockedAnswerRevision(consultationId, rawToken));
        if (blocked != null) return blockedState(consultationId, blocked);
        StructuredFollowUpStateResponse unsupported = unsupportedState(consultationId);
        if (unsupported != null) return unsupported;
        StructuredFollowUpStateResponse insufficient = insufficientState(consultationId);
        if (insufficient != null) {
            persistenceService.getState(consultationId, rawToken, null);
            return insufficient;
        }
        List<FollowUpQuestion> current = currentQuestions(consultationId);
        return current.isEmpty()
                ? StructuredFollowUpStateResponse.complete(currentRevision(consultationId))
                : toState(current);
    }

    /** 기존 response contract를 계속 사용하는 legacy endpoint adapter다. */
    @Transactional
    public FollowUpStateResponse getLegacyState(UUID consultationId, String rawToken) {
        return getLegacyState(consultationId, rawToken, null);
    }

    @Transactional
    public FollowUpStateResponse getLegacyState(
            UUID consultationId,
            String rawToken,
            Integer questionNumber
    ) {
        String blocked = refresh(consultationId, rawToken, lockedAnswerRevision(consultationId, rawToken));
        if (blocked != null) return FollowUpStateResponse.versionBlocked(blocked);
        return persistenceService.getState(consultationId, rawToken, questionNumber);
    }

    @Transactional
    public StructuredFollowUpStateResponse answer(
            UUID consultationId,
            UUID questionId,
            String rawToken,
        UpdateFollowUpAnswerRequest request
    ) {
        long before = lockedAnswerRevision(consultationId, rawToken);
        String blocked = validateQuestionVersion(consultationId, questionId, rawToken, before);
        if (blocked != null) return blockedState(consultationId, blocked);
        persistenceService.saveAnswer(consultationId, questionId, rawToken, request.answer(), true);
        StructuredFollowUpStateResponse unsupported = unsupportedState(consultationId);
        if (unsupported != null) return unsupported;
        blocked = refresh(consultationId, rawToken, before);
        if (blocked != null) return blockedState(consultationId, blocked);
        StructuredFollowUpStateResponse insufficient = insufficientState(consultationId);
        if (insufficient != null) return insufficient;
        List<FollowUpQuestion> current = currentQuestions(consultationId);
        return current.isEmpty()
                ? StructuredFollowUpStateResponse.complete(currentRevision(consultationId))
                : toState(current);
    }

    private String validateQuestionVersion(UUID consultationId, UUID questionId,
            String rawToken, long logicalStartRevision) {
        persistenceService.getState(consultationId, rawToken, null);
        Consultation consultation = consultationRepository.findForUpdateById(consultationId)
                .orElseThrow(() -> new IllegalArgumentException("consultation does not exist"));
        if (consultation.getCurrentStep() != ConsultationStep.FOLLOW_UP) {
            throw new InvalidConsultationStateException();
        }
        ConfirmedCaseSnapshotData snapshot = snapshotService.capture(consultationId, logicalStartRevision);
        FollowUpQuestion question = questionRepository
                .findByIdAndConsultation_IdAndCaseInputRevisionAndSupersededAtIsNull(
                        questionId, consultationId, consultation.getCaseInputRevision())
                .orElse(null);
        if (question == null) return "STALE_QUESTION_VERSION";
        Set<String> clarificationAsked = currentQuestions(consultationId).stream()
                .filter(FollowUpQuestion::isAnswered)
                .filter(item -> item.getQuestionIntent() != null
                        && item.getQuestionIntent().startsWith("CLARIFY_"))
                .map(FollowUpQuestion::getFactKey).filter(java.util.Objects::nonNull)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        StructuredFollowUpService.ProcedureResolution resolution =
                specificationService.resolveForConsultation(snapshot, clarificationAsked);
        if (resolution.blockReason() != null) return resolution.blockReason();
        if (question.getFactKey() != null
                && question.getFactKey().equals(resolution.exhaustedFact())
                && ("SCENARIO_ROUTING".equals(question.getQuestionScope())
                    || "COMMON_DATE".equals(question.getQuestionScope()))) return null;
        if (resolution.selected()) {
            return "VERSION_BOUND".equals(question.getQuestionScope())
                    && resolution.procedure().id().equals(question.getProcedureVersionId())
                    ? null : "STALE_QUESTION_VERSION";
        }
        return resolution.commonScope() != null
                && resolution.commonScope().equals(question.getQuestionScope())
                && resolution.factContractId().equals(question.getFactContractId())
                ? null : "STALE_QUESTION_VERSION";
    }

    private String refresh(UUID consultationId, String rawToken, long logicalStartRevision) {
        // Validate ownership before any reconciliation can mutate questions or revision.
        persistenceService.getState(consultationId, rawToken, null);
        Consultation consultation = consultationRepository.findForUpdateById(consultationId)
                .orElseThrow(() -> new IllegalArgumentException("consultation does not exist"));
        if (consultation.getCurrentStep() == ConsultationStep.SUMMARY) {
            return null;
        }
        ConfirmedCaseSnapshotData snapshot = snapshotService.capture(consultationId, logicalStartRevision);
        List<FollowUpQuestion> current = questionRepository
                .findByConsultation_IdAndCaseInputRevisionAndSupersededAtIsNullOrderBySequenceNoAsc(
                        consultationId, snapshot.caseInputRevision());
        Set<String> clarificationAsked = current.stream()
                .filter(FollowUpQuestion::isAnswered)
                .filter(question -> question.getQuestionIntent() != null
                        && question.getQuestionIntent().startsWith("CLARIFY_"))
                .map(FollowUpQuestion::getFactKey)
                .filter(java.util.Objects::nonNull)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        StructuredFollowUpService.ProcedureResolution resolution =
                specificationService.resolveForConsultation(snapshot, clarificationAsked);
        if (resolution.blockReason() != null) return resolution.blockReason();
        if (resolution.exhaustedFact() != null) return null;
        FollowUpQuestion pending = current.stream().filter(question -> !question.isAnswered())
                .findFirst().orElse(null);
        if (pending != null) {
            boolean belongs = resolution.selected()
                    ? "VERSION_BOUND".equals(pending.getQuestionScope())
                        && resolution.procedure().id().equals(pending.getProcedureVersionId())
                    : resolution.commonScope().equals(pending.getQuestionScope())
                        && resolution.factContractId().equals(pending.getFactContractId());
            if (belongs) return null;
            pending.supersede(OffsetDateTime.now(ZoneOffset.UTC));
            if (consultation.getFollowUpAnswerRevision() == logicalStartRevision)
                consultation.recordFollowUpAnswerChanged(OffsetDateTime.now(ZoneOffset.UTC));
            return "STALE_QUESTION_VERSION";
        }
        if (!resolution.selected()) {
            persistenceService.appendStructuredQuestionsIfCurrent(
                    consultationId, rawToken, snapshot.caseInputRevision(),
                    List.of(resolution.commonQuestion()), resolution.commonScope(), null,
                    resolution.factContractId());
            if (consultation.getFollowUpAnswerRevision() == logicalStartRevision)
                consultation.recordFollowUpAnswerChanged(OffsetDateTime.now(ZoneOffset.UTC));
            return null;
        }
        ConsultationScenario scenario = ConsultationScenarioResolver.resolve(consultation);
        StructuredFollowUpData specification = specificationService.specify(
                resolution.procedure(),
                scenario == ConsultationScenario.CARD_LOSS_UNAUTHORIZED_USE
                        ? CardCaseFactExtractor.fromSnapshot(snapshot)
                        : ScenarioCaseFactExtractor.fromSnapshot(snapshot),
                snapshot.caseInputRevision(), clarificationAsked, scenario);
        FollowUpQuestion lastAnswered = current.isEmpty() ? null : current.getLast();
        if (lastAnswered != null && "UNKNOWN".equalsIgnoreCase(lastAnswered.getAnswerValue())
                && clarificationAsked.contains(lastAnswered.getFactKey())
                && (accountDateClarificationExhausted(consultation, lastAnswered)
                    || !specificationService.hasActionCandidate(snapshot))) {
            return null;
        }
        if (specification.questions().isEmpty()) {
            persistenceService.completeWithoutQuestions(
                    consultationId, rawToken, snapshot.caseInputRevision());
            return null;
        }
        persistenceService.appendStructuredQuestionsIfCurrent(
                consultationId, rawToken, snapshot.caseInputRevision(),
                List.of(specification.questions().getFirst()), "VERSION_BOUND",
                resolution.procedure().id(), null);
        if (consultation.getFollowUpAnswerRevision() == logicalStartRevision)
            consultation.recordFollowUpAnswerChanged(OffsetDateTime.now(ZoneOffset.UTC));
        return null;
    }

    private long lockedAnswerRevision(UUID consultationId, String rawToken) {
        // Ownership is checked before taking a write lock. Refresh the managed entity
        // after locking because the ownership lookup may have loaded an older revision.
        persistenceService.getState(consultationId, rawToken, null);
        Consultation consultation = consultationRepository.findForUpdateById(consultationId)
                .orElseThrow(() -> new IllegalArgumentException("consultation does not exist"));
        entityManager.refresh(consultation, LockModeType.PESSIMISTIC_WRITE);
        return consultation.getFollowUpAnswerRevision();
    }

    private StructuredFollowUpStateResponse blockedState(UUID consultationId, String reason) {
        return StructuredFollowUpStateResponse.versionBlocked(currentRevision(consultationId), reason);
    }

    private long currentRevision(UUID consultationId) {
        return consultationRepository.findById(consultationId)
                .map(Consultation::getCaseInputRevision)
                .orElse(0L);
    }

    private List<FollowUpQuestion> currentQuestions(UUID consultationId) {
        return consultationRepository.findById(consultationId)
                .map(consultation -> questionRepository
                        .findByConsultation_IdAndCaseInputRevisionAndSupersededAtIsNullOrderBySequenceNoAsc(
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
        return StructuredFollowUpStateResponse.question(target, options,
                questions.indexOf(target) + 1, questions.size(), missingFacts);
    }

    /** Explicit CARD boundary values stop the flow before any later procedure/evidence work. */
    private StructuredFollowUpStateResponse unsupportedState(UUID consultationId) {
        Consultation consultation = consultationRepository.findById(consultationId).orElse(null);
        if (consultation == null || com.financialhelper.consultation.ConsultationScenarioResolver.resolve(consultation)
                != com.financialhelper.consultation.ConsultationScenario.CARD_LOSS_UNAUTHORIZED_USE) return null;
        List<FollowUpQuestion> questions = currentQuestions(consultationId);
        for (FollowUpQuestion question : questions) {
            if (!question.isAnswered()) continue;
            String value = question.getAnswerValue();
            String reason = switch (question.getFactKey()) {
                case "institution" -> isSupportedInstitution(value) ? null : "CARD_INSTITUTION_UNSUPPORTED";
                case "productType" -> "PERSONAL_CREDIT_CARD".equals(value) || "UNKNOWN".equals(value)
                        ? null : "CARD_PRODUCT_UNSUPPORTED";
                case "domestic" -> "FALSE".equals(value) ? "CARD_TRANSACTION_SCOPE_UNSUPPORTED" : null;
                case "transactionType" -> "CREDIT_SALE".equals(value) || "UNKNOWN".equals(value)
                        ? null : "CARD_TRANSACTION_SCOPE_UNSUPPORTED";
                default -> null;
            };
            if (reason != null) return StructuredFollowUpStateResponse.unsupported(question,
                    readOptions(question), questions.size(), reason);
        }
        return null;
    }

    private StructuredFollowUpStateResponse insufficientState(UUID consultationId) {
        Consultation consultation = consultationRepository.findById(consultationId).orElse(null);
        if (consultation == null) return null;
        List<FollowUpQuestion> questions = currentQuestions(consultationId);
        if (questions.isEmpty()) return null;
        FollowUpQuestion last = questions.getLast();
        if (!last.isAnswered() || !"UNKNOWN".equalsIgnoreCase(last.getAnswerValue())
                || last.getQuestionIntent() == null || !last.getQuestionIntent().startsWith("CLARIFY_")) return null;
        ConfirmedCaseSnapshotData snapshot = snapshotService.capture(consultationId);
        StructuredFollowUpService.ProcedureResolution resolution =
                specificationService.resolveForConsultation(snapshot, Set.of(last.getFactKey()));
        if (last.getFactKey().equals(resolution.exhaustedFact()))
            return StructuredFollowUpStateResponse.insufficient(last, readOptions(last), questions.size());
        if (!accountDateClarificationExhausted(consultation, last)
                && specificationService.hasActionCandidate(snapshot)) return null;
        return StructuredFollowUpStateResponse.insufficient(last, readOptions(last), questions.size());
    }

    private boolean accountDateClarificationExhausted(
            Consultation consultation, FollowUpQuestion question
    ) {
        return com.financialhelper.consultation.ConsultationScenarioResolver.resolve(consultation)
                == com.financialhelper.consultation.ConsultationScenario.UNAUTHORIZED_ACCOUNT_TRANSFER
                && "transactionDate".equals(question.getFactKey())
                && "CLARIFY_TRANSACTION_DATE".equals(question.getQuestionIntent());
    }

    private boolean isSupportedInstitution(String value) {
        return "KB_KOOKMIN_CARD".equals(value) || "㈜KB국민카드".equals(value)
                || "UNKNOWN".equals(value);
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
