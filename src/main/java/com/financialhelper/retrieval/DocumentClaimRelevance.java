package com.financialhelper.retrieval;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** A conservative, deterministic check over the document title and approved claim text only. */
final class DocumentClaimRelevance {
    private static final Pattern WORD = Pattern.compile("[\\p{L}\\p{N}]+");
    private record Purpose(String label, Pattern query, Pattern evidence) {}
    private static final List<Purpose> PURPOSES = List.of(
            purpose("신청", "신청(?!하면|시|후)|가입(?!하면|시|후)", "신청|가입|접수"),
            purpose("방법", "어떻게|방법|절차", "신청|접수|절차"),
            purpose("해제", "해제|해지", "해제|해지"),
            purpose("비용", "수수료|비용", "수수료|비용"),
            purpose("금액·한도", "한도|금액|얼마", "한도|금액"),
            purpose("제외·예외", "제외|예외", "제외|예외|대상이 아니"),
            purpose("필요 서류", "서류|준비물", "서류|자료|증명"),
            purpose("이용 시간", "시간|운영시간", "시간|시부터|시까지|영업일"),
            purpose("영향", "영향|하락|떨어|불이익", "영향|하락|떨어|불이익"),
            purpose("이용 제한", "못\\s*쓰|제한|(?:어떤|무슨).{0,20}거래.{0,12}차단", "제한|중단|불가|불가능|등록.{0,8}차단|거래.{0,8}차단"));

    private static Purpose purpose(String label, String query, String evidence) {
        return new Purpose(label, Pattern.compile(query), Pattern.compile(evidence));
    }

    static boolean supports(String question, String title, String body) {
        List<String> requested = requestedPurposes(question);
        return !requested.isEmpty() && matchedPurposes(question, title, body).containsAll(requested);
    }

    static List<String> matchedPurposes(String question, String title, String body) {
        return matchedPurposesInSection(question, title, body, null);
    }

    static List<String> matchedPurposesInSection(String question, String title, String body, String section) {
        if (question == null || title == null || body == null) return List.of();
        String q = question.toLowerCase(Locale.ROOT);
        String heading = section == null ? "" : section;
        String searchContext = compact(title + " " + heading + " " + body);
        List<Purpose> purposes = matchingPurposes(q);
        if (purposes.isEmpty()) return List.of();
        var numeric = WORD.matcher(q);
        while (numeric.find()) if (numeric.group().matches(".*\\d.*") && !compact(body).contains(compact(numeric.group()))) return List.of();
        int purposeStart = purposes.stream().mapToInt(p -> {
            var match = p.query().matcher(q);
            return match.find() ? match.start() : q.length();
        }).min().orElse(q.length());
        var matcher = WORD.matcher(q.substring(0, purposeStart));
        List<String> anchors = new ArrayList<>();
        while (matcher.find()) {
            String token = matcher.group();
            if (token.matches(".*\\d.*")) {
                if (!compact(body).contains(compact(token))) return List.of();
                continue;
            }
            if (token.matches(".*(신청|가입|해제|해지|수수료|비용|한도|금액|얼마|제외|예외|서류|준비물|시간|어디|어떤|어떻게|필요한|하나요|있나요|쓰나요|가능하|되나요).*")) continue;
            if (token.equals("이용") || token.matches("기능(이|은|을|의|도)?")) continue;
            token = token.replaceFirst("(에서도|에게도|으로는|에는|에도|에서|으로|에게|이라면|이라도|이면|하면|은|는|이|가|을|를|의|에|도)$", "");
            if (token.length() >= 2) anchors.add(token);
        }
        if (anchors.isEmpty()) return List.of();
        // Restrictions must be attributable to the named subject in the title;
        // a passing mention of another service in the body is not enough.
        if (purposes.stream().anyMatch(p -> p.label().equals("이용 제한"))
                && anchors.stream().filter(anchor -> compact(title).contains(compact(anchor))).count() < Math.min(2, anchors.size())) return List.of();
        int required = Math.min(2, anchors.size());
        if (anchors.stream().filter(anchor -> searchContext.contains(compact(anchor))).count() < required) return List.of();
        // A title enumerating subjects is insufficient to assign an individual
        // claim to one of them: require the claim text itself to identify it.
        boolean singleSubjectSection = !heading.isBlank()
                && !Pattern.compile("(?:와|과)\\s+|\\s+(?:및|또는)\\s+|[·,/]").matcher(heading).find()
                && anchors.stream().allMatch(anchor -> compact(heading).contains(compact(anchor)));
        if (Pattern.compile("(?:와|과)\\s+|\\s+(?:및|또는)\\s+|[·,/]").matcher(title).find()
                && !singleSubjectSection && !anchors.stream().allMatch(anchor -> compact(body).contains(compact(anchor)))) return List.of();
        return purposes.stream().filter(p -> p.evidence().matcher(body).find())
                .filter(p -> !p.label().equals("금액·한도") || body.matches("(?s).*\\d.*"))
                .map(Purpose::label).toList();
    }

