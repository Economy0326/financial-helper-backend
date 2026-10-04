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
        boolean transfer = containsAny(value, "계좌이체", "계좌 이체", "계좌 출금", "송금", "출금");
        boolean coercion = containsAny(value, "보이스피싱", "전화", "상대방 지시", "시키는 대로",
                "지시에 따라", "속아서", "사기 의심 송금", "의심 송금");
        boolean unauthorized = containsAny(value,
                "내가 하지 않은 계좌", "제가 하지 않은 계좌", "본인이 하지 않은 계좌",
                "내가 하지 않은 계좌이체", "제가 하지 않은 계좌이체", "본인이 하지 않은 계좌이체",
                "무단이체", "무단 출금", "모르는 계좌", "모르는 송금", "모르는 출금",
                "하지 않은 출금", "제가 하지 않은 출금", "본인이 하지 않은 출금");
        // "국내"처럼 중간에 수식어가 있어도 사용자가 거래하지 않았다는 직접 진술을 보존한다.
        boolean explicitUserDidNotTransfer = (transfer && containsAny(value,
                "내가 하지 않은", "제가 하지 않은", "본인이 하지 않은"))
                || containsAny(value, "무단이체", "무단 출금", "모르는 출금");
        // 두 사건 유형의 명시 신호가 함께 있으면 어느 Procedure도 추측으로 선택하지 않는다.
        if (transfer && coercion && explicitUserDidNotTransfer) {
            return ConsultationScenario.UNKNOWN;
        }
        // 전화/상대방 지시/기망과 실제 송금이라는 직접 진술은 단순 거래 단어보다 구체적이다.
        if (transfer && coercion) {
            return ConsultationScenario.VOICE_PHISHING_SUSPICIOUS_TRANSFER;
        }
        if (unauthorized || explicitUserDidNotTransfer) {
            return ConsultationScenario.UNAUTHORIZED_ACCOUNT_TRANSFER;
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
