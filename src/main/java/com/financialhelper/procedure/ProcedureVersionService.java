package com.financialhelper.procedure;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
public class ProcedureVersionService {
    private static final Logger log = LoggerFactory.getLogger(ProcedureVersionService.class);
    public static final String CARD_SCENARIO = "CARD_LOSS_UNAUTHORIZED_USE";
    public static final String CARD_LOSS_ONLY_SCENARIO = "CARD_LOSS_ONLY";
    public static final String CARD_HELD_UNAUTHORIZED_SCENARIO = "CARD_HELD_UNAUTHORIZED_USE";
    public static final String CARD_COMPENSATION_PROCESS_SCENARIO = "CARD_COMPENSATION_PROCESS";
    public static final String CARD_COMPENSATION_RESULT_SCENARIO = "CARD_COMPENSATION_RESULT";
    public static final String KB_INSTITUTION = "㈜KB국민카드";
    public static final String PERSONAL_CREDIT_CARD = "PERSONAL_CREDIT_CARD";
    private static final Set<String> EVIDENCE_ROLES = Set.of(
            "REPORT_CHANNEL", "PROCEDURE", "FORM", "INVESTIGATION",
            "FOLLOW_UP", "SAFE_GUIDANCE", "TERMS", "GUIDELINE");

    private final ProcedureVersionRepository repository;
    private final JsonMapper jsonMapper;

    public ProcedureVersionService(
            ProcedureVersionRepository repository,
            JsonMapper jsonMapper
    ) {
        this.repository = repository;
        this.jsonMapper = jsonMapper;
    }

    @Transactional(readOnly = true)
    public ProcedureVersionData requireApproved(
            String scenario,
            String institution,
            String productType
    ) {
        if (scenario == null || institution == null || productType == null) {
            throw new IllegalArgumentException("scenario, institution and productType are required");
        }
        ProcedureVersion entity = repository.findLatestApproved(
                        scenario,
                        canonicalInstitution(institution),
                        canonicalProduct(productType))
                .orElseThrow(() -> new IllegalStateException("approved procedure is unavailable"));
        return toData(entity);
    }

    @Transactional(readOnly = true)
    public ProcedureVersionData requireApprovedCard() {
        return requireApproved(CARD_SCENARIO, KB_INSTITUTION, PERSONAL_CREDIT_CARD);
    }

    /**
     * 확정 fact만으로 CARD branch를 선택한다. branch 선택을 결정적으로 유지하고
     * 새로운 branch row가 기존 분실 및 미인지 결제 baseline을 대체하지 못하게 한다.
     */
    @Transactional(readOnly = true)
    public ProcedureVersionData requireApprovedCard(CardCaseFacts facts) {
        String branch = selectCardProcedureScenario(facts);
        return requireApproved(branch, KB_INSTITUTION, PERSONAL_CREDIT_CARD);
    }

    public enum SelectionFailure {
        DATE_REQUIRED, BRANCH_FACT_REQUIRED, NO_APPLICABLE_VERSION,
        OVERLAPPING_APPROVED_VERSIONS
    }

    public record Selection(ProcedureVersionData procedure, SelectionFailure failure,
                            String blockingFact) {
        public Selection(ProcedureVersionData procedure, SelectionFailure failure) {
            this(procedure, failure, null);
        }
        public boolean selected() { return procedure != null; }
    }

    public record CardBranchSelection(String scenario, String missingFact) {
        public boolean selected() { return missingFact == null; }
    }