    static List<String> requestedPurposes(String question) {
        if (question == null) return List.of();
        return matchingPurposes(question).stream().map(Purpose::label).toList();
    }

    private static List<Purpose> matchingPurposes(String question) {
        List<Purpose> matched = PURPOSES.stream().filter(p -> p.query().matcher(question).find()).toList();
        return matched.size() > 1 ? matched.stream().filter(p -> !p.label().equals("방법")).toList() : matched;
    }

    static boolean hasKnownPurpose(String question) {
        return question != null && PURPOSES.stream().anyMatch(p -> p.query().matcher(question).find());
    }

    static List<String> purposesCoveredByEvidence(String question, String body) {
        if (question == null || body == null) return List.of();
        return matchingPurposes(question).stream().filter(p -> p.evidence().matcher(body).find())
                .filter(p -> !p.label().equals("금액·한도") || body.matches("(?s).*\\d.*"))
                .map(Purpose::label).toList();
    }

    /** Labels are existing general question purposes, supported by this approved text. */
    static List<String> availablePurposes(String body) {
        if (body == null) return List.of();
        var labels = PURPOSES.stream().filter(p -> p.evidence().matcher(body).find())
                .filter(p -> !p.label().equals("금액·한도") || body.matches("(?s).*\\d.*"))
                .map(Purpose::label).distinct().toList();
        return labels.contains("신청") ? labels.stream().filter(label -> !label.equals("방법")).toList() : labels;
    }

    /** Return general procedural evidence as a document reference, not a product eligibility decision. */
    static boolean generalProcedureReference(String question, String title, String approvedDocumentText, String body) {
        if (question == null || title == null || approvedDocumentText == null || body == null) return false;
        if (!Pattern.compile("(?:와|과)\\s+|\\s+(?:및|또는)\\s+|[·,/]").matcher(title).find()) return false;
        List<String> requested = requestedPurposes(question);
        if (requested.isEmpty() || requested.stream().anyMatch(p -> !List.of("신청", "방법", "필요 서류").contains(p))) return false;
        if (Pattern.compile("[0-9,]|제외|예외|한도|금액|기간|조건|이상|이하|초과|미만|대상|제한|차단|불가|전용|일부|만\\s*(?:신청|가능|해당|이용|제공)").matcher(body).find()) return false;
        var word = WORD.matcher(question);
        if (!word.find()) return false;
        String subject = word.group().replaceFirst("(에서는|에서도|에는|에도|에서|으로|은|는|이|가|을|를|의|에|도)$", "");
        if (subject.codePointCount(0, subject.length()) < 4 || !compact(approvedDocumentText).contains(compact(subject))) return false;
        for (String part : title.split("[·,/]|\\s+(?:및|또는)\\s+")) {
            var titleWord = WORD.matcher(part);
            if (titleWord.find()) {
                String named = compact(titleWord.group());
                if (named.length() >= 2 && !compact(question).contains(named) && compact(body).contains(named)) return false;
            }
        }
        return PURPOSES.stream().filter(p -> requested.contains(p.label())).anyMatch(p -> p.evidence().matcher(body).find());
    }

    private static String compact(String value) { return value.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]", ""); }
    private DocumentClaimRelevance() {}
}
