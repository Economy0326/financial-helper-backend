package com.financialhelper.source;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Small boundary helper for the extensible JSON metadata columns used by the
 * source and retrieval persistence foundation.
 *
 * <p>Metadata is compared as JSON trees, rather than as raw strings, so key
 * ordering or insignificant whitespace cannot create a second definition.</p>
 */
final class JsonObjectSupport {

    private static final JsonMapper JSON_MAPPER =
            JsonMapper.builder().build();

    private JsonObjectSupport() {
    }

    static String requireObject(
            String json,
            String fieldName
    ) {
        if (json == null || json.isBlank()) {
            throw new IllegalArgumentException(
                    fieldName + " must be a JSON object"
            );
        }

        try {
            JsonNode node =
                    JSON_MAPPER.readTree(json);

            if (node == null || !node.isObject()) {
                throw new IllegalArgumentException(
                        fieldName + " must be a JSON object"
                );
            }

            // Store a compact, syntactically valid representation.  Equality
            // checks still use the parsed tree, so object key order is ignored.
            return JSON_MAPPER.writeValueAsString(node);

        } catch (JacksonException exception) {
            throw new IllegalArgumentException(
                    fieldName + " must be a JSON object",
                    exception
            );
        }
    }

    static boolean deepEquals(
            String left,
            String right
    ) {
        try {
            JsonNode leftNode =
                    JSON_MAPPER.readTree(left);
            JsonNode rightNode =
                    JSON_MAPPER.readTree(right);

            return leftNode != null
                    && rightNode != null
                    && leftNode.equals(rightNode);

        } catch (JacksonException exception) {
            // Values written through the entity boundary and the DB check are
            // expected to be valid JSON.  Treat an invalid persisted value as
            // a definition conflict rather than exposing its contents.
            return false;
        }
    }
}