    /** Only facts that can change the selected CARD branch are required. */
    public CardBranchSelection selectCardBranch(CardCaseFacts facts) {
        if (facts == null || !facts.hasKnownValue("cardLost"))
            return new CardBranchSelection(null, "cardLost");
        if (!facts.hasKnownValue("unauthorizedPayment"))
            return new CardBranchSelection(null, "unauthorizedPayment");
        String lost = facts.value("cardLost");
        String payment = facts.value("unauthorizedPayment");
        if ("TRUE".equalsIgnoreCase(lost) && "FALSE".equalsIgnoreCase(payment))
            return new CardBranchSelection(CARD_LOSS_ONLY_SCENARIO, null);
        if ("FALSE".equalsIgnoreCase(lost) && "TRUE".equalsIgnoreCase(payment))
            return new CardBranchSelection(CARD_HELD_UNAUTHORIZED_SCENARIO, null);
        if ("TRUE".equalsIgnoreCase(lost) && "TRUE".equalsIgnoreCase(payment)) {
            if (!facts.hasKnownValue("reported"))
                return new CardBranchSelection(null, "reported");
            if ("TRUE".equalsIgnoreCase(facts.value("reported"))) {
                if (!facts.hasKnownValue("compensationStatus"))
                    return new CardBranchSelection(null, "compensationStatus");
                return new CardBranchSelection(
                        "RESULT_RECEIVED".equalsIgnoreCase(facts.value("compensationStatus"))
                                ? CARD_COMPENSATION_RESULT_SCENARIO
                                : CARD_COMPENSATION_PROCESS_SCENARIO, null);
            }
        }
        return new CardBranchSelection(CARD_SCENARIO, null);
    }

    @Transactional(readOnly = true)
    public Selection selectApplicableCard(CardCaseFacts facts, LocalDate date) {
        CardBranchSelection branch = selectCardBranch(facts);
        if (!branch.selected())
            return new Selection(null, SelectionFailure.BRANCH_FACT_REQUIRED, branch.missingFact());
        return selectApplicable(branch.scenario(), KB_INSTITUTION, PERSONAL_CREDIT_CARD, date, true);
    }

    @Transactional(readOnly = true)
    public List<ProcedureVersionData> approvedCandidates(
            String scenario, String institution, String productType) {
        return repository.findByScenarioAndInstitutionAndProductTypeAndStatus(
                scenario, canonicalInstitution(institution), canonicalProduct(productType),
                ProcedureStatus.APPROVED).stream().map(this::toData).toList();
    }

    @Transactional(readOnly = true)
    public Selection selectApplicable(
            String scenario, String institution, String productType, LocalDate date,
            boolean dateRequired
    ) {
        if (scenario == null || institution == null || productType == null) {
            throw new IllegalArgumentException("scenario, institution and productType are required");
        }
        if (dateRequired && date == null) {
            return new Selection(null, SelectionFailure.DATE_REQUIRED);
        }
        List<ProcedureVersion> candidates = repository.findByScenarioAndInstitutionAndProductTypeAndStatus(
                scenario, canonicalInstitution(institution), canonicalProduct(productType),
                ProcedureStatus.APPROVED);
        if (date == null && (candidates.size() != 1
                || candidates.getFirst().getApplicabilityStartDate() != null
                || candidates.getFirst().getApplicabilityEndDate() != null)) {
            return new Selection(null, candidates.size() > 1
                    ? SelectionFailure.OVERLAPPING_APPROVED_VERSIONS
                    : SelectionFailure.NO_APPLICABLE_VERSION);
        }
        List<ProcedureVersion> applicable = candidates.stream()
                .filter(candidate -> date == null
                        ? candidate.getApplicabilityStartDate() == null
                                && candidate.getApplicabilityEndDate() == null
                        : (candidate.getApplicabilityStartDate() == null
                                || !date.isBefore(candidate.getApplicabilityStartDate()))
                                && (candidate.getApplicabilityEndDate() == null
                                || !date.isAfter(candidate.getApplicabilityEndDate())))
                .toList();
        if (applicable.isEmpty()) {
            return new Selection(null, SelectionFailure.NO_APPLICABLE_VERSION);
        }
        if (applicable.size() != 1) {
            log.warn("Overlapping approved ProcedureVersions scenario={}, institution={}, productType={}, "
                            + "candidates={}", scenario, institution, productType,
                    applicable.stream().map(candidate -> candidate.getId() + ":"
                            + candidate.getApplicabilityStartDate() + ".."
                            + candidate.getApplicabilityEndDate()).toList());
            return new Selection(null, SelectionFailure.OVERLAPPING_APPROVED_VERSIONS);
        }
        return new Selection(toData(applicable.getFirst()), null);
    }

