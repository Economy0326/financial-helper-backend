package com.financialhelper.procedure;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Service
public class ProcedureVersionService {
    public static final String CARD_SCENARIO = "CARD_LOSS_UNAUTHORIZED_USE";
    public static final String CARD_LOSS_ONLY_SCENARIO = "CARD_LOSS_ONLY";
    public static final String CARD_HELD_UNAUTHORIZED_SCENARIO = "CARD_HELD_UNAUTHORIZED_USE";
    public static final String CARD_COMPENSATION_PROCESS_SCENARIO = "CARD_COMPENSATION_PROCESS";
    public static final String CARD_COMPENSATION_RESULT_SCENARIO = "CARD_COMPENSATION_RESULT";
    public static final String KB_INSTITUTION = "㈜KB국민카드";
    public static final String PERSONAL_CREDIT_CARD = "PERSONAL_CREDIT_CARD";

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
     * Selects a CARD branch from confirmed facts only.  This keeps branch
     * selection deterministic and prevents a newer branch row from replacing
     * the established loss-plus-unauthorized-payment baseline.
     */
    @Transactional(readOnly = true)
    public ProcedureVersionData requireApprovedCard(CardCaseFacts facts) {
        String branch = selectCardProcedureScenario(facts);
        return requireApproved(branch, KB_INSTITUTION, PERSONAL_CREDIT_CARD);
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
            return new ProcedureVersionData(
                    entity.getId(), entity.getScenario(), entity.getInstitution(),
                    entity.getProductType(), entity.getVersion(), entity.getStatus(),
                    entity.getApplicabilityStartDate(), entity.getApplicabilityEndDate(),
                    parseRequiredFacts(entity.getRequiredFactsJson()),
                    parseConditionRules(entity.getConditionRulesJson()),
                    parseActionSteps(entity.getActionStepsJson()),
                    parseDocumentRequirements(entity.getDocumentRequirementsJson()),
                    parseEvidenceReferences(entity.getEvidenceReferencesJson()),
                    parseReviewedContacts(entity.getReviewedContactsJson()),
                    parseReviewedValues(entity.getReviewedValuesJson()),
                    parseStrings(entity.getConditionsExceptionsJson()),
                    entity.getReviewNotes(), entity.getReviewedBy(), entity.getReviewedAt());
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

    private List<ProcedureVersionData.EvidenceReference> parseEvidenceReferences(String json)
            throws JacksonException {
        List<ProcedureVersionData.EvidenceReference> result = new ArrayList<>();
        for (JsonNode node : jsonMapper.readTree(json)) {
            result.add(new ProcedureVersionData.EvidenceReference(
                    requiredText(node, "sourceKey"), integerValue(node, "documentVersion"),
                    requiredText(node, "expectedRawSha256"), nullableText(node, "articleReference"),
                    nullableText(node, "pageReference"), requiredText(node, "role"),
                    booleanValue(node, "historicalApplicabilityRequired",
                            !"PROCEDURE".equals(requiredText(node, "role"))
                                    && !"REPORT_CHANNEL".equals(requiredText(node, "role")))));
        }
        return List.copyOf(result);
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
