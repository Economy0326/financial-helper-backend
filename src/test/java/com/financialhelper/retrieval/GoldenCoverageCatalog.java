package com.financialhelper.retrieval;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Evaluation questions, not approved source evidence or a financial answer key. */
final class GoldenCoverageCatalog {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final List<String> FILES = List.of("card", "voice", "account", "smishing");

    private GoldenCoverageCatalog() {}

    static List<Case> load() {
        List<Case> cases = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (String file : FILES) {
            String path = "official/coverage/" + file + "-questions.json";
            try (InputStream input = GoldenCoverageCatalog.class.getClassLoader().getResourceAsStream(path)) {
                if (input == null) throw new IllegalStateException("Missing coverage fixture: " + path);
                JsonNode root = JSON.readTree(new String(input.readAllBytes(), StandardCharsets.UTF_8));
                if (!root.isArray() || root.isEmpty()) throw new IllegalStateException("Empty coverage fixture: " + path);
                for (JsonNode item : root) {
                    JsonNode facts = item.path("confirmedFacts");
                    Case value = new Case(text(item, "caseId"), text(item, "scenario"),
                            text(item, "question"), nullable(facts, "institution"),
                            nullable(facts, "productType"), nullable(facts, "incidentDate"),
                            list(item, "requiredFacts"), text(item, "expectedInformationScope"),
                            text(item, "requiredEvidenceType"), list(item, "allowedStatuses"),
                            list(item, "exclude"), item.path("conditionRequired").asBoolean(),
                            item.path("exceptionRequired").asBoolean(),
                            text(item, "answerKeyStatus"), text(item, "unverifiedReason"),
                            text(item, "syntheticProbe"));
                    if (!ids.add(value.caseId())) throw new IllegalStateException("Duplicate case: " + value.caseId());
                    cases.add(value);
                }
            } catch (java.io.IOException exception) {
                throw new IllegalStateException("Invalid coverage fixture: " + path, exception);
            }
        }
        return List.copyOf(cases);
    }

    private static String text(JsonNode node, String field) {
        String value = nullable(node, field);
        if (value == null || value.isBlank()) throw new IllegalStateException("Missing coverage field: " + field);
        return value;
    }

    private static String nullable(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? null : value.asText();
    }

    private static List<String> list(JsonNode node, String field) {
        JsonNode values = node.path(field);
        if (!values.isArray() || values.isEmpty()) throw new IllegalStateException("Missing coverage list: " + field);
        List<String> result = new ArrayList<>();
        values.forEach(value -> result.add(value.asText()));
        return List.copyOf(result);
    }

    record Case(String caseId, String scenario, String question, String institution,
                String productType, String incidentDate, List<String> requiredFacts,
                String expectedInformationScope, String requiredEvidenceType,
                List<String> allowedStatuses, List<String> excluded, boolean conditionRequired,
                boolean exceptionRequired, String answerKeyStatus, String unverifiedReason,
                String syntheticProbe) {
        @Override public String toString() { return caseId; }
    }
}