    public String selectCardProcedureScenario(CardCaseFacts facts) {
        if (facts == null) return CARD_SCENARIO;
        String cardLost = facts.value("cardLost");
        String unauthorized = facts.value("unauthorizedPayment");
        String reported = facts.value("reported");
        if ("TRUE".equalsIgnoreCase(cardLost) && "FALSE".equalsIgnoreCase(unauthorized)) {
            return CARD_LOSS_ONLY_SCENARIO;
        }
        if ("FALSE".equalsIgnoreCase(cardLost) && "TRUE".equalsIgnoreCase(unauthorized)) {
            return CARD_HELD_UNAUTHORIZED_SCENARIO;
        }
        if ("TRUE".equalsIgnoreCase(cardLost)
                && "TRUE".equalsIgnoreCase(unauthorized)
                && "TRUE".equalsIgnoreCase(reported)) {
            return "RESULT_RECEIVED".equalsIgnoreCase(facts.value("compensationStatus"))
                    ? CARD_COMPENSATION_RESULT_SCENARIO
                    : CARD_COMPENSATION_PROCESS_SCENARIO;
        }
        return CARD_SCENARIO;
    }

    public static boolean isCardProcedureScenario(String scenario) {
        return CARD_SCENARIO.equals(scenario)
                || CARD_LOSS_ONLY_SCENARIO.equals(scenario)
                || CARD_HELD_UNAUTHORIZED_SCENARIO.equals(scenario)
                || CARD_COMPENSATION_PROCESS_SCENARIO.equals(scenario)
                || CARD_COMPENSATION_RESULT_SCENARIO.equals(scenario);
    }

    public ProcedureVersionData toData(ProcedureVersion entity) {
        if (entity == null || !entity.isApproved()) {
            throw new IllegalStateException("procedure version is not approved");
        }
        try {
            List<ProcedureVersionData.ActionStep> actions = parseActionSteps(entity.getActionStepsJson());
            ParsedEvidenceReferences parsed =
                    parseEvidenceReferences(entity.getEvidenceReferencesJson());
            validateEvidenceContract(actions, parsed);
            return new ProcedureVersionData(
                    entity.getId(), entity.getScenario(), entity.getInstitution(),
                    entity.getProductType(), entity.getVersion(), entity.getStatus(),
                    entity.getApplicabilityStartDate(), entity.getApplicabilityEndDate(),
                    parseRequiredFacts(entity.getRequiredFactsJson()),
                    parseConditionRules(entity.getConditionRulesJson()),
                    actions,
                    parseDocumentRequirements(entity.getDocumentRequirementsJson()),
                    parsed.references(),
                    parseReviewedContacts(entity.getReviewedContactsJson()),
                    parseReviewedValues(entity.getReviewedValuesJson()),
                    parseStrings(entity.getConditionsExceptionsJson()),
                    entity.getReviewNotes(), entity.getReviewedBy(), entity.getReviewedAt(),
                    parsed.contract());
        } catch (JacksonException exception) {
            throw new IllegalStateException("approved procedure definition is invalid", exception);
        }
    }

    public boolean isApplicable(ProcedureVersionData procedure, LocalDate incidentDate) {
        if (procedure == null) {
            return false;
        }
        if (incidentDate == null) {
            return true;
        }
        if (procedure.applicabilityStartDate() != null
                && incidentDate.isBefore(procedure.applicabilityStartDate())) {
            return false;
        }
        return procedure.applicabilityEndDate() == null
                || !incidentDate.isAfter(procedure.applicabilityEndDate());
    }

    public static String canonicalInstitution(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim().replace(" ", "");
        if (normalized.equals("KB_KOOKMIN_CARD") || normalized.equals("KB국민카드") || normalized.equals("㈜KB국민카드")
                || normalized.equals("주식회사KB국민카드")) {
            return KB_INSTITUTION;
        }
        return value.trim();
    }

