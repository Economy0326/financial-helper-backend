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

/** Backend가 결정한 내용을 기존 follow-up store에 연결하는 adapter다. */
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
        StructuredFollowUpStateResponse unsupported = unsupportedState(consultationId);
        if (unsupported != null) return unsupported;
        refresh(consultationId, rawToken);
        List<FollowUpQuestion> current = currentQuestions(consultationId);
        return current.isEmpty()
                ? StructuredFollowUpStateResponse.complete(currentRevision(consultationId))
                : toState(current);
    }

    /** 기존 FollowUpController를 위한 backward-compatible adapter다. */
    public FollowUpStateResponse prepareLegacy(UUID consultationId, String rawToken) {
        refresh(consultationId, rawToken);
        return persistenceService.getState(consultationId, rawToken, null);
    }

    public StructuredFollowUpStateResponse getState(UUID consultationId, String rawToken) {
        StructuredFollowUpStateResponse unsupported = unsupportedState(consultationId);
        if (unsupported != null) return unsupported;
        StructuredFollowUpStateResponse insufficient = insufficientState(consultationId);
        if (insufficient != null) return insufficient;
        refresh(consultationId, rawToken);
        List<FollowUpQuestion> current = currentQuestions(consultationId);
        return current.isEmpty()
                ? StructuredFollowUpStateResponse.complete(currentRevision(consultationId))
                : toState(current);
    }

    /** 기존 response contract를 계속 사용하는 legacy endpoint adapter다. */
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
        StructuredFollowUpStateResponse unsupported = unsupportedState(consultationId);
        if (unsupported != null) return unsupported;
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
        FollowUpQuestion lastAnswered = current.isEmpty() ? null : current.getLast();
        if (lastAnswered != null && "UNKNOWN".equalsIgnoreCase(lastAnswered.getAnswerValue())
                && clarificationAsked.contains(lastAnswered.getFactKey())
                && !specificationService.hasActionCandidate(snapshot)) {
            return;
        }
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
        if (specificationService.hasActionCandidate(snapshot)) return null;
        return StructuredFollowUpStateResponse.insufficient(last, readOptions(last), questions.size());
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
