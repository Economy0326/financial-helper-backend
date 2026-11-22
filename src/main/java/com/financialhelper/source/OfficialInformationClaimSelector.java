package com.financialhelper.source;

import com.financialhelper.consultation.FinancialTopic;

import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Narrow, source-specific review candidates. These excerpts are never approvals.
 * A changed or ambiguous source fails closed instead of silently selecting a new passage.
 */
public final class OfficialInformationClaimSelector {
    private static final Map<String, List<Boundary>> BOUNDARIES = Map.of(
            "fsc-card-use-qa", List.of(new Boundary("카드 분실 신고",
                    "카드를 분실하거나",
                    "신고해야 합니다.",
                    "Q1) 국내에서 카드를 도난, 분실시 조치방법은 무엇인지?", "Q2) 리볼빙")),
            "police-campaign", List.of(new Boundary("피해 발생 시 112 신고",
                    "보이스피싱 피해 입었다면 즉시 112로 전화하여",
                    "피해 사실을 알려주세요!",
                    "STEP 01.", "STEP 02.")),
            "fsc-early-repayment-qa", List.of(new Boundary("기존 계약과 적용 시점",
                    "1. 기존계약분도 적용대상인지?",
                    "적용되지 않습니다.",
                    null, "2. 새마을금고")),
            "fsc-silsong24-guide", List.of(new Boundary("실손24 청구서류 전송",
                    "소비자가 앱으로 요청하면 병원이 실손보험금 청구서류를 보험회사로 자동으로 보내주는",
                    "실손보험 청구전산화 시행(‘24.10.25일)",
                    null, null),
                    new Boundary("실손24 연계 의료기관 조건",
                    "연계 완료 : 실손24 연계가 완료되어 청구전산화 서비스를 이용할 수 있는 요양기관",
                    "청구전산화 서비스를 이용할 수 있는 요양기관",
                    null, null)),
            "law-financial-consumer-protection-19", List.of(new Boundary("설명의무 제19조",
                    "제19조(설명의무) ①",
                    "수수료 등 투자성 상품에 관한 중요한 사항으로서 대통령령으로 정하는 사항",
                    null, null)),
            "fsc-consumer-complaints", List.of(new Boundary("금융민원과 분쟁조정 신청",
                    "금융소비자와 금융회사간의 분쟁조정 또는 금융위원회·금융감독원에 대한 민원을 신청할 수 있습니다.",
                    "금융민원 신청하기",
                    null, null)));

    private static final Map<String, String> OFFICIAL_URLS = Map.of(
            "fsc-card-use-qa", "https://www.fsc.go.kr/po020201/27315",
            "police-campaign", "https://www.counterscam112.go.kr/campaign/index.html",
            "fsc-early-repayment-qa", "https://www.fsc.go.kr/po020201/83840",
            "fsc-silsong24-guide", "https://www.fsc.go.kr/po010103/87818",
            "law-financial-consumer-protection-19",
                    "https://www.law.go.kr/LSW/lsLinkCommonInfo.do?chrClsCd=010202&lsJoLnkSeq=1031274847",
            "fsc-consumer-complaints", "https://www.fsc.go.kr/pa010000");

    private OfficialInformationClaimSelector() {}

    /** Fail closed before a changed or unavailable official page can supersede a stored version. */
    public static void validateFetchedCandidate(SourceIngestionData.Snapshot source,
                                                SourceIngestionData.Parsed parsed) {
        List<Boundary> boundaries = BOUNDARIES.get(source.sourceKey());
        if (boundaries == null) return;
        String host = java.net.URI.create(parsed.resolvedUrl()).getHost();
        String domain = source.officialDomain();
        if (!officialUrl(source.sourceKey()).equals(source.canonicalUrl()) || host == null
                || !(host.equalsIgnoreCase(domain) || host.toLowerCase(java.util.Locale.ROOT)
                        .endsWith("." + domain.toLowerCase(java.util.Locale.ROOT)))) {
            throw new SourceIngestionException("SOURCE_IDENTITY_MISMATCH", "Official source identity changed");
        }
        if ("law.go.kr".equalsIgnoreCase(domain)
                && "/sorry/sorry.html".equals(java.net.URI.create(parsed.resolvedUrl()).getPath())) {
            throw new SourceIngestionException("SOURCE_TEMPORARILY_UNAVAILABLE",
                    "Official law source returned a maintenance page");
        }
        String content = parsed.normalizedContent();
        try {
            for (Boundary boundary : boundaries) {
                int lower = boundary.sectionStart() == null ? 0 : uniqueSpan(content, boundary.sectionStart()).start();
                int upper = boundary.sectionEnd() == null ? content.length()
                        : uniqueSpan(content.substring(lower + 1), boundary.sectionEnd()).start() + lower + 1;
                if (upper <= lower) throw new IllegalStateException("Invalid section");
                String section = content.substring(lower, upper);
                int start = uniqueSpan(section, boundary.start()).start();
                int end = uniqueSpan(section, boundary.end()).end();
                if (end <= start) throw new IllegalStateException("Invalid claim");
            }
            if ("law-financial-consumer-protection-19".equals(source.sourceKey())) {
                if (!content.contains("금융소비자 보호에 관한 법률")
                        || !content.contains("제19조(설명의무)")) {
                    throw new IllegalStateException("Law title or article changed");
                }
                java.util.regex.Matcher versions = Pattern.compile(
                        "\\[시행\\s+([^]]+)]\\s*\\[법률\\s+제(\\d+)호,\\s*([^]]+)]")
                        .matcher(content);
                String observed = null;
                while (versions.find()) {
                    String version = versions.group();
                    if (observed != null && !observed.equals(version)) {
                        throw new IllegalStateException("Conflicting law versions");
                    }
                    observed = version;
                }
                if (observed == null) throw new IllegalStateException("Law version missing");
            }
        } catch (IllegalStateException changed) {
            throw new SourceIngestionException("SOURCE_OFFICIAL_CONTENT_UNAVAILABLE",
                    "Official claim structure or law version could not be verified", changed);
        }
    }