    public static String canonicalProduct(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        if (normalized.equals(PERSONAL_CREDIT_CARD)
                || value.contains("개인") && value.contains("본인") && value.contains("신용카드")) {
            return PERSONAL_CREDIT_CARD;
        }
        return value.trim();
    }

    private List<ProcedureVersionData.RequiredFact> parseRequiredFacts(String json)
            throws JacksonException {
        List<ProcedureVersionData.RequiredFact> result = new ArrayList<>();
        for (JsonNode node : jsonMapper.readTree(json)) {
            String key = requiredText(node, "key");
            if (!ScenarioCaseFactExtractor.ALLOWED_FACT_KEYS.contains(key)
                    && !CardCaseFactExtractor.ALLOWED_FACT_KEYS.contains(key)) {
                throw new IllegalArgumentException("procedure contains unsupported fact key");
            }
            result.add(new ProcedureVersionData.RequiredFact(
                    key, requiredText(node, "type"), booleanValue(node, "requiredForDecision")));
        }
        return List.copyOf(result);
    }

    private List<ProcedureVersionData.ConditionRule> parseConditionRules(String json)
            throws JacksonException {
        List<ProcedureVersionData.ConditionRule> result = new ArrayList<>();
        for (JsonNode node : jsonMapper.readTree(json)) {
            ConditionExpression expression = ConditionExpression.fromJson(node.get("expression"));
            validateConditionFacts(expression);
            result.add(new ProcedureVersionData.ConditionRule(
                    requiredText(node, "actionId"),
                    expression));
        }
        return List.copyOf(result);
    }

    private List<ProcedureVersionData.ActionStep> parseActionSteps(String json)
            throws JacksonException {
        List<ProcedureVersionData.ActionStep> result = new ArrayList<>();
        for (JsonNode node : jsonMapper.readTree(json)) {
            List<String> roles = new ArrayList<>();
            JsonNode roleNode = node.get("evidenceRoles");
            if (roleNode != null && roleNode.isArray()) {
                for (JsonNode role : roleNode) {
                    roles.add(role.asText());
                }
            }
            result.add(new ProcedureVersionData.ActionStep(
                    requiredText(node, "actionId"), integerValue(node, "order"),
                    requiredText(node, "title"), requiredText(node, "description"),
                    nullableText(node, "channelRef"), roles));
        }
        return result.stream().sorted(java.util.Comparator.comparingInt(
                ProcedureVersionData.ActionStep::order)).toList();
    }

    private List<ProcedureVersionData.DocumentRequirement> parseDocumentRequirements(String json)
            throws JacksonException {
        List<ProcedureVersionData.DocumentRequirement> result = new ArrayList<>();
        for (JsonNode node : jsonMapper.readTree(json)) {
            ConditionExpression condition = ConditionExpression.fromJson(node.get("condition"));
            validateConditionFacts(condition);
            result.add(new ProcedureVersionData.DocumentRequirement(
                    requiredText(node, "documentId"), requiredText(node, "title"),
                    requiredText(node, "status"),
                    condition,
                    requiredText(node, "evidenceRef")));
        }
        return List.copyOf(result);
    }

