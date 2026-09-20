package com.financialhelper.procedure;

import com.financialhelper.retrieval.ConfirmedCaseSnapshotData;

import java.time.LocalDate;
import java.time.DateTimeException;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 명시적인 사용자 표현이나 typed follow-up 값만 작은 CARD fact 어휘로 변환한다.
 * retrieval 문장, AI 설명, 암시된 상황에서는 fact를 추론하지 않는다.
 */
public final class CardCaseFactExtractor {
    public static final Set<String> ALLOWED_FACT_KEYS = Set.of(
            "institution", "productType", "cardLost", "unauthorizedPayment",
            "transactionType", "domestic", "reported", "incidentDate", "transactionDate",
            "compensationStatus", "resultDisputed");

    private static final Pattern DATE = Pattern.compile(
            "(20\\d{2})[.\\-/년](\\d{1,2})[.\\-/월](\\d{1,2})일?");

    private CardCaseFactExtractor() {
    }

    public static CardCaseFacts fromSnapshot(ConfirmedCaseSnapshotData snapshot) {
        Map<String, String> facts = new LinkedHashMap<>();
        if (snapshot == null) {
            return new CardCaseFacts(facts);
        }
        for (ConfirmedCaseSnapshotData.Fact fact : snapshot.facts()) {
            if (fact == null || fact.value() == null) {
                continue;
            }
            if (ALLOWED_FACT_KEYS.contains(fact.key())) {
                facts.put(fact.key(), normalizeValue(fact.key(), fact.value()));
                if ("SHORT_TEXT".equalsIgnoreCase(fact.type())) {
                    extractExplicitText(fact.value(), facts);
                }
            } else if ("category".equals(fact.key()) && "CARD".equalsIgnoreCase(fact.value())) {
                facts.put("category", "CARD");
            } else if ("situationText".equals(fact.key())) {
                extractExplicitText(fact.value(), facts);
            }
        }
        return new CardCaseFacts(facts);
    }

    public static CardCaseFacts fromValues(Map<String, String> explicitValues) {
        Map<String, String> normalized = new LinkedHashMap<>();
        if (explicitValues != null) {
            explicitValues.forEach((key, value) -> {
                if (ALLOWED_FACT_KEYS.contains(key) && value != null) {
                    normalized.put(key, normalizeValue(key, value));
                }
            });
        }
        return new CardCaseFacts(normalized);
    }

    public static void extractExplicitText(String text, Map<String, String> target) {
        if (text == null || text.isBlank() || target == null) {
            return;
        }
        String value = text.trim();
        if (containsAny(value, "KB국민카드", "국민카드")) {
            target.put("institution", ProcedureVersionService.KB_INSTITUTION);
        }
        if ((value.contains("개인") || value.contains("본인 명의") || value.contains("본인명의"))
                && value.contains("신용카드")) {
            target.put("productType", ProcedureVersionService.PERSONAL_CREDIT_CARD);
        }
        if (containsAny(value, "잃어버렸", "잃어 버렸", "분실했", "분실", "도난당", "도난")) {
            target.put("cardLost", "TRUE");
        }
        if (containsAny(value, "모르는 결제", "모르는 국내", "모르는 해외", "부정사용", "내가 하지 않은 결제", "본인이 하지 않은 결제")
                || value.contains("모르는") && value.contains("결제")) {
            target.put("unauthorizedPayment", "TRUE");
        }
        if (value.contains("신용판매")) {
            target.put("transactionType", "CREDIT_SALE");
        }
        if (value.contains("국내")) {
            target.put("domestic", "TRUE");
        } else if (value.contains("해외")) {
            target.put("domestic", "FALSE");
        }
        if (containsAny(value, "아직 신고 안", "신고 안 했", "신고하지 않았", "신고 못")) {
            target.put("reported", "FALSE");
        } else if (containsAny(value, "신고했", "신고 완료", "신고를 했", "접수했")) {
            target.put("reported", "TRUE");
        }
        Matcher matcher = DATE.matcher(value);
        if (matcher.find()) {
            try {
                LocalDate date = LocalDate.of(
                        Integer.parseInt(matcher.group(1)),
                        Integer.parseInt(matcher.group(2)),
                        Integer.parseInt(matcher.group(3)));
                target.put("incidentDate", date.toString());
            } catch (DateTimeException ignored) {
                // 유효하지 않은 날짜는 값 없이 남으므로 UNKNOWN으로 처리된다.
            }
        }
    }

    /** 명시적인 미지원 product 표현을 신용카드 범위로 조용히 강제 변환해서는 안 된다. */
    public static boolean hasExplicitUnsupportedProduct(String text) {
        if (text == null) return false;
        return containsAny(text, "체크카드", "선불카드", "법인카드", "가족카드");
    }

    private static String normalizeValue(String key, String value) {
        String trimmed = value.trim();
        if ("institution".equals(key)) {
            return ProcedureVersionService.canonicalInstitution(trimmed);
        }
        if ("productType".equals(key)) {
            return ProcedureVersionService.canonicalProduct(trimmed);
        }
        if (Set.of("cardLost", "unauthorizedPayment", "domestic", "reported", "resultDisputed")
                .contains(key)) {
            String upper = trimmed.toUpperCase(Locale.ROOT);
            if (Set.of("TRUE", "YES", "Y", "네").contains(upper)) {
                return "TRUE";
            }
            if (Set.of("FALSE", "NO", "N", "아니요").contains(upper)) {
                return "FALSE";
            }
            return "UNKNOWN";
        }
        if ("compensationStatus".equals(key)) {
            String normalized = trimmed.toUpperCase(Locale.ROOT).replace(" ", "_");
            return Set.of("NOT_SUBMITTED", "SUBMITTED", "INVESTIGATING", "RESULT_RECEIVED", "UNKNOWN")
                    .contains(normalized) ? normalized : "UNKNOWN";
        }
        if ("incidentDate".equals(key) || "transactionDate".equals(key)) {
            try {
                return LocalDate.parse(trimmed).toString();
            } catch (DateTimeParseException ignored) {
                return trimmed;
            }
        }
        if ("transactionType".equals(key)) {
            String normalized = trimmed.toUpperCase(Locale.ROOT)
                    .replace(" ", "_");
            return Set.of("CREDIT_SALE", "CASH_ADVANCE", "CARD_LOAN", "TRANSFER", "UNKNOWN")
                    .contains(normalized) ? normalized : "UNKNOWN";
        }
        return "UNKNOWN".equalsIgnoreCase(trimmed) ? "UNKNOWN" : trimmed;
    }

    private static boolean containsAny(String text, String... values) {
        for (String value : values) {
            if (text.contains(value)) {
                return true;
            }
        }
        return false;
    }

}
