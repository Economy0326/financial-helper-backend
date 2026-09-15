package com.financialhelper.procedure;

import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;

/** Restricted, data-only condition tree. It is never evaluated as code. */
public record ConditionExpression(
        String op,
        String factKey,
        ConditionOperator operator,
        String expectedValue,
        List<ConditionExpression> conditions
) {
    public ConditionExpression {
        op = op == null ? null : op.trim().toUpperCase();
        factKey = factKey == null ? null : factKey.trim();
        expectedValue = expectedValue == null ? null : expectedValue.trim();
        conditions = conditions == null ? List.of() : List.copyOf(conditions);
    }

    public boolean isGroup() {
        return "AND".equals(op) || "OR".equals(op);
    }

    public static ConditionExpression fromJson(JsonNode node) {
        if (node == null || !node.isObject()) {
            throw new IllegalArgumentException("condition must be an object");
        }
        JsonNode opNode = node.get("op");
        if (opNode != null && !opNode.isNull()) {
            String op = opNode.asText(null);
            if (!"AND".equalsIgnoreCase(op) && !"OR".equalsIgnoreCase(op)) {
                throw new IllegalArgumentException("unsupported condition group");
            }
            JsonNode children = node.get("conditions");
            if (children == null || !children.isArray() || children.isEmpty()) {
                throw new IllegalArgumentException("condition group must have children");
            }
            List<ConditionExpression> parsed = new ArrayList<>();
            for (JsonNode child : children) {
                parsed.add(fromJson(child));
            }
            return new ConditionExpression(op, null, null, null, parsed);
        }

        String factKey = text(node, "factKey");
        String operatorText = text(node, "operator");
        ConditionOperator operator;
        try {
            operator = ConditionOperator.valueOf(operatorText.toUpperCase());
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("unsupported condition operator");
        }
        String expected = node.get("expectedValue") == null
                ? null : node.get("expectedValue").asText(null);
        if (factKey.isBlank() || (operator != ConditionOperator.IS_UNKNOWN
                && (expected == null || expected.isBlank()))) {
            throw new IllegalArgumentException("condition leaf is incomplete");
        }
        return new ConditionExpression(null, factKey, operator, expected, List.of());
    }

    private static String text(JsonNode node, String name) {
        JsonNode value = node.get(name);
        if (value == null || value.isNull() || value.asText().isBlank()) {
            throw new IllegalArgumentException("condition " + name + " is required");
        }
        return value.asText();
    }
}
