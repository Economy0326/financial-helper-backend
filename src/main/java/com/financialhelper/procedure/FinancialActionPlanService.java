package com.financialhelper.procedure;

import com.financialhelper.consultation.Consultation;
import com.financialhelper.consultation.ConsultationCategory;
import com.financialhelper.consultation.ConsultationScenario;
import com.financialhelper.consultation.ConsultationScenarioResolver;
import com.financialhelper.consultation.ConsultationNotFoundException;
import com.financialhelper.consultation.ConsultationRepository;
import com.financialhelper.guest.GuestSession;
import com.financialhelper.guest.GuestSessionService;
import com.financialhelper.retrieval.ConfirmedCaseSnapshot;
import com.financialhelper.retrieval.ConfirmedCaseSnapshotData;
import com.financialhelper.retrieval.ConfirmedCaseSnapshotRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
    private static final Logger log = LoggerFactory.getLogger(FinancialActionPlanService.class);
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
        if (!isProcedureBacked(consultation)) {
            throw new IllegalStateException("procedure is unavailable for this consultation scenario");
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
        if (!isProcedureBacked(consultation)) {
            throw new IllegalStateException("procedure is unavailable for this consultation scenario");
        }
        return buildForConsultation(consultation);
    }

    private FinancialActionPlanData buildForConsultation(Consultation consultation) {
        ConfirmedCaseSnapshotData snapshotData = snapshotService.capture(consultation.getId());
        ConfirmedCaseSnapshot snapshot = snapshotRepository.findById(snapshotData.id())
                .orElseThrow(() -> new IllegalStateException("confirmed case snapshot is unavailable"));
        ConsultationScenario scenario = ConsultationScenarioResolver.resolve(consultation);
        CardCaseFacts extractedFacts = scenario == ConsultationScenario.CARD_LOSS_UNAUTHORIZED_USE
                ? CardCaseFactExtractor.fromSnapshot(snapshotData)
                : ScenarioCaseFactExtractor.fromSnapshot(snapshotData);
        ProcedureVersionData procedure = scenario == ConsultationScenario.CARD_LOSS_UNAUTHORIZED_USE
                ? procedureVersionService.requireApprovedCard(extractedFacts)
                : procedureVersionService.requireApproved(scenario.name(),
                        "GENERIC_FINANCIAL_INSTITUTION",
                        scenario == ConsultationScenario.PERSONAL_INFO_SMISHING_MALICIOUS_APP
                                ? "DIGITAL_FINANCIAL_SERVICE" : "BANK_ACCOUNT");
        ProcedureVersion procedureEntity = procedureRepository.findById(procedure.id())
                .orElseThrow(() -> new IllegalStateException("approved procedure entity is unavailable"));
        log.debug("FAP evaluation consultationId={}, scenario={}, snapshotCaseRevision={}, "
                        + "snapshotFollowUpRevision={}, procedureVersionId={}, procedureVersion={}, facts={}",
                consultation.getId(), scenario, snapshotData.caseInputRevision(),
                snapshotData.followUpAnswerRevision(), procedure.id(), procedure.version(),
                safeFactSummary(extractedFacts));

        FinancialActionPlanData result = buildFromSnapshot(snapshotData, procedure, extractedFacts);
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

        boolean cardProcedure = ProcedureVersionService.isCardProcedureScenario(procedure.scenario());
        LocalDate incidentDate = incidentDate(facts, unresolved, !cardProcedure);
        if (!procedureVersionService.isApplicable(procedure, incidentDate)) {
            coverageGaps.add("PROCEDURE_NOT_APPLICABLE_FOR_INCIDENT_DATE");
        }
        if (!matchesScope(procedure.scenario(), facts, unresolved)) {
            String scopeGap = cardProcedure
                    ? "CARD_SCOPE_OUT_OF_SCOPE" : "SCENARIO_SCOPE_OUT_OF_SCOPE";
            log.warn("FAP scope unsupported procedureVersionId={}, scenario={}, "
                            + "procedureVersion={}, facts={}",
                    procedure.id(), procedure.scenario(), procedure.version(), safeFactSummary(facts));
            return data(snapshot, procedure, PlanStatus.UNSUPPORTED, List.of(), List.of(),
                    List.of(), unresolved, List.of(scopeGap), warnings);
        }
        // A procedure catalog can list facts that are useful for later
        // branches without making them relevant to the action currently
        // selected.  Only retain a required fact when it participates in an
        // action or document condition (scope and temporal guards are
        // handled separately above).  This keeps optional UNKNOWN values
        // from downgrading an otherwise grounded report.
        for (ProcedureVersionData.RequiredFact required : procedure.requiredFacts()) {
            if (required.requiredForDecision()
                    && !facts.hasKnownValue(required.key())
                    && procedureUsesFact(procedure, required.key())) {
                unresolved.add(required.key());
            }
        }
        if (cardProcedure && hasUnknownCardIdentity(facts, unresolved)) {
            return data(snapshot, procedure, PlanStatus.NEEDS_CLARIFICATION, List.of(), List.of(),
                    List.of(), unresolved, coverageGaps,
                    List.of("CARD_INSTITUTION_OR_PRODUCT_REQUIRED"));
        }
        if (hasBlockingUnresolved(procedure.scenario(), unresolved)
                && !hasDefinitelyTrueAction(procedure, facts)) {
            return data(snapshot, procedure, PlanStatus.NEEDS_CLARIFICATION, List.of(), List.of(),
                    List.of(), unresolved, coverageGaps,
                    List.of("BLOCKING_INFORMATION_REQUIRED"));
        }
        // A breadth Procedure definition is not executable until its reviewed
        // official corpus is bound.  Keeping this guard in the deterministic
        // plan layer prevents an approved-looking catalog row from producing
        // unsupported actions while source acquisition/review is pending.
        if (!cardProcedure
                && procedure.evidenceReferences().isEmpty()) {
            return data(snapshot, procedure, PlanStatus.NEEDS_CLARIFICATION, List.of(), List.of(),
                    List.of(), unresolved, List.of("OFFICIAL_EVIDENCE_UNAVAILABLE"),
                    List.of("SCENARIO_CORPUS_NOT_ACTIVATED"));
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
                .filter(reference -> !cardProcedure
                        || !"kb-unauthorized-compensation-form-260209".equals(reference.sourceKey())
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
            traceCondition(procedure, rule, facts, result);
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
        if (actions.isEmpty() && documents.isEmpty() && unresolved.isEmpty()) {
            return data(snapshot, procedure, PlanStatus.NEEDS_CLARIFICATION, actions, documents,
                    resolution.bindings(), unresolved, coverageGaps,
                    List.of("NO_APPROVED_SAFE_ACTION"));
        }
        if (!unresolved.isEmpty()) {
            warnings.add("PARTIAL_GUIDANCE_ONLY");
            return data(snapshot, procedure, PlanStatus.NEEDS_CLARIFICATION, actions, documents,
                    resolution.bindings(), unresolved, coverageGaps, warnings);
        }
        return data(snapshot, procedure, PlanStatus.READY, actions, documents,
                resolution.bindings(), List.of(), coverageGaps, warnings);
    }

    private void traceCondition(
            ProcedureVersionData procedure,
            ProcedureVersionData.ConditionRule rule,
            CardCaseFacts facts,
            ConditionResult overall
    ) {
        traceConditionLeaves(procedure, rule.actionId(), rule.expression(), facts, overall);
    }

    private void traceConditionLeaves(
            ProcedureVersionData procedure,
            String actionId,
            ConditionExpression expression,
            CardCaseFacts facts,
            ConditionResult overall
    ) {
        if (expression == null) {
            return;
        }
        if (expression.isGroup()) {
            expression.conditions().forEach(child ->
                    traceConditionLeaves(procedure, actionId, child, facts, overall));
            return;
        }
        String actual = facts.value(expression.factKey());
        log.debug("FAP condition procedureVersionId={}, scenario={}, actionId={}, "
                        + "conditionKey={}, evaluatedValue={}, matched={}, result={}",
                procedure.id(), procedure.scenario(), actionId, expression.factKey(),
                safeFactValue(expression.factKey(), actual),
                ConditionEvaluator.evaluate(expression, facts) == ConditionResult.TRUE,
                overall);
    }

    private String safeFactSummary(CardCaseFacts facts) {
        if (facts == null || facts.values().isEmpty()) {
            return "{}";
        }
        return facts.values().entrySet().stream()
                .collect(java.util.stream.Collectors.toMap(
                        java.util.Map.Entry::getKey,
                        entry -> safeFactValue(entry.getKey(), entry.getValue()),
                        (left, right) -> left,
                        java.util.LinkedHashMap::new))
                .toString();
    }

    private String safeFactValue(String key, String value) {
        if (value == null || value.isBlank() || "UNKNOWN".equalsIgnoreCase(value)) {
            return "UNKNOWN";
        }
        if (Set.of("cardLost", "unauthorizedPayment", "domestic", "reported",
                "resultDisputed", "transferCompleted", "userInitiatedTransfer",
                "suspiciousTransfer", "reportedToFinancialInstitution",
                "maliciousAppInstalled", "unauthorizedTransaction", "accessCredentialExposed",
                "suspiciousLinkClicked", "remoteControlUsed", "personalInfoExposed",
                "moneyMoved", "policeReported", "financialLossOccurred",
                "authenticationInfoExposed").contains(key)) {
            return switch (value.toUpperCase(java.util.Locale.ROOT)) {
                case "TRUE", "FALSE" -> value.toUpperCase(java.util.Locale.ROOT);
                default -> "UNKNOWN";
            };
        }
        if ("transactionType".equals(key)) {
            return value;
        }
        if ("compensationStatus".equals(key)) {
            return Set.of("NOT_SUBMITTED", "SUBMITTED", "INVESTIGATING", "RESULT_RECEIVED")
                    .contains(value.toUpperCase(java.util.Locale.ROOT))
                    ? value.toUpperCase(java.util.Locale.ROOT) : "UNKNOWN";
        }
        if ("productType".equals(key)) {
            return ProcedureVersionService.PERSONAL_CREDIT_CARD.equals(value)
                    ? ProcedureVersionService.PERSONAL_CREDIT_CARD : "KNOWN";
        }
        if ("institution".equals(key)) {
            return ProcedureVersionService.KB_INSTITUTION.equals(value)
                    ? "KB_KOOKMIN_CARD" : "KNOWN";
        }
        if ("incidentDate".equals(key) || "transactionDate".equals(key)) {
            return "PRESENT";
        }
        return "PRESENT";
    }

    private boolean matchesScope(String scenario, CardCaseFacts facts, Set<String> unresolved) {
        if (!ProcedureVersionService.isCardProcedureScenario(scenario)) {
            return matchesBreadthScope(scenario, facts, unresolved);
        }
        String institution = facts.value("institution");
        if (institution == null || "UNKNOWN".equalsIgnoreCase(institution)) {
            unresolved.add("institution");
        } else if (!ProcedureVersionService.KB_INSTITUTION.equals(
                ProcedureVersionService.canonicalInstitution(institution))) {
            return scopeMismatch(ProcedureVersionService.CARD_SCENARIO, "institution", institution,
                    ProcedureVersionService.KB_INSTITUTION);
        }
        String product = facts.value("productType");
        if (product == null || "UNKNOWN".equalsIgnoreCase(product)) {
            unresolved.add("productType");
        } else if (!ProcedureVersionService.PERSONAL_CREDIT_CARD.equals(
                ProcedureVersionService.canonicalProduct(product))) {
            return scopeMismatch(ProcedureVersionService.CARD_SCENARIO, "productType", product,
                    ProcedureVersionService.PERSONAL_CREDIT_CARD);
        }
        if (ProcedureVersionService.CARD_LOSS_ONLY_SCENARIO.equals(scenario)) {
            return requireCardBoolean(facts, unresolved, "cardLost", "TRUE")
                    && requireCardBoolean(facts, unresolved, "unauthorizedPayment", "FALSE");
        }
        if (ProcedureVersionService.CARD_HELD_UNAUTHORIZED_SCENARIO.equals(scenario)) {
            return requireCardBoolean(facts, unresolved, "cardLost", "FALSE")
                    && requireCardBoolean(facts, unresolved, "unauthorizedPayment", "TRUE")
                    && requireCardEnum(facts, unresolved, "transactionType", "CREDIT_SALE")
                    && requireCardBoolean(facts, unresolved, "domestic", "TRUE");
        }
        String cardLost = facts.value("cardLost");
        if (cardLost == null || "UNKNOWN".equalsIgnoreCase(cardLost)) {
            unresolved.add("cardLost");
        } else if (!"TRUE".equalsIgnoreCase(cardLost)) {
            return scopeMismatch(ProcedureVersionService.CARD_SCENARIO, "cardLost", cardLost, "TRUE");
        }
        String unauthorizedPayment = facts.value("unauthorizedPayment");
        if (unauthorizedPayment == null || "UNKNOWN".equalsIgnoreCase(unauthorizedPayment)) {
            unresolved.add("unauthorizedPayment");
        } else if (!"TRUE".equalsIgnoreCase(unauthorizedPayment)) {
            return scopeMismatch(ProcedureVersionService.CARD_SCENARIO, "unauthorizedPayment",
                    unauthorizedPayment, "TRUE");
        }
        String transactionType = facts.value("transactionType");
        if (transactionType == null || "UNKNOWN".equalsIgnoreCase(transactionType)) {
            unresolved.add("transactionType");
        } else if (!"CREDIT_SALE".equalsIgnoreCase(transactionType)) {
            return scopeMismatch(ProcedureVersionService.CARD_SCENARIO, "transactionType",
                    transactionType, "CREDIT_SALE");
        }
        String domestic = facts.value("domestic");
        if (domestic == null || "UNKNOWN".equalsIgnoreCase(domestic)) {
            unresolved.add("domestic");
        } else if (!"TRUE".equalsIgnoreCase(domestic)) {
            return scopeMismatch(ProcedureVersionService.CARD_SCENARIO, "domestic", domestic, "TRUE");
        }
        if (ProcedureVersionService.CARD_COMPENSATION_PROCESS_SCENARIO.equals(scenario)
                || ProcedureVersionService.CARD_COMPENSATION_RESULT_SCENARIO.equals(scenario)) {
            String reported = facts.value("reported");
            if (reported == null || "UNKNOWN".equalsIgnoreCase(reported)) {
                unresolved.add("reported");
            } else if (!"TRUE".equalsIgnoreCase(reported)) {
                return scopeMismatch(scenario, "reported", reported, "TRUE");
            }
        }
        if (ProcedureVersionService.CARD_COMPENSATION_RESULT_SCENARIO.equals(scenario)) {
            String status = facts.value("compensationStatus");
            if (status == null || "UNKNOWN".equalsIgnoreCase(status)) {
                unresolved.add("compensationStatus");
            } else if (!"RESULT_RECEIVED".equalsIgnoreCase(status)) {
                return scopeMismatch(scenario, "compensationStatus", status, "RESULT_RECEIVED");
            }
        }
        return true;
    }

    private boolean requireCardBoolean(CardCaseFacts facts, Set<String> unresolved,
                                       String key, String expected) {
        String value = facts.value(key);
        if (value == null || "UNKNOWN".equalsIgnoreCase(value)) {
            unresolved.add(key);
            return true;
        }
        return expected.equalsIgnoreCase(value)
                || scopeMismatch(ProcedureVersionService.CARD_SCENARIO, key, value, expected);
    }

    private boolean requireCardEnum(CardCaseFacts facts, Set<String> unresolved,
                                    String key, String expected) {
        return requireCardBoolean(facts, unresolved, key, expected);
    }

    private boolean scopeMismatch(String scenario, String key, String actual, String expected) {
        log.warn("FAP scope condition failed scenario={}, conditionKey={}, evaluatedValue={}, "
                        + "expectedValue={}, matched=false",
                scenario, key, safeFactValue(key, actual), expected);
        return false;
    }

    private boolean matchesBreadthScope(String scenario, CardCaseFacts facts, Set<String> unresolved) {
        if (scenario == null || !Set.of(
                "VOICE_PHISHING_SUSPICIOUS_TRANSFER",
                "UNAUTHORIZED_ACCOUNT_TRANSFER",
                "PERSONAL_INFO_SMISHING_MALICIOUS_APP").contains(scenario)) return false;
        String institution = facts.value("institution");
        if (institution == null || "UNKNOWN".equalsIgnoreCase(institution)) {
            // Institution-specific channels are never guessed. Generic safe
            // actions may still be returned because the procedure uses only
            // the reviewed, institution-neutral wording.
        }
        if ("VOICE_PHISHING_SUSPICIOUS_TRANSFER".equals(scenario)) {
            String suspicious = facts.value("suspiciousTransfer");
            if (suspicious != null && !"UNKNOWN".equalsIgnoreCase(suspicious)
                    && !"TRUE".equalsIgnoreCase(suspicious)) return false;
        }
        if ("UNAUTHORIZED_ACCOUNT_TRANSFER".equals(scenario)) {
            String unauthorized = facts.value("unauthorizedTransaction");
            if (unauthorized != null && !"UNKNOWN".equalsIgnoreCase(unauthorized)
                    && !"TRUE".equalsIgnoreCase(unauthorized)) return false;
        }
        return true;
    }

    private LocalDate incidentDate(CardCaseFacts facts, Set<String> unresolved, boolean nonBlocking) {
        String value = facts.value("incidentDate");
        if (value == null || "UNKNOWN".equalsIgnoreCase(value)) {
            if (!nonBlocking) unresolved.add("incidentDate");
            return null;
        }
        LocalDate parsed = parseDate(value);
        if (parsed == null) {
            if (!nonBlocking) unresolved.add("incidentDate");
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

    private boolean procedureUsesFact(ProcedureVersionData procedure, String factKey) {
        if (factKey == null || factKey.isBlank()) {
            return false;
        }
        return procedure.conditionRules().stream()
                .anyMatch(rule -> containsFact(rule.expression(), factKey))
                || procedure.documentRequirements().stream()
                .anyMatch(requirement -> containsFact(requirement.condition(), factKey));
    }

    private boolean containsFact(ConditionExpression expression, String factKey) {
        if (expression == null) {
            return false;
        }
        if (!expression.isGroup()) {
            return factKey.equals(expression.factKey());
        }
        return expression.conditions().stream().anyMatch(child -> containsFact(child, factKey));
    }

    private boolean hasBlockingUnresolved(String scenario, Set<String> unresolved) {
        if (ProcedureVersionService.isCardProcedureScenario(scenario)) {
            return unresolved.stream().anyMatch(BLOCKING_FACTS::contains);
        }
        Set<String> blocking = Set.of(
                "institution", "productType", "transferCompleted", "userInitiatedTransfer",
                "suspiciousTransfer", "unauthorizedTransaction", "transactionType");
        return unresolved.stream().anyMatch(blocking::contains);
    }

    private boolean isProcedureBacked(Consultation consultation) {
        ConsultationScenario scenario = ConsultationScenarioResolver.resolve(consultation);
        return scenario == ConsultationScenario.CARD_LOSS_UNAUTHORIZED_USE
                || scenario == ConsultationScenario.VOICE_PHISHING_SUSPICIOUS_TRANSFER
                || scenario == ConsultationScenario.UNAUTHORIZED_ACCOUNT_TRANSFER
                || scenario == ConsultationScenario.PERSONAL_INFO_SMISHING_MALICIOUS_APP;
    }

    private String valueFrom(ConfirmedCaseSnapshotData snapshot, String key, String fallback) {
        if (snapshot != null) for (ConfirmedCaseSnapshotData.Fact fact : snapshot.facts()) {
            if (fact != null && key.equals(fact.key()) && fact.value() != null) return fact.value();
        }
        return fallback;
    }

    private boolean hasDefinitelyTrueAction(
            ProcedureVersionData procedure,
            CardCaseFacts facts
    ) {
        return procedure.conditionRules().stream()
                .anyMatch(rule -> ConditionEvaluator.evaluate(rule.expression(), facts)
                        == ConditionResult.TRUE);
    }

    private boolean hasUnknownCardIdentity(CardCaseFacts facts, Set<String> unresolved) {
        return unresolved.contains("institution") || unresolved.contains("productType")
                || facts.isUnknown("institution") || facts.isUnknown("productType");
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
