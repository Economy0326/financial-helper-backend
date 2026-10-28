package com.financialhelper.retrieval;

import com.financialhelper.ai.followup.FollowUpQuestion;
import com.financialhelper.ai.followup.FollowUpQuestionRepository;
import com.financialhelper.consultation.Consultation;
import com.financialhelper.consultation.ConsultationRepository;
import com.financialhelper.consultation.ConsultationScenarioResolver;
import com.financialhelper.consultation.ConsultationScenario;
import com.financialhelper.procedure.CardCaseFactExtractor;
import com.financialhelper.procedure.CardCaseFacts;
import com.financialhelper.procedure.ProcedureVersionService;
import com.financialhelper.procedure.ScenarioCaseFactExtractor;
import com.financialhelper.procedure.ScenarioRoutingFactContract;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.HashMap;
import java.util.Map;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;

/** 사용자 소유 입력만으로 consultation 범위의 immutable context를 만든다. */
@Service
public class ConfirmedCaseSnapshotService {

    private final ConsultationRepository consultationRepository;
    private final FollowUpQuestionRepository followUpQuestionRepository;
    private final ConfirmedCaseSnapshotRepository snapshotRepository;
    private final JsonMapper jsonMapper;
    private final ProcedureVersionService procedureVersionService;

    @Autowired
    public ConfirmedCaseSnapshotService(
            ConsultationRepository consultationRepository,
            FollowUpQuestionRepository followUpQuestionRepository,
            ConfirmedCaseSnapshotRepository snapshotRepository,
            JsonMapper jsonMapper,
            ProcedureVersionService procedureVersionService
    ) {
        this.consultationRepository = consultationRepository;
        this.followUpQuestionRepository = followUpQuestionRepository;
        this.snapshotRepository = snapshotRepository;
        this.jsonMapper = jsonMapper;
        this.procedureVersionService = procedureVersionService;
    }

