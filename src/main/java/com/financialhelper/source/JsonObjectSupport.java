package com.financialhelper.source;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * source 및 retrieval persistence 기반에서 사용하는 확장 가능한 JSON metadata
 * column을 위한 작은 경계 helper다.
 *
 * <p>Metadata를 raw string 대신 JSON tree로 비교하므로 key 순서나 의미 없는
 * 공백 때문에 두 번째 정의가 생기지 않는다.</p>
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

            // 작고 문법적으로 유효한 representation을 저장한다. 동등성 검사는
            // parse된 tree를 사용하므로 object key 순서를 무시한다.
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
            // entity 경계와 DB check를 통해 쓴 값은 유효한 JSON이어야 한다.
            // 유효하지 않은 저장 값은 내용을 노출하지 않고 정의 conflict로 처리한다.
            return false;
        }
    }
}