    private ParsedEvidenceReferences parseEvidenceReferences(String json)
            throws JacksonException {
        JsonNode root = jsonMapper.readTree(json);
        ProcedureVersionData.EvidenceContract contract;
        JsonNode nodes;
        if (root.isArray()) {
            contract = ProcedureVersionData.EvidenceContract.LEGACY;
            nodes = root;
        } else if (root.isObject()) {
            if (!"ACTION_SCOPED".equals(nullableText(root, "contract"))) {
                throw new IllegalArgumentException("evidence contract must be ACTION_SCOPED");
            }
            nodes = root.get("references");
            if (nodes == null || !nodes.isArray()) {
                throw new IllegalArgumentException("evidence references must be an array");
            }
            contract = ProcedureVersionData.EvidenceContract.ACTION_SCOPED;
        } else {
            throw new IllegalArgumentException("evidence references must be a legacy array or action-scoped object");
        }
        List<ProcedureVersionData.EvidenceReference> result = new ArrayList<>();
        for (JsonNode node : nodes) {
            if (!node.isObject()) {
                throw new IllegalArgumentException("evidence reference must be an object");
            }
            JsonNode idNode = node.get("referenceId");
            JsonNode actionsNode = node.get("actionIds");
            if (contract == ProcedureVersionData.EvidenceContract.LEGACY
                    && (idNode != null || actionsNode != null)) {
                throw new IllegalArgumentException("legacy array cannot contain referenceId or actionIds");
            }
            if (contract == ProcedureVersionData.EvidenceContract.ACTION_SCOPED
                    && (idNode == null || actionsNode == null)) {
                if (idNode == null && actionsNode == null) {
                    throw new IllegalArgumentException("legacy and action-scoped evidence references cannot be mixed");
                }
                throw new IllegalArgumentException("action-scoped referenceId and actionIds are required");
            }
            String referenceId = null;
            List<String> actionIds = null;
            if (idNode != null) {
                if (!idNode.isString() || idNode.asText().isBlank()) {
                    throw new IllegalArgumentException("referenceId must be nonblank text");
                }
                if ("*".equals(idNode.asText())) {
                    throw new IllegalArgumentException("referenceId wildcard is not allowed");
                }
                referenceId = idNode.asText();
                if (!actionsNode.isArray() || actionsNode.isEmpty()) {
                    throw new IllegalArgumentException("actionIds must be a nonempty array");
                }
                actionIds = new ArrayList<>();
                Set<String> seenActions = new HashSet<>();
                for (JsonNode actionNode : actionsNode) {
                    if (!actionNode.isString() || actionNode.asText().isBlank()
                            || "*".equals(actionNode.asText())) {
                        throw new IllegalArgumentException("actionIds must contain explicit nonblank action IDs");
                    }
                    if (!seenActions.add(actionNode.asText())) {
                        throw new IllegalArgumentException("actionIds contains a duplicate action ID");
                    }
                    actionIds.add(actionNode.asText());
                }
            }
            result.add(new ProcedureVersionData.EvidenceReference(
                    requiredText(node, "sourceKey"), integerValue(node, "documentVersion"),
                    requiredText(node, "expectedRawSha256"), nullableText(node, "articleReference"),
                    nullableText(node, "pageReference"), requiredText(node, "role"),
                    booleanValue(node, "historicalApplicabilityRequired",
                            !"PROCEDURE".equals(requiredText(node, "role"))
                                    && !"REPORT_CHANNEL".equals(requiredText(node, "role"))),
                    referenceId, actionIds));
        }
        return new ParsedEvidenceReferences(contract, List.copyOf(result));
    }

    private record ParsedEvidenceReferences(
            ProcedureVersionData.EvidenceContract contract,
            List<ProcedureVersionData.EvidenceReference> references
    ) {}

