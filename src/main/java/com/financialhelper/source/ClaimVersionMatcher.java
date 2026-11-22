package com.financialhelper.source;

import java.util.List;
import java.util.regex.Pattern;

/** 위치가 아닌 원문과 연결 관계를 비교한다. */
final class ClaimVersionMatcher {
    private static final Pattern MULTIPLE_SUBJECTS = Pattern.compile("[·,/]|(?:와|과)\\s+|\\s+(?:및|또는)\\s+");
    private static final Pattern HEADING = Pattern.compile("^(?:#{1,6}\\s+|[◆◇■□]\\s*|제\\s*\\d+\\s*(?:장|절|조)|\\d+\\.\\s+).*");
    private static final Pattern TERMINAL = Pattern.compile(".*[.!?。！？]$");
    private static final Pattern QUESTION = Pattern.compile(".*[?？]$");
    private ClaimVersionMatcher() {}

    static boolean same(DocumentClaimReviewerService.Claim first, DocumentClaimReviewerService.Claim second) {
        if (!normalized(first.body()).equals(normalized(second.body())) ||
                !normalized(first.context()).equals(normalized(second.context()))) return false;
        return relations(first).equals(relations(second));
    }

    static boolean sameSection(String first, int firstOffset, String second, int secondOffset) {
        return section(first, firstOffset).equals(section(second, secondOffset));
    }

    static boolean hasUnambiguousSection(String title, String text, int offset) {
        boolean multipleSubjects = MULTIPLE_SUBJECTS.matcher(title).find();
        return !multipleSubjects || !section(text, offset).isBlank();
    }

    private static String section(String text, int offset) {
        if (offset < 0 || offset > text.length()) throw new IllegalArgumentException("Invalid claim position");
        String result = "";
        for (String line : text.substring(0, offset).split("\\n")) {
            String part = line.strip();
            boolean heading = HEADING.matcher(part).matches() && !TERMINAL.matcher(part).matches();
            if (heading || QUESTION.matcher(part).matches()) result = normalized(part);
        }
        return result;
    }

    private static List<String> relations(DocumentClaimReviewerService.Claim claim) {
        return claim.relations().stream().map(r -> r.role() + "\u0000" + r.ambiguous() + "\u0000" + normalized(r.text())).toList();
    }

    private static String normalized(String value) {
        return java.text.Normalizer.normalize(value, java.text.Normalizer.Form.NFC).strip();
    }
}
