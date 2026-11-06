package com.financialhelper.consultation;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Conservative routing candidates only; no candidate is a confirmed fact or evidence. */
final class InformationIntakeClassifier {
    private InformationIntakeClassifier() {}

    static List<ConsultationScenario> candidates(String input) {
        if (input == null || input.isBlank()) return List.of();
        String text = input.toLowerCase(Locale.ROOT);
        List<ConsultationScenario> results = new ArrayList<>();
        boolean cardLoss = has(text, "분실", "도난", "잃어버")
                && !has(text, "분실하지 않", "분실 안 했", "잃어버리지 않", "도난당하지 않");
        if (has(text, "카드") && (cardLoss || has(text, "부정사용", "모르는 결제", "하지 않은 결제")))
            results.add(ConsultationScenario.CARD_LOSS_UNAUTHORIZED_USE);
        if ((has(text, "보이스피싱") || (has(text, "전화") && has(text, "사기", "속", "지시", "시키")))
                && has(text, "송금", "이체", "보냈") && !has(text, "송금하지 않", "송금 안 했", "보내지 않"))
            results.add(ConsultationScenario.VOICE_PHISHING_SUSPICIOUS_TRANSFER);
        if (has(text, "계좌", "무단이체") && has(text, "이체", "송금", "출금", "빠져나")
                && has(text, "무단", "하지 않", "모르는", "모르는데", "본인 아닌"))
            results.add(ConsultationScenario.UNAUTHORIZED_ACCOUNT_TRANSFER);
        if ((has(text, "문자", "스미싱") && has(text, "링크", "앱", "악성"))
                || (has(text, "악성 앱", "악성앱") && has(text, "설치", "실행")))
            results.add(ConsultationScenario.PERSONAL_INFO_SMISHING_MALICIOUS_APP);
        return List.copyOf(results);
    }

    private static boolean has(String text, String... words) {
        for (String word : words) if (text.contains(word)) return true;
        return false;
    }
}
