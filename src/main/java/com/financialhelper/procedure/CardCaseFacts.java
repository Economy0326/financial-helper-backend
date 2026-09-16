package com.financialhelper.procedure;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Consultation-scoped, user-confirmed values used by the deterministic
 * procedure evaluator.  Missing keys and the literal UNKNOWN are distinct
 * from FALSE and are never guessed by this value object.
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
