package com.financialhelper.consultation;

import java.util.Locale;

/** Resolves only explicit scenario signals; it never infers a financial event from evidence. */
public final class ConsultationScenarioResolver {
    private ConsultationScenarioResolver() {}

    public static ConsultationScenario resolve(Consultation consultation) {
        if (consultation == null) return ConsultationScenario.UNKNOWN;
        if (consultation.getScenario() != null) return consultation.getScenario();
        return resolve(consultation.getCategory(), consultation.getSituationText());
    }

    public static ConsultationScenario resolve(ConsultationCategory category, String situation) {
        if (situation == null) {
            return category == ConsultationCategory.CARD
                    ? ConsultationScenario.CARD_LOSS_UNAUTHORIZED_USE : ConsultationScenario.UNKNOWN;
        }
        String value = situation.toLowerCase(Locale.ROOT);
        if (containsAny(value, "내가 하지 않은 계좌", "제가 하지 않은 계좌", "본인이 하지 않은 계좌",
                "내가 하지 않은", "제가 하지 않은", "본인이 하지 않은",
                "무단이체", "무단 출금", "모르는 계좌", "모르는 송금", "모르는 출금",
                "하지 않은 출금", "제가 하지 않은 출금", "본인이 하지 않은 출금")) {
            return ConsultationScenario.UNAUTHORIZED_ACCOUNT_TRANSFER;
        }
        if (containsAny(value, "보이스피싱", "사기 의심 송금", "의심 송금", "상대방 지시", "송금했", "송금하려")) {
            return ConsultationScenario.VOICE_PHISHING_SUSPICIOUS_TRANSFER;
        }
        if (containsAny(value, "스미싱", "악성 앱", "악성앱", "원격제어", "개인정보", "인증정보", "링크를 눌")) {
            return ConsultationScenario.PERSONAL_INFO_SMISHING_MALICIOUS_APP;
        }
        return category == ConsultationCategory.CARD
                ? ConsultationScenario.CARD_LOSS_UNAUTHORIZED_USE : ConsultationScenario.UNKNOWN;
    }

    public static boolean compatible(ConsultationCategory category, ConsultationScenario scenario) {
        if (scenario == null || scenario == ConsultationScenario.UNKNOWN) return true;
        if (scenario == ConsultationScenario.CARD_LOSS_UNAUTHORIZED_USE) {
            return category == ConsultationCategory.CARD;
        }
        return category == ConsultationCategory.FINANCIAL_FRAUD;
    }

    private static boolean containsAny(String value, String... terms) {
        for (String term : terms) if (value.contains(term)) return true;
        return false;
    }
}
