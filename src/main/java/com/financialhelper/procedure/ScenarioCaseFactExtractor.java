package com.financialhelper.procedure;

import com.financialhelper.retrieval.ConfirmedCaseSnapshotData;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** CARD 이외의 Procedure 기반 scenario가 공유하는 명시적 fact 어휘다. */
public final class ScenarioCaseFactExtractor {
    public static final Set<String> ALLOWED_FACT_KEYS = Set.of(
            "institution", "productType", "incidentDate", "transactionDate", "reported",
            "policeReported", "financialLossOccurred", "transactionChannel",
            "authenticationInfoExposed", "transferCompleted", "userInitiatedTransfer",
            "suspiciousTransfer", "reportedToFinancialInstitution", "maliciousAppInstalled",
            "unauthorizedTransaction", "transactionType", "accessCredentialExposed",
            "suspiciousLinkClicked", "remoteControlUsed", "personalInfoExposed", "moneyMoved",
            "scenario");

    private ScenarioCaseFactExtractor() {}

    public static CardCaseFacts fromSnapshot(ConfirmedCaseSnapshotData snapshot) {
        Map<String, String> result = new LinkedHashMap<>();
        if (snapshot == null) return new CardCaseFacts(result);
        for (ConfirmedCaseSnapshotData.Fact fact : snapshot.facts()) {
            if (fact == null || fact.value() == null || fact.key() == null) continue;
            String key = canonicalKey(fact.key());
            if (ALLOWED_FACT_KEYS.contains(key)) {
                result.put(key, normalize(key, fact.value()));
            }
            if ("SITUATION".equalsIgnoreCase(fact.type()) || "situationText".equals(fact.key())) {
                extractExplicitText(fact.value(), result);
            }
        }
        addAliasIfMissing(result, "reported", "reportedToFinancialInstitution");
        addAliasIfMissing(result, "reportedToFinancialInstitution", "reported");
        return new CardCaseFacts(result);
    }

    public static CardCaseFacts fromValues(Map<String, String> values) {
        Map<String, String> result = new LinkedHashMap<>();
        if (values != null) values.forEach((rawKey, value) -> {
            String key = canonicalKey(rawKey);
            if (ALLOWED_FACT_KEYS.contains(key) && value != null) result.put(key, normalize(key, value));
        });
        addAliasIfMissing(result, "reported", "reportedToFinancialInstitution");
        addAliasIfMissing(result, "reportedToFinancialInstitution", "reported");
        return new CardCaseFacts(result);
    }

    /** 같은 의미의 structured answer 이름을 하나의 canonical 어휘로 유지한다. */
    public static String canonicalKey(String rawKey) {
        if (rawKey == null) return null;
        return switch (rawKey.trim()) {
            case "transferMade" -> "transferCompleted";
            case "financialInstitutionReported" -> "reportedToFinancialInstitution";
            default -> rawKey.trim();
        };
    }

    private static void addAliasIfMissing(Map<String, String> values,
                                          String canonical,
                                          String alias) {
        if (!values.containsKey(canonical) && values.containsKey(alias)) {
            values.put(canonical, values.get(alias));
        }
    }

    public static void extractExplicitText(String text, Map<String, String> target) {
        if (text == null || text.isBlank() || target == null) return;
        String v = text.trim();
        if (containsAny(v, "송금했", "이체했", "보냈")) target.put("transferCompleted", "TRUE");
        if (containsAny(v, "송금하지 않았", "이체하지 않았", "보내지 않았",
                "송금하려", "이체하려", "보내려")) target.put("transferCompleted", "FALSE");
        if (containsAny(v, "내가 직접", "본인이 직접", "직접 송금")) target.put("userInitiatedTransfer", "TRUE");
        if (containsAny(v, "내가 보낸 게 아니", "제가 보낸 게 아니", "직접 보내지 않았")) {
            target.put("userInitiatedTransfer", "FALSE");
        }
        if (containsAny(v, "상대방 지시", "속아서", "사기", "보이스피싱")) target.put("suspiciousTransfer", "TRUE");
        if (containsAny(v, "내가 하지 않은", "제가 하지 않은", "본인이 하지 않은", "무단")) {
            target.put("unauthorizedTransaction", "TRUE");
        }
        if (containsAny(v, "계좌이체", "계좌 이체")) {
            target.put("transactionType", "ACCOUNT_TRANSFER");
        } else if (containsAny(v, "계좌 출금", "무단 출금", "모르는 출금")) {
            target.put("transactionType", "ACCOUNT_WITHDRAWAL");
        }
        if (containsAny(v, "신고 안", "신고하지 않았", "접수하지 않았", "아직 신고")) {
            target.put("reported", "FALSE");
            target.put("reportedToFinancialInstitution", "FALSE");
        } else if (containsAny(v, "신고했", "신고 완료", "접수했")) {
            target.put("reported", "TRUE");
            target.put("reportedToFinancialInstitution", "TRUE");
        }
        if (containsAny(v, "경찰에 신고", "112에 신고")) target.put("policeReported", "TRUE");
        if (containsAny(v, "링크를 눌", "링크 클릭", "스미싱")) target.put("suspiciousLinkClicked", "TRUE");
        if (containsAny(v, "앱을 설치", "악성 앱", "악성앱")) target.put("maliciousAppInstalled", "TRUE");
        if (containsAny(v, "원격제어", "원격 조종")) target.put("remoteControlUsed", "TRUE");
        if (containsAny(v, "개인정보", "주민번호", "개인 정보")) target.put("personalInfoExposed", "TRUE");
        if (containsAny(v, "인증번호", "비밀번호", "인증 정보")) target.put("authenticationInfoExposed", "TRUE");
        if (containsAny(v, "돈이 빠져", "돈이 나갔", "금전 피해")) target.put("moneyMoved", "TRUE");
    }

    private static String normalize(String key, String value) {
        String v = value.trim();
        if (Set.of("reported", "policeReported", "financialLossOccurred", "authenticationInfoExposed",
                "transferCompleted", "userInitiatedTransfer", "suspiciousTransfer",
                "reportedToFinancialInstitution", "maliciousAppInstalled", "unauthorizedTransaction",
                "accessCredentialExposed", "suspiciousLinkClicked", "remoteControlUsed",
                "personalInfoExposed", "moneyMoved").contains(key)) {
            String u = v.toUpperCase(Locale.ROOT);
            if (Set.of("TRUE", "YES", "Y", "네").contains(u)) return "TRUE";
            if (Set.of("FALSE", "NO", "N", "아니요").contains(u)) return "FALSE";
            return "UNKNOWN";
        }
        if (Set.of("incidentDate", "transactionDate").contains(key)) {
            try { return LocalDate.parse(v).toString(); } catch (DateTimeParseException ignored) { return v; }
        }
        return "UNKNOWN".equalsIgnoreCase(v) ? "UNKNOWN" : v;
    }

    private static boolean containsAny(String value, String... terms) {
        for (String term : terms) if (value.contains(term)) return true;
        return false;
    }
}
