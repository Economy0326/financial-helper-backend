package com.financialhelper.procedure;

import com.financialhelper.consultation.Consultation;
import com.financialhelper.consultation.ConsultationCategory;
import com.financialhelper.consultation.ConsultationNotFoundException;
import com.financialhelper.consultation.ConsultationRepository;
import com.financialhelper.guest.GuestSession;
import com.financialhelper.guest.GuestSessionService;
import com.financialhelper.retrieval.ConfirmedCaseSnapshot;
import com.financialhelper.retrieval.ConfirmedCaseSnapshotData;
import com.financialhelper.retrieval.ConfirmedCaseSnapshotRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Owns the deterministic financial action decision. LLM output and
 * retrieval rank are intentionally absent from this service's inputs.
 */
@Service
public class FinancialActionPlanService {
    private static final Set<String> BLOCKING_FACTS = Set.of(
            "institution", "productType", "cardLost", "unauthorizedPayment",
            "transactionType", "domestic");
    private final ConsultationRepository consultationRepository;
    private final GuestSessionService guestSessionService;
    private final ConfirmedCaseSnapshotServiceAdapter snapshotService;
    private final ConfirmedCaseSnapshotRepository snapshotRepository;
    private final ProcedureVersionRepository procedureRepository;
    private final ProcedureVersionService procedureVersionService;
    private final EvidenceBindingResolver evidenceResolver;
    private final FinancialActionPlanRepository planRepository;
    private final JsonMapper jsonMapper;

    public FinancialActionPlanService(
            ConsultationRepository consultationRepository,
            GuestSessionService guestSessionService,
            com.financialhelper.retrieval.ConfirmedCaseSnapshotService confirmedCaseSnapshotService,
            ConfirmedCaseSnapshotRepository snapshotRepository,
            ProcedureVersionRepository procedureRepository,
            ProcedureVersionService procedureVersionService,
            EvidenceBindingResolver evidenceResolver,
            FinancialActionPlanRepository planRepository,
            JsonMapper jsonMapper
    ) {
        this.consultationRepository = consultationRepository;
        this.guestSessionService = guestSessionService;
        this.snapshotService = new ConfirmedCaseSnapshotServiceAdapter(confirmedCaseSnapshotService);
        this.snapshotRepository = snapshotRepository;
        this.procedureRepository = procedureRepository;
        this.procedureVersionService = procedureVersionService;
        this.evidenceResolver = evidenceResolver;
        this.planRepository = planRepository;
        this.jsonMapper = jsonMapper;
    }

    @Transactional
    public FinancialActionPlanData build(UUID consultationId, String rawToken) {
        if (consultationId == null) {
            throw new IllegalArgumentException("consultationId must not be null");
        }
        GuestSession session = guestSessionService.requireValidSession(rawToken);
        Consultation consultation = consultationRepository
                .findByIdAndGuestSession_Id(consultationId, session.getId())
                .orElseThrow(ConsultationNotFoundException::new);
        if (consultation.getCategory() != ConsultationCategory.CARD) {
            throw new IllegalStateException("CARD procedure is unavailable for this consultation category");
        }
        return buildForConsultation(consultation);
    }

    /**
     * Internal orchestration entry point used after the consultation has
     * already passed its guest-owned state transition.  It deliberately does
     * not accept a user token because the analysis worker has no request
     * credentials; callers must keep this method behind the analysis state
     * machine.
     */
    @Transactional
    public FinancialActionPlanData buildForCurrent(UUID consultationId) {
        Consultation consultation = consultationRepository.findById(consultationId)
                .orElseThrow(ConsultationNotFoundException::new);
        if (consultation.getCategory() != ConsultationCategory.CARD) {
            throw new IllegalStateException("CARD procedure is unavailable for this consultation category");
        }
        return buildForConsultation(consultation);
    }

    private FinancialActionPlanData buildForConsultation(Consultation consultation) {
        ConfirmedCaseSnapshotData snapshotData = snapshotService.capture(consultation.getId());
        ConfirmedCaseSnapshot snapshot = snapshotRepository.findById(snapshotData.id())
                .orElseThrow(() -> new IllegalStateException("confirmed case snapshot is unavailable"));
        ProcedureVersionData procedure = procedureVersionService.requireApprovedCard();
        ProcedureVersion procedureEntity = procedureRepository.findById(procedure.id())
                .orElseThrow(() -> new IllegalStateException("approved procedure entity is unavailable"));

        FinancialActionPlanData result = buildFromSnapshot(snapshotData, procedure,
                CardCaseFactExtractor.fromSnapshot(snapshotData));
        return saveIfAbsent(consultation, procedureEntity, snapshot, result);
    }