    public static boolean supports(String sourceKey) {
        return BOUNDARIES.containsKey(sourceKey);
    }

    public static List<String> sourceKeys() {
        return BOUNDARIES.keySet().stream().sorted().toList();
    }

    public static String officialUrl(String sourceKey) {
        String value = OFFICIAL_URLS.get(sourceKey);
        if (value == null) throw new IllegalArgumentException("Unsupported information source");
        return value;
    }

    public static String label(String sourceKey) {
        List<Boundary> values = BOUNDARIES.get(sourceKey);
        if (values == null || values.isEmpty()) {
            throw new IllegalArgumentException("Unsupported information source");
        }
        return values.getFirst().label();
    }

    public static String role(String sourceKey, int sequence) {
        List<Boundary> values = BOUNDARIES.get(sourceKey);
        if (values == null || sequence < 0 || sequence >= values.size()) {
            throw new IllegalArgumentException("Unsupported information claim");
        }
        return "fsc-silsong24-guide".equals(sourceKey) && sequence == 1 ? "CONDITION" : "SUPPORT";
    }

    public static FinancialTopic topic(String sourceKey) {
        return switch (sourceKey) {
            case "fsc-card-use-qa" -> FinancialTopic.CARD_PAYMENTS;
            case "police-campaign" -> FinancialTopic.FRAUD_SECURITY;
            case "fsc-early-repayment-qa" -> FinancialTopic.LOANS_CREDIT;
            case "fsc-silsong24-guide" -> FinancialTopic.INSURANCE;
            case "law-financial-consumer-protection-19" -> FinancialTopic.INVESTMENTS_SECURITIES;
            case "fsc-consumer-complaints" -> FinancialTopic.CONSUMER_RIGHTS_DISPUTES;
            default -> throw new IllegalArgumentException("Unsupported information source");
        };
    }

