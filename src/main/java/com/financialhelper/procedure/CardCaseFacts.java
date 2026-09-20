package com.financialhelper.procedure;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 결정적 Procedure evaluator가 사용하는 consultation 범위의 사용자 확정 값이다.
 * 누락된 key와 literal UNKNOWN은 FALSE와 구분하며 이 value object가 추측하지 않는다.
 */
public record CardCaseFacts(Map<String, String> values) {
    public CardCaseFacts {
        Map<String, String> copy = new LinkedHashMap<>();
        if (values != null) {
            values.forEach((key, value) -> {
                if (key != null && !key.isBlank() && value != null) {
                    copy.put(key, value.trim());
                }
            });
        }
        values = Map.copyOf(copy);
    }

    public String value(String key) {
        return values.get(key);
    }

    public boolean hasKnownValue(String key) {
        String value = value(key);
        return value != null && !"UNKNOWN".equalsIgnoreCase(value);
    }

    public boolean isUnknown(String key) {
        return !hasKnownValue(key);
    }

    public CardCaseFacts with(String key, String value) {
        Map<String, String> next = new LinkedHashMap<>(values);
        if (value == null) {
            next.remove(key);
        } else {
            next.put(key, value);
        }
        return new CardCaseFacts(next);
    }
}
