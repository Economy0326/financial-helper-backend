package com.financialhelper.consultation;

import java.util.Locale;

/** 명시적 scenario 신호만 판정하며 Evidence에서 금융 사건을 추론하지 않는다. */
public final class ConsultationScenarioResolver {
    private ConsultationScenarioResolver() {}

    public static ConsultationScenario resolve(Consultation consultation) {
        if (consultation == null) return ConsultationScenario.UNKNOWN;
        // Situation은 현재 사용자의 명시적 입력이다. category 선택으로 cache된
        // scenario 때문에 계좌이체 신고가 CARD 경로에 남아서는 안 된다.
        if (consultation.getSituationText() != null && !consultation.getSituationText().isBlank()) {
            return resolve(consultation.getCategory(), consultation.getSituationText());
        }
        return consultation.getScenario() == null
                ? resolve(consultation.getCategory(), null)
                : consultation.getScenario();
    }

    public static ConsultationScenario resolve(ConsultationCategory category, String situation) {
        if (situation == null) {
            return category == ConsultationCategory.CARD
                    ? ConsultationScenario.CARD_LOSS_UNAUTHORIZED_USE : ConsultationScenario.UNKNOWN;
        }
        String value = situation.toLowerCase(Locale.ROOT);
        boolean explicitAccountTransfer = containsAny(value, "계좌이체", "계좌 이체", "계좌 출금", "무단이체", "무단 출금", "모르는 출금");
        if (explicitAccountTransfer || containsAny(value,
                "내가 하지 않은 계좌", "제가 하지 않은 계좌", "본인이 하지 않은 계좌",
                "내가 하지 않은 계좌이체", "제가 하지 않은 계좌이체", "본인이 하지 않은 계좌이체",
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
