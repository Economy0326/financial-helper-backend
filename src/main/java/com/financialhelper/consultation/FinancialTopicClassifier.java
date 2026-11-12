package com.financialhelper.consultation;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Deliberately narrow local routing signals. A match never establishes source coverage. */
final class FinancialTopicClassifier {
    private FinancialTopicClassifier() {}

    static Result classify(String input) {
        if (input == null || input.isBlank()) return new Result(List.of(), "INPUT_NOT_UNDERSTOOD", false);
        String text = input.toLowerCase(Locale.ROOT);
        List<FinancialTopic> topics = new ArrayList<>();
        boolean card = has(text, "카드") && has(text, "분실", "도난", "잃어버", "부정사용", "결제", "승인", "취소", "한도");
        if (card) topics.add(FinancialTopic.CARD_PAYMENTS);
        if (has(text, "예금자보호", "착오송금", "무단이체")
                || (has(text, "예금", "계좌", "적금", "송금") && has(text, "보호", "이체", "잔액", "금리", "반환", "출금"))
                || (has(text, "통장") && has(text, "은행", "금융기관") && has(text, "여러", "보호", "한도"))
                || (has(text, "예금") && has(text, "서로 다른 금융기관", "다른 금융기관"))
                || (has(text, "isa", "퇴직연금", "연금저축") && has(text, "보호", "한도"))
                || (has(text, "원금") && has(text, "소정의 이자")))
            topics.add(FinancialTopic.DEPOSITS_ACCOUNTS);
        if (has(text, "신용점수", "중도상환수수료")
                || (has(text, "대출", "신용") && has(text, "상환", "금리", "수수료", "점수", "한도", "심사")))
            topics.add(FinancialTopic.LOANS_CREDIT);
        if (has(text, "보험금 청구", "보험금청구")
                || (has(text, "보험") && has(text, "청구", "보장", "보험료", "해지", "계약")))
            topics.add(FinancialTopic.INSURANCE);
        if (has(text, "투자상품", "증권계좌")
                || (has(text, "투자", "주식", "증권", "펀드") && has(text, "위험", "손실", "수수료", "매매", "상품")))
            topics.add(FinancialTopic.INVESTMENTS_SECURITIES);
        if ((has(text, "보이스피싱") && has(text, "송금", "전화", "신고", "피해", "입금"))
                || (has(text, "스미싱") && has(text, "문자", "링크", "앱", "피해"))
                || (has(text, "악성앱") && has(text, "설치", "실행", "피해"))
                || (has(text, "금융사기") && has(text, "신고", "피해", "송금"))
                || (has(text, "사기", "개인정보") && has(text, "송금", "문자", "링크", "피해", "노출")))
            topics.add(FinancialTopic.FRAUD_SECURITY);
        if (has(text, "분쟁조정", "금융민원", "금융소비자")
                || (has(text, "금융", "은행", "카드사", "보험사") && has(text, "민원", "분쟁", "권리")))
            topics.add(FinancialTopic.CONSUMER_RIGHTS_DISPUTES);

        boolean emergency = !InformationIntakeClassifier.candidates(input).isEmpty()
                && has(text, "지금", "당장", "방금")
                && has(text, "분실", "도난", "송금", "이체", "악성", "피해");
        String unresolved = "NEEDS_CLARIFICATION";
        if (topics.isEmpty()) {
            if (has(text, "점심 메뉴", "오늘 날씨", "요리법", "영화 추천") && !has(text, "금융", "카드", "계좌", "예금", "대출", "보험", "투자"))
                unresolved = "OUT_OF_SCOPE_NON_FINANCIAL";
            else if (has(text, "코인", "가상자산", "연금", "세금", "금융상품", "부동산 대출"))
                unresolved = "UNSUPPORTED_FINANCIAL_TOPIC";
            else if (!has(text, "카드", "계좌", "예금", "대출", "보험", "투자", "송금", "금융", "결제", "피싱", "스미싱", "문자", "앱"))
                unresolved = "INPUT_NOT_UNDERSTOOD";
        }
        return new Result(List.copyOf(topics), unresolved, emergency);
    }

    private static boolean has(String text, String... terms) {
        for (String term : terms) if (text.contains(term)) return true;
        return false;
    }

    record Result(List<FinancialTopic> topics, String unresolvedStatus, boolean emergencyRecommended) {}
}
