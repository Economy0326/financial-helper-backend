package com.financialhelper.retrieval;

import java.util.List;
import java.util.regex.Pattern;

/** A deliberately narrow vocabulary for candidate retrieval, not a financial fact classifier. */
public final class DepositQueryPurpose {
    private static final Pattern HISTORICAL = Pattern.compile("(?:20\\d{2}년|과거|당시|작년|지난해|예전에)");
    private static final Pattern REVIEWED_EFFECTIVE_DATE = Pattern.compile("2025년\\s*9월(?:\\s*1일)?부터");

    private DepositQueryPurpose() {}

    public static boolean hasHistoricalMarker(String text) {
        if (text == null) return false;
        if (REVIEWED_EFFECTIVE_DATE.matcher(text).find()
                && !containsAny(text, "사고", "발생", "가입", "과거", "당시", "작년", "지난해", "예전에")) {
            return false;
        }
        return HISTORICAL.matcher(text).find();
    }

    public static String query(String text) {
        Purpose purpose = resolve(text);
        return purpose == null ? null : purpose.query();
    }

    public static List<Integer> questionNumbers(String text) {
        Purpose purpose = resolve(text);
        return purpose == null ? List.of() : purpose.questions();
    }

    private static Purpose resolve(String text) {
        if (text == null) return null;
        String normalized = text.replaceAll("\\s+", "");
        if (!normalized.contains("예금") && !normalized.contains("적금")
                && !normalized.contains("은행")
                && !normalized.contains("계좌") && !normalized.contains("한도")
                && !normalized.contains("보호")
                && !normalized.contains("퇴직연금") && !normalized.contains("ISA")
                && !normalized.contains("원금") && !normalized.contains("통장")) return null;
        // Individual coverage, liability and the unreviewed foreign-currency Q4 stay out of scope.
        if (containsAny(normalized, "외화예금", "제펀드", "제가가입한", "실제보호금액",
                "제보호사고", "배상", "환급", "보장해", "반드시보호", "보호대상인지확정")) return null;
        if (containsAny(normalized, "같은은행", "동일은행", "여러계좌", "통장이여러")
                || normalized.contains("같은금융기관") && containsAny(normalized, "계좌", "통장")
                || normalized.contains("한은행") && containsAny(normalized, "두개", "여러", "각각"))
            return new Purpose("동일 금융기관 계좌 합산", List.of(8));
        if (containsAny(normalized, "다른은행", "서로다른은행", "금융기관별", "은행두곳", "서로다른금융기관")
                || normalized.matches(".*은행.*은행.*") && normalized.contains("각각"))
            if (!containsAny(normalized, "이자", "약정이율"))
                return new Purpose("금융기관별 보호 한도", List.of(9));
        if (normalized.contains("별도보호한도")
                || containsAny(normalized, "퇴직연금", "연금저축", "사고보험금")
                && containsAny(normalized, "별도", "한도"))
            return new Purpose("별도 보호 한도 조건", List.of(10));
        if (containsAny(normalized, "ISA", "개인종합자산관리계좌", "DC형", "IRP", "보호상품"))
            return new Purpose("보호대상 상품 운용 조건", List.of(2));
        if (normalized.contains("펀드"))
            return new Purpose("보호 제외 금융상품", List.of(3));
        if (normalized.contains("보호되지않는") && normalized.contains("상품"))
            return new Purpose("보호 제외 금융상품", List.of(3));
        if (containsAny(normalized, "인상되기전", "이전에가입", "이전가입", "시행일", "언제부터시행")
                || normalized.contains("기존예금") && normalized.contains("새기준"))
            return new Purpose("예금보호한도 시행 시점", List.of(5));
        if (containsAny(normalized, "이자", "약정이율")) {
            if (containsAny(normalized, "한도", "얼마")
                    && !(normalized.contains("원금") && containsAny(normalized, "합쳐서", "합산"))) return null;
            return new Purpose("원금과 소정의 이자", List.of(6));
        }
        if (containsAny(normalized, "이전가입", "언제가", "시행일"))
            return new Purpose("예금보호한도 시행 시점", List.of(5));
        if (containsAny(normalized, "얼마", "한도", "1억원", "1억", "최대금액", "어디까지"))
            return new Purpose("예금 보호 한도", List.of(6));
        return null;
    }

    private static boolean containsAny(String text, String... terms) {
        for (String term : terms) if (text.contains(term)) return true;
        return false;
    }

    private record Purpose(String query, List<Integer> questions) {}
}