    private static void validateEvidenceContract(
            List<ProcedureVersionData.ActionStep> actions,
            ParsedEvidenceReferences parsed
    ) {
        if (parsed.contract() == ProcedureVersionData.EvidenceContract.LEGACY) {
            return;
        }
        List<ProcedureVersionData.EvidenceReference> references = parsed.references();
        if (references.stream().anyMatch(ProcedureVersionData.EvidenceReference::isLegacy)) {
            throw new IllegalArgumentException("legacy and action-scoped evidence references cannot be mixed");
        }
        Map<String, ProcedureVersionData.ActionStep> actionsById = new HashMap<>();
        for (ProcedureVersionData.ActionStep action : actions) {
            if (actionsById.putIfAbsent(action.actionId(), action) != null) {
                throw new IllegalArgumentException("actionId must be unique in an action-scoped procedure");
            }
            if (!EVIDENCE_ROLES.containsAll(action.evidenceRoles())) {
                throw new IllegalArgumentException("action evidenceRoles contains an unsupported role");
            }
        }
        Set<String> referenceIds = new HashSet<>();
        Map<String, Set<String>> rolesByAction = new HashMap<>();
        for (ProcedureVersionData.EvidenceReference reference : references) {
            if (!referenceIds.add(reference.referenceId())) {
                throw new IllegalArgumentException("referenceId must be unique in a procedure version");
            }
            if (!EVIDENCE_ROLES.contains(reference.role())) {
                throw new IllegalArgumentException("reference role is unsupported");
            }
            for (String actionId : reference.actionIds()) {
                ProcedureVersionData.ActionStep action = actionsById.get(actionId);
                if (action == null) {
                    throw new IllegalArgumentException("actionIds contains an unknown action ID");
                }
                if (!action.evidenceRoles().contains(reference.role())) {
                    throw new IllegalArgumentException("reference role is not required by its actionId");
                }
                rolesByAction.computeIfAbsent(actionId, ignored -> new HashSet<>())
                        .add(reference.role());
            }
        }
        for (ProcedureVersionData.ActionStep action : actions) {
            if (!rolesByAction.getOrDefault(action.actionId(), Set.of())
                    .containsAll(action.evidenceRoles())) {
                throw new IllegalArgumentException("actionId is missing a required evidence role reference");
            }
        }
    }

    private List<ProcedureVersionData.ReviewedContact> parseReviewedContacts(String json)
            throws JacksonException {
        List<ProcedureVersionData.ReviewedContact> result = new ArrayList<>();
        for (JsonNode node : jsonMapper.readTree(json)) {
            result.add(new ProcedureVersionData.ReviewedContact(
                    requiredText(node, "key"), requiredText(node, "value"), requiredText(node, "evidenceRef")));
        }
        return List.copyOf(result);
    }

    private List<ProcedureVersionData.ReviewedValue> parseReviewedValues(String json)
            throws JacksonException {
        List<ProcedureVersionData.ReviewedValue> result = new ArrayList<>();
        for (JsonNode node : jsonMapper.readTree(json)) {
            result.add(new ProcedureVersionData.ReviewedValue(
                    requiredText(node, "key"), requiredText(node, "value"), requiredText(node, "evidenceRef")));
        }
        return List.copyOf(result);
    }

    private List<String> parseStrings(String json) throws JacksonException {
        List<String> result = new ArrayList<>();
        for (JsonNode node : jsonMapper.readTree(json)) {
            result.add(node.asText());
        }
        return List.copyOf(result);
    }

    private static String requiredText(JsonNode node, String name) {
        String value = nullableText(node, name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("procedure field " + name + " is required");
        }
        return value;
    }

    private static String nullableText(JsonNode node, String name) {
        JsonNode value = node == null ? null : node.get(name);
        return value == null || value.isNull() ? null : value.asText();
    }

    private static boolean booleanValue(JsonNode node, String name) {
        return booleanValue(node, name, false);
    }

    private static boolean booleanValue(JsonNode node, String name, boolean defaultValue) {
        JsonNode value = node.get(name);
        return value == null ? defaultValue : value.asBoolean(defaultValue);
    }

    private static void validateConditionFacts(ConditionExpression expression) {
        if (expression == null) {
            throw new IllegalArgumentException("condition is required");
        }
        if (expression.isGroup()) {
            expression.conditions().forEach(ProcedureVersionService::validateConditionFacts);
            return;
        }
        if (!ScenarioCaseFactExtractor.ALLOWED_FACT_KEYS.contains(expression.factKey())
                && !CardCaseFactExtractor.ALLOWED_FACT_KEYS.contains(expression.factKey())) {
            throw new IllegalArgumentException("condition contains unsupported fact key");
        }
    }

    private static int integerValue(JsonNode node, String name) {
        JsonNode value = node.get(name);
        if (value == null || !value.isNumber()) {
            throw new IllegalArgumentException("procedure field " + name + " must be numeric");
        }
        return value.asInt();
    }
}