    @Transactional(readOnly = true)
    public FinancialActionPlanData getLatest(UUID consultationId, String rawToken) {
        GuestSession session = guestSessionService.requireValidSession(rawToken);
        Consultation consultation = consultationRepository
                .findByIdAndGuestSession_Id(consultationId, session.getId())
                .orElseThrow(ConsultationNotFoundException::new);
        return planRepository.findTopByConsultation_IdOrderByCaseInputRevisionDescFollowUpAnswerRevisionDesc(
                        consultation.getId())
                .map(this::toData)
                .orElseThrow(() -> new IllegalStateException("financial action plan does not exist"));
    }

    /** Pure decision entry point used by unit tests and future orchestration. */
    public FinancialActionPlanData buildFromSnapshot(
            ConfirmedCaseSnapshotData snapshot,
            ProcedureVersionData procedure,
            CardCaseFacts facts
    ) {
        if (snapshot == null || procedure == null || facts == null) {
            throw new IllegalArgumentException("snapshot, procedure and facts are required");
        }
        Set<String> unresolved = new LinkedHashSet<>();
        List<String> coverageGaps = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        if (procedure.status() != ProcedureStatus.APPROVED) {
            return data(snapshot, procedure, PlanStatus.FAILED, List.of(), List.of(),
                    List.of(), List.of(), List.of("PROCEDURE_NOT_APPROVED"),
                    List.of("Only an APPROVED ProcedureVersion may execute."));
        }

        LocalDate incidentDate = incidentDate(facts, unresolved);
        if (!procedureVersionService.isApplicable(procedure, incidentDate)) {
            coverageGaps.add("PROCEDURE_NOT_APPLICABLE_FOR_INCIDENT_DATE");
        }
        if (!matchesScope(facts, unresolved)) {
            return data(snapshot, procedure, PlanStatus.UNSUPPORTED, List.of(), List.of(),
                    List.of(), unresolved, List.of("CARD_SCOPE_OUT_OF_SCOPE"), warnings);
        }
        for (ProcedureVersionData.RequiredFact required : procedure.requiredFacts()) {
            if (required.requiredForDecision() && !facts.hasKnownValue(required.key())) {
                unresolved.add(required.key());
            }
        }
        if (hasBlockingUnresolved(unresolved)) {
            return data(snapshot, procedure, PlanStatus.NEEDS_CLARIFICATION, List.of(), List.of(),
                    List.of(), unresolved, coverageGaps,
                    List.of("BLOCKING_INFORMATION_REQUIRED"));
        }
        // If no action can be determined yet, do not spend evidence resolution
        // work or manufacture a partial result from an UNKNOWN condition.
        if (!unresolved.isEmpty() && !hasDefinitelyTrueAction(procedure, facts)) {
            return data(snapshot, procedure, PlanStatus.NEEDS_CLARIFICATION, List.of(), List.of(),
                    List.of(), unresolved, coverageGaps,
                    List.of("ADDITIONAL_INFORMATION_REQUIRED"));
        }
        if (!coverageGaps.isEmpty()) {
            return data(snapshot, procedure, PlanStatus.NEEDS_CLARIFICATION, List.of(), List.of(),
                    List.of(), unresolved, coverageGaps, warnings);
        }

        List<ProcedureVersionData.EvidenceReference> applicableReferences = procedure.evidenceReferences()
                .stream()
                // K5 is an operational form with no independently verified
                // historical effective date. It is needed once compensation
                // submission is selected, but must not block the immediate
                // loss-report branch for an already-known FALSE report state.
                .filter(reference -> !"kb-unauthorized-compensation-form-260209".equals(reference.sourceKey())
                        || "TRUE".equalsIgnoreCase(facts.value("reported"))
                        || incidentDate == null)
                .toList();
        EvidenceBindingResolver.Resolution resolution = evidenceResolver.resolve(
                applicableReferences, incidentDate);
        coverageGaps.addAll(resolution.coverageGaps());
        if (!coverageGaps.isEmpty()) {
            return data(snapshot, procedure, PlanStatus.NEEDS_CLARIFICATION, List.of(), List.of(),
                    resolution.bindings(), List.of(), coverageGaps, warnings);
        }

        List<FinancialActionPlanData.Action> actions = new ArrayList<>();
        for (ProcedureVersionData.ConditionRule rule : procedure.conditionRules()) {
            ConditionResult result = ConditionEvaluator.evaluate(rule.expression(), facts);
            if (result == ConditionResult.UNKNOWN
                    && !"request-result-review".equals(rule.actionId())) {
                collectUnknownFacts(rule.expression(), facts, unresolved);
            }
            if (result == ConditionResult.TRUE) {
                procedure.actionSteps().stream()
                        .filter(step -> step.actionId().equals(rule.actionId()))
                        .findFirst()
                        .ifPresent(step -> actions.add(new FinancialActionPlanData.Action(
                                step.actionId(), step.order(), step.title(), step.description(),
                                result, step.channelRef())));
            }
        }
        actions.sort(Comparator.comparingInt(FinancialActionPlanData.Action::order));

        List<FinancialActionPlanData.Document> documents = new ArrayList<>();
        for (ProcedureVersionData.DocumentRequirement requirement : procedure.documentRequirements()) {
            ConditionResult result = ConditionEvaluator.evaluate(requirement.condition(), facts);
            if (result == ConditionResult.UNKNOWN) {
                collectUnknownFacts(requirement.condition(), facts, unresolved);
            }
            if (result == ConditionResult.TRUE
                    && !("kb-unauthorized-compensation-form-260209".equals(requirement.evidenceRef())
                    && !"TRUE".equalsIgnoreCase(facts.value("reported")))) {
                documents.add(new FinancialActionPlanData.Document(
                        requirement.documentId(), requirement.title(), requirement.status(),
                        result, requirement.evidenceRef()));
            }
        }
        if (!unresolved.isEmpty()) {
            warnings.add("PARTIAL_GUIDANCE_ONLY");
            return data(snapshot, procedure, PlanStatus.NEEDS_CLARIFICATION, actions, documents,
                    resolution.bindings(), unresolved, coverageGaps, warnings);
        }
        return data(snapshot, procedure, PlanStatus.READY, actions, documents,
                resolution.bindings(), List.of(), coverageGaps, warnings);
    }