    /** Compatibility constructor for the existing isolated unit test. */
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
        this.procedureVersionService = null;
    }

    @Transactional
    public ConfirmedCaseSnapshotData capture(UUID consultationId) {
        return capture(consultationId, null);
    }

    @Transactional
    public ConfirmedCaseSnapshotData capture(UUID consultationId, Long logicalStartRevision) {
        if (consultationId == null) {
            throw new IllegalArgumentException("consultationId must not be null");
        }
        Consultation consultation = consultationRepository.findForUpdateById(consultationId)
                .orElseThrow(() -> new IllegalArgumentException("consultation does not exist"));
        reconcileQuestionAttribution(consultation, logicalStartRevision);
        long caseRevision = consultation.getCaseInputRevision();
        long answerRevision = consultation.getFollowUpAnswerRevision();
        return snapshotRepository
                .findByConsultation_IdAndCaseInputRevisionAndFollowUpAnswerRevision(
                        consultationId, caseRevision, answerRevision)
                .map(this::toData)
                .orElseGet(() -> saveSnapshot(consultation, caseRevision, answerRevision));
    }

    private void reconcileQuestionAttribution(Consultation consultation, Long logicalStartRevision) {
        if (procedureVersionService == null) return;
        ConsultationScenario scenario = ConsultationScenarioResolver.resolve(consultation);
        if (scenario != ConsultationScenario.CARD_LOSS_UNAUTHORIZED_USE
                && scenario != ConsultationScenario.VOICE_PHISHING_SUSPICIOUS_TRANSFER
                && scenario != ConsultationScenario.UNAUTHORIZED_ACCOUNT_TRANSFER
                && scenario != ConsultationScenario.PERSONAL_INFO_SMISHING_MALICIOUS_APP) return;
        List<FollowUpQuestion> questions = followUpQuestionRepository
                .findByConsultation_IdAndCaseInputRevisionAndSupersededAtIsNullOrderBySequenceNoAsc(
                        consultation.getId(), consultation.getCaseInputRevision());
        List<ConfirmedCaseSnapshotData.Fact> preliminary = new ArrayList<>();
        if (consultation.getSituationText() != null)
            preliminary.add(new ConfirmedCaseSnapshotData.Fact("SITUATION", "situationText",
                    consultation.getSituationText(), null, "USER_STATED", null));
        preliminary.add(new ConfirmedCaseSnapshotData.Fact("SCENARIO", "scenario",
                scenario.name(), null, "SYSTEM_RESOLVED_FROM_EXPLICIT_INPUT", null));
        for (FollowUpQuestion question : questions) {
            if (!"SCENARIO_ROUTING".equals(question.getQuestionScope())
                    && !"COMMON_DATE".equals(question.getQuestionScope())) continue;
            ScenarioRoutingFactContract.Contract contract =
                    ScenarioRoutingFactContract.byId(question.getFactContractId());
            if (contract != null && contract.scenario() == scenario
                    && contract.matches(question, jsonMapper) && question.isAnswered()) {
                preliminary.add(new ConfirmedCaseSnapshotData.Fact(question.getInputType(),
                        question.getFactKey(), question.getAnswerValue(), question.getAnswerLabel(),
                        "USER_ANSWERED", question.getId()));
            }
        }
        ConfirmedCaseSnapshotData preview = new ConfirmedCaseSnapshotData(null,
                consultation.getId(), consultation.getCaseInputRevision(),
                consultation.getFollowUpAnswerRevision(), preliminary, List.of(), null);
        CardCaseFacts facts = scenario == ConsultationScenario.CARD_LOSS_UNAUTHORIZED_USE
                ? CardCaseFactExtractor.fromSnapshot(preview)
                : ScenarioCaseFactExtractor.fromSnapshot(preview);
        java.util.UUID currentVersion = currentProcedureId(scenario, facts);
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        boolean changed = false;
        Map<String, FollowUpQuestion> latestAnswered = new HashMap<>();
        for (FollowUpQuestion question : questions) {
            if (question.isAnswered() && question.getFactKey() != null
                    && validAttribution(question, scenario, currentVersion))
                latestAnswered.put(question.getFactKey(), question);
        }
        for (FollowUpQuestion question : questions) {
            boolean valid = validAttribution(question, scenario, currentVersion);
            if (valid && question.isAnswered() && question.getFactKey() != null
                    && latestAnswered.get(question.getFactKey()) != question) valid = false;
            if (!valid) changed |= question.supersede(now);
        }
        if (changed && (logicalStartRevision == null
                || consultation.getFollowUpAnswerRevision() == logicalStartRevision))
            consultation.recordFollowUpAnswerChanged(now);
    }

    private boolean validAttribution(FollowUpQuestion question, ConsultationScenario scenario,
            UUID currentVersion) {
        return switch (question.getQuestionScope()) {
            case "SCENARIO_ROUTING", "COMMON_DATE" -> {
                ScenarioRoutingFactContract.Contract contract =
                        ScenarioRoutingFactContract.byId(question.getFactContractId());
                yield contract != null && contract.scenario() == scenario
                        && contract.matches(question, jsonMapper);
            }
            case "VERSION_BOUND" -> currentVersion != null
                    && currentVersion.equals(question.getProcedureVersionId());
            case "LEGACY_UNATTRIBUTED" -> false;
            default -> false;
        };
    }

    private java.util.UUID currentProcedureId(ConsultationScenario scenario, CardCaseFacts facts) {
        String branch = scenario.name();
        String institution = "GENERIC_FINANCIAL_INSTITUTION";
        String product = scenario == ConsultationScenario.PERSONAL_INFO_SMISHING_MALICIOUS_APP
                ? "DIGITAL_FINANCIAL_SERVICE" : "BANK_ACCOUNT";
        if (scenario == ConsultationScenario.CARD_LOSS_UNAUTHORIZED_USE) {
            ProcedureVersionService.CardBranchSelection selected =
                    procedureVersionService.selectCardBranch(facts);
            if (!selected.selected()) return null;
            branch = selected.scenario();
            institution = ProcedureVersionService.KB_INSTITUTION;
            product = ProcedureVersionService.PERSONAL_CREDIT_CARD;
        }
        String dateValue = facts.value(scenario == ConsultationScenario.UNAUTHORIZED_ACCOUNT_TRANSFER
                ? "transactionDate" : "incidentDate");
        LocalDate date;
        try { date = dateValue == null ? null : LocalDate.parse(dateValue); }
        catch (DateTimeParseException exception) { date = null; }
        ProcedureVersionService.Selection selection = procedureVersionService.selectApplicable(
                branch, institution, product, date,
                scenario != ConsultationScenario.PERSONAL_INFO_SMISHING_MALICIOUS_APP);
        return selection.selected() ? selection.procedure().id() : null;
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

    @Transactional(readOnly = true)
    public Optional<ConfirmedCaseSnapshotData> findCurrent(
            UUID consultationId,
            long caseInputRevision,
            long followUpAnswerRevision
    ) {
        if (consultationId == null) {
            throw new IllegalArgumentException("consultationId must not be null");
        }
        return snapshotRepository
                .findByConsultation_IdAndCaseInputRevisionAndFollowUpAnswerRevision(
                        consultationId, caseInputRevision, followUpAnswerRevision)
                .map(this::toData);
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
        // 넓은 CARD category가 legacy scenario를 이미 결정하면 기존 CARD snapshot
        // 형태를 유지한다. breadth scenario에는 명시적 scenario fact를 남겨 후속
        // service가 맞는 Procedure와 범위를 안전하게 선택하게 한다.
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

        List<FollowUpQuestion> questions = procedureVersionService == null
                ? followUpQuestionRepository.findByConsultation_IdAndCaseInputRevisionOrderBySequenceNoAsc(
                        consultation.getId(), caseRevision)
                : followUpQuestionRepository
                        .findByConsultation_IdAndCaseInputRevisionAndSupersededAtIsNullOrderBySequenceNoAsc(
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
