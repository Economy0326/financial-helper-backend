package com.financialhelper.retrieval;

import com.financialhelper.consultation.FinancialTopic;

/**
 * Conservative intent gate for the first reviewed claim in each new topic.
 * Every external retrieval query is a fixed purpose phrase, never user text.
 */
public final class SixTopicQueryPurpose {
    private SixTopicQueryPurpose() {}

    public static Match resolve(FinancialTopic topic, String text) {
        if (topic == null || text == null) return null;
        String value = text.replaceAll("\\s+", "").toLowerCase(java.util.Locale.ROOT);
        if (contains(value, "외화예금", "개인별", "반드시", "무조건", "보장", "배상", "환급",
                "제상품", "내상품", "수수료율이몇", "수수료가얼마", "보험금얼마",
                "손실보상", "지급액", "실제보호금액")) return null;
        return switch (topic) {
            case CARD_PAYMENTS -> contains(value, "카드") && contains(value, "분실", "도난")
                    && contains(value, "신고", "어떻게", "해야", "방법")
                    ? new Match("fsc-card-use-qa", "카드 분실 도난 신고 경로") : null;
            case FRAUD_SECURITY -> contains(value, "보이스피싱", "피싱")
                    && contains(value, "피해", "돈을보냈", "송금했", "이체했")
                    && contains(value, "신고", "112", "어디", "어떻게")
                    ? new Match("police-campaign", "보이스피싱 피해 발생 112 신고") : null;
            case LOANS_CREDIT -> contains(value, "중도상환수수료", "중도상환수수료율")
                    && contains(value, "2025년1월13일", "25년1월13일")
                    && contains(value, "계약", "갱신")
                    ? new Match("fsc-early-repayment-qa", "2025년 1월 13일 이후 대출 계약 적용 조건") : null;
            case INSURANCE -> contains(value, "실손24") && contains(value, "청구", "서류")
                    ? new Match("fsc-silsong24-guide", "실손24 청구서류 전송 절차") : null;
            case INVESTMENTS_SECURITIES -> contains(value, "투자상품", "투자성상품")
                    && contains(value, "설명", "위험", "수수료")
                    ? new Match("law-financial-consumer-protection-19", "투자성 상품 설명의무 위험 수수료") : null;
            case CONSUMER_RIGHTS_DISPUTES -> contains(value, "금융민원", "분쟁조정", "금융회사민원")
                    && contains(value, "신청", "어디", "절차", "방법")
                    ? new Match("fsc-consumer-complaints", "금융민원 분쟁조정 신청 경로") : null;
            default -> null;
        };
    }

    private static boolean contains(String value, String... markers) {
        for (String marker : markers) if (value.contains(marker)) return true;
        return false;
    }

    public record Match(String sourceKey, String query) {}
}