    private boolean matchesScope(CardCaseFacts facts, Set<String> unresolved) {
        String institution = facts.value("institution");
        if (institution == null || "UNKNOWN".equalsIgnoreCase(institution)) {
            unresolved.add("institution");
        } else if (!ProcedureVersionService.KB_INSTITUTION.equals(
                ProcedureVersionService.canonicalInstitution(institution))) {
            return false;
        }
        String product = facts.value("productType");
        if (product == null || "UNKNOWN".equalsIgnoreCase(product)) {
            unresolved.add("productType");
        } else if (!ProcedureVersionService.PERSONAL_CREDIT_CARD.equals(
                ProcedureVersionService.canonicalProduct(product))) {
            return false;
        }
        String cardLost = facts.value("cardLost");
        if (cardLost == null || "UNKNOWN".equalsIgnoreCase(cardLost)) {
            unresolved.add("cardLost");
        } else if (!"TRUE".equalsIgnoreCase(cardLost)) {
            return false;
        }
        String unauthorizedPayment = facts.value("unauthorizedPayment");
        if (unauthorizedPayment == null || "UNKNOWN".equalsIgnoreCase(unauthorizedPayment)) {
            unresolved.add("unauthorizedPayment");
        } else if (!"TRUE".equalsIgnoreCase(unauthorizedPayment)) {
            return false;
        }
        String transactionType = facts.value("transactionType");
        if (transactionType == null || "UNKNOWN".equalsIgnoreCase(transactionType)) {
            unresolved.add("transactionType");
        } else if (!"CREDIT_SALE".equalsIgnoreCase(transactionType)) {
            return false;
        }
        String domestic = facts.value("domestic");
        if (domestic == null || "UNKNOWN".equalsIgnoreCase(domestic)) {
            unresolved.add("domestic");
        } else if (!"TRUE".equalsIgnoreCase(domestic)) {
            return false;
        }
        return true;
    }

    private LocalDate incidentDate(CardCaseFacts facts, Set<String> unresolved) {
        String value = facts.value("incidentDate");
        if (value == null || "UNKNOWN".equalsIgnoreCase(value)) {
            unresolved.add("incidentDate");
            return null;
        }
        LocalDate parsed = parseDate(value);
        if (parsed == null) {
            unresolved.add("incidentDate");
        }
        return parsed;
    }