    /** Reviewer prompts are scope checks, not additional evidence or approval decisions. */
    public static ReviewGuide guide(String sourceKey) {
        return switch (sourceKey) {
            case "fsc-card-use-qa" -> new ReviewGuide("Q1 분실·도난 신고 문장",
                    "카드사에 신고하는 일반 안내", "분실·도난 상황과 신고 접수 확인",
                    "60일 자동 보상·카드사별 약관·개인별 책임 판단 제외",
                    "2012년 게시 문구를 2026년 모든 카드사 절차로 사용할 현행 근거가 부족함",
                    "게시일 2012-07-02은 주장 적용 시작일이 아님");
            case "police-campaign" -> new ReviewGuide("STEP 01 통합대응단",
                    "금전 피해 발생 시 112 신고 안내", "실제 피해 발생 시에만 적용",
                    "단순 의심, 지급정지·환급 성공 보장 제외",
                    "게시일과 주장 적용 시작일을 공식 자료에서 확인하지 못함",
                    "시작일을 임의로 채우지 말 것");
            case "fsc-early-repayment-qa" -> new ReviewGuide("문답 1 기존 계약분",
                    "2025-01-13 신규 계약 적용 기준", "계약 체결·갱신 시점과 대상 상품 확인",
                    "기존 계약에 소급 적용하거나 개인별 수수료율·금액 확정 금지",
                    "2026년 현재의 상품·예외 범위를 이 문답만으로 확정하지 못함",
                    "2025-01-13은 문답의 제도 적용 기준일이며 승인 결정일이 아님");
            case "fsc-silsong24-guide" -> new ReviewGuide("본문 실손24 서비스 개요·연계 완료 각주",
                    "소비자 요청에 따른 실손24 청구서류 전송 구조",
                    "연계 완료 의료기관에서 이용 가능하며 실제 참여 여부는 별도 확인",
                    "모든 의료기관·보험사 지원 또는 보험금 지급 보장 금지",
                    "참여 기관은 변동되므로 기사 속 2026-09-28 수치를 현재 목록으로 사용 금지",
                    "게시일 2026-09-30과 제도 단계별 시행일을 구분할 것");
            case "law-financial-consumer-protection-19" -> new ReviewGuide("금융소비자보호법 제19조 제1항",
                    "투자성 상품의 위험·수수료 등 중요 사항 설명의무",
                    "일반금융소비자에게 권유하거나 설명을 요청받는 경우 등 조문 조건",
                    "개별 상품 위험등급·수수료율·적합성 판단 제외",
                    "확인된 조문은 2026-01-02 시행 판본이며 후속 개정 연혁 확인 필요",
                    "2026-01-02은 확인된 법령 판본 시행일; 현재성은 별도 확인");
            case "fsc-consumer-complaints" -> new ReviewGuide("민원신청 본문 첫 안내",
                    "금융위원회 안내에서 민원·분쟁조정 신청 경로 확인",
                    "민원과 분쟁조정의 대상·소관 기관 구분",
                    "처리 기간·조정 성공·배상 보장 제외",
                    "게시일과 경로 변경 이력을 확인하지 못해 현행 접수 경로 판단 필요",
                    "페이지 게시일은 미확인; 주장 적용 시작일을 임의로 입력하지 말 것");
            default -> throw new IllegalArgumentException("Unsupported information source");
        };
    }

    public record ReviewGuide(String location, String support, String condition,
                              String exception, String hold, String dateNote) {}

    public static List<SourceChunkData.Definition> select(SourceDocument document,
            String configVersion, String configJson) {
        if (document == null || document.getSourceRegistry() == null) {
            throw new IllegalArgumentException("Persisted source document is required");
        }
        String key = document.getSourceRegistry().getSourceKey();
        List<Boundary> boundaries = BOUNDARIES.get(key);
        if (boundaries == null || document.getStatus() != SourceDocumentStatus.ACTIVE
                || !officialUrl(key).equals(document.getCanonicalUrl())
                || !officialUrl(key).equals(document.getSourceRegistry().getCanonicalUrl())) {
            throw new IllegalArgumentException("Unsupported or inactive information source");
        }
        String content = document.getNormalizedContent();
        return java.util.stream.IntStream.range(0, boundaries.size()).mapToObj(sequence -> {
            Boundary boundary = boundaries.get(sequence);
            int lower = boundary.sectionStart() == null ? 0 : uniqueSpan(content, boundary.sectionStart()).start();
            int upper = boundary.sectionEnd() == null ? content.length()
                    : uniqueSpan(content.substring(lower + 1), boundary.sectionEnd()).start() + lower + 1;
            if (upper <= lower) {
                throw new IllegalStateException("Official claim section changed: " + key);
            }
            String section = content.substring(lower, upper);
            int start;
            int end;
            try {
                start = lower + uniqueSpan(section, boundary.start()).start();
                end = lower + uniqueSpan(section, boundary.end()).end();
            } catch (IllegalStateException exception) {
                throw new IllegalStateException("Official claim anchor missing or ambiguous: " + key, exception);
            }
            if (end <= start) {
                throw new IllegalStateException("Official claim boundary changed: " + key);
            }
            String body = content.substring(start, end);
            if (body.isBlank()) {
                throw new IllegalStateException("Official claim text changed: " + key);
            }
            return new SourceChunkData.Definition(configVersion, configJson, sequence,
                    body, boundary.label(), key.startsWith("law-") ? "제19조" : null,
                    null, "normalized-content:utf16:" + start + "-" + end,
                    start, end, "{\"selection\":\"review-candidate-only\"}");
        }).toList();
    }

    private static Span uniqueSpan(String content, String needle) {
        String[] words = needle.trim().split("\\s+");
        String expression = java.util.Arrays.stream(words).map(Pattern::quote)
                .collect(java.util.stream.Collectors.joining("\\s+"));
        Matcher matcher = Pattern.compile(expression).matcher(content);
        if (!matcher.find()) {
            throw new IllegalStateException("Official claim anchor missing or ambiguous");
        }
        Span span = new Span(matcher.start(), matcher.end());
        if (matcher.find()) {
            throw new IllegalStateException("Official claim anchor missing or ambiguous");
        }
        return span;
    }

    private record Span(int start, int end) {}
    private record Boundary(String label, String start, String end,
                            String sectionStart, String sectionEnd) {}
}