    private LocalDate parseDate(String value) {
        if (value == null || value.isBlank() || "UNKNOWN".equalsIgnoreCase(value)) {
            return null;
        }
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException exception) {
            return null;
        }
    }

    private void collectUnknownFacts(
            ConditionExpression expression,
            CardCaseFacts facts,
            Set<String> target
    ) {
        if (expression == null) {
            return;
        }
        if (expression.isGroup()) {
            expression.conditions().forEach(child -> collectUnknownFacts(child, facts, target));
        } else if (ConditionEvaluator.evaluate(expression, facts) == ConditionResult.UNKNOWN) {
            target.add(expression.factKey());
        }
    }

    private boolean hasBlockingUnresolved(Set<String> unresolved) {
        return unresolved.stream().anyMatch(BLOCKING_FACTS::contains);
    }

    private boolean hasDefinitelyTrueAction(
            ProcedureVersionData procedure,
            CardCaseFacts facts
    ) {
        return procedure.conditionRules().stream()
                .anyMatch(rule -> ConditionEvaluator.evaluate(rule.expression(), facts)
                        == ConditionResult.TRUE);
    }

    private FinancialActionPlanData data(
            ConfirmedCaseSnapshotData snapshot,
            ProcedureVersionData procedure,
            PlanStatus status,
            List<FinancialActionPlanData.Action> actions,
            List<FinancialActionPlanData.Document> documents,
            List<FinancialActionPlanData.EvidenceBinding> evidence,
            Set<String> unresolved,
            List<String> gaps,
            List<String> warnings
    ) {
        return data(snapshot, procedure, status, actions, documents, evidence,
                List.copyOf(unresolved), gaps, warnings);
    }

    private FinancialActionPlanData data(
            ConfirmedCaseSnapshotData snapshot,
            ProcedureVersionData procedure,
            PlanStatus status,
            List<FinancialActionPlanData.Action> actions,
            List<FinancialActionPlanData.Document> documents,
            List<FinancialActionPlanData.EvidenceBinding> evidence,
            List<String> unresolved,
            List<String> gaps,
            List<String> warnings
    ) {
        return new FinancialActionPlanData(
                null, snapshot.consultationId(), snapshot.id(), procedure.id(),
                procedure.scenario(), procedure.version(), snapshot.caseInputRevision(),
                snapshot.followUpAnswerRevision(), status, actions, documents, evidence,
                unresolved, gaps, warnings);
    }

    private FinancialActionPlanData saveIfAbsent(
            Consultation consultation,
            ProcedureVersion procedure,
            ConfirmedCaseSnapshot snapshot,
            FinancialActionPlanData result
    ) {
        return planRepository
                .findByConsultation_IdAndProcedureVersion_IdAndCaseInputRevisionAndFollowUpAnswerRevision(
                        consultation.getId(), procedure.getId(), snapshot.getCaseInputRevision(),
                        snapshot.getFollowUpAnswerRevision())
                .map(this::toData)
                .orElseGet(() -> {
                    try {
                        String json = jsonMapper.writeValueAsString(result);
                        FinancialActionPlan saved = planRepository.saveAndFlush(new FinancialActionPlan(
                                consultation, procedure, snapshot, result.status(), json,
                                OffsetDateTime.now(ZoneOffset.UTC)));
                        return withId(result, saved.getId());
                    } catch (JacksonException exception) {
                        throw new IllegalStateException("could not serialize financial action plan", exception);
                    }
                });
    }

    private FinancialActionPlanData withId(FinancialActionPlanData data, UUID id) {
        return new FinancialActionPlanData(
                id, data.consultationId(), data.confirmedCaseSnapshotId(), data.procedureVersionId(),
                data.scenario(), data.procedureVersion(), data.caseInputRevision(),
                data.followUpAnswerRevision(), data.status(), data.actions(), data.requiredDocuments(),
                data.evidence(), data.unresolvedFacts(), data.coverageGaps(), data.warnings());
    }

    private FinancialActionPlanData toData(FinancialActionPlan plan) {
        try {
            FinancialActionPlanData parsed = jsonMapper.readValue(
                    plan.getPlanJson(), FinancialActionPlanData.class);
            return withId(parsed, plan.getId());
        } catch (JacksonException exception) {
            throw new IllegalStateException("stored financial action plan is invalid", exception);
        }
    }

    /** Keeps the dependency boundary explicit while avoiding a second snapshot implementation. */
    private static final class ConfirmedCaseSnapshotServiceAdapter {
        private final com.financialhelper.retrieval.ConfirmedCaseSnapshotService delegate;

        private ConfirmedCaseSnapshotServiceAdapter(
                com.financialhelper.retrieval.ConfirmedCaseSnapshotService delegate
        ) {
            this.delegate = delegate;
        }

        private ConfirmedCaseSnapshotData capture(UUID consultationId) {
            return delegate.capture(consultationId);
        }
    }
}
