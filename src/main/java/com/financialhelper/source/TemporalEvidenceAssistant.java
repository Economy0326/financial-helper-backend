package com.financialhelper.source;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Read-only hints from the stored source text. A hint is never an applicability review. */
public final class TemporalEvidenceAssistant {
    private static final Pattern DATE = Pattern.compile(
            "(?<!\\d)(?:(\\d{4})\\s*(?:년\\s*|[./-]\\s*)|[‘'’](\\d{2})\\s*년\\s*)(\\d{1,2})\\s*(?:월\\s*|[./-]\\s*)(\\d{1,2})\\s*일?(?!\\d)");
    private static final Pattern SHORT_YEAR = Pattern.compile(
            "(?<!\\d)([‘'’])(\\d{2})[./-](\\d{1,2})[./-](\\d{1,2})일?(?!\\d)");
    private static final int MAX_MENTIONS = 20;

    private TemporalEvidenceAssistant() {}

    public static Assistance analyze(String normalizedContent, int claimStart, int claimEnd,
            LocalDate publishedAt) {
        if (normalizedContent == null || claimStart < 0 || claimEnd > normalizedContent.length()
                || claimStart >= claimEnd) throw new IllegalArgumentException("Invalid stored source range");
        List<DateMention> claimMentions = new ArrayList<>();
        List<DateMention> nearbyContext = new ArrayList<>();
        List<UnresolvedDate> unresolved = new ArrayList<>();
        Matcher matcher = DATE.matcher(normalizedContent);
        boolean truncated = false;
        while (matcher.find()) {
            boolean inClaim = matcher.start() >= claimStart && matcher.end() <= claimEnd;
            if (!inClaim && (matcher.end() < claimStart - 300 || matcher.start() > claimEnd + 300
                    || nearbyContext.size() >= 5)) continue;
            if (inClaim && claimMentions.size() == MAX_MENTIONS) { truncated = true; continue; }
            LocalDate parsed;
            try {
                // An explicit apostrophe denotes the abbreviated 2000s year in these official materials.
                int year = matcher.group(1) == null ? 2000 + Integer.parseInt(matcher.group(2))
                        : Integer.parseInt(matcher.group(1));
                parsed = LocalDate.of(year, Integer.parseInt(matcher.group(3)),
                        Integer.parseInt(matcher.group(4)));
            } catch (DateTimeException invalid) {
                if (inClaim && unresolved.size() < MAX_MENTIONS) {
                    unresolved.add(new UnresolvedDate(matcher.group(),
                            "normalized-content:utf16:" + matcher.start() + "-" + matcher.end(),
                            "존재하지 않는 날짜이므로 적용 시점을 확인할 수 없습니다."));
                }
                continue;
            }
            int sentenceStart = matcher.start();
            while (sentenceStart > 0 && !boundary(normalizedContent.charAt(sentenceStart - 1))) sentenceStart--;
            int sentenceEnd = matcher.end();
            while (sentenceEnd < normalizedContent.length()
                    && !boundary(normalizedContent.charAt(sentenceEnd))) sentenceEnd++;
            String sourceSentence = normalizedContent.substring(sentenceStart, sentenceEnd);
            String sentence = sourceSentence.trim();
            Meaning meaning = classify(sentence, matcher.start() - sentenceStart
                    - sourceSentence.indexOf(sentence), matcher.group().length());
            DateMention mention = new DateMention(parsed, matcher.group(), meaning, sentence,
                    "normalized-content:utf16:" + sentenceStart + "-" + sentenceEnd,
                    inClaim, hasCondition(sentence), hasException(sentence));
            if (inClaim) claimMentions.add(mention);
            else nearbyContext.add(mention);
        }
        Matcher shortYear = SHORT_YEAR.matcher(normalizedContent);
        while (shortYear.find()) {
            boolean inClaim = shortYear.start() >= claimStart && shortYear.end() <= claimEnd;
            if (!inClaim || unresolved.size() == MAX_MENTIONS) continue;
            // The publication year may disambiguate the century, but it is never an effective date.
            int twoDigitYear = Integer.parseInt(shortYear.group(2));
            int candidateYear = publishedAt == null ? -1 : publishedAt.getYear() / 100 * 100 + twoDigitYear;
            if (candidateYear > 0 && candidateYear <= publishedAt.getYear() + 2
                    && candidateYear >= publishedAt.getYear() - 20) {
                try {
                    LocalDate date = LocalDate.of(candidateYear, Integer.parseInt(shortYear.group(3)),
                            Integer.parseInt(shortYear.group(4)));
                    int sentenceStart = shortYear.start();
                    while (sentenceStart > 0 && !boundary(normalizedContent.charAt(sentenceStart - 1))) sentenceStart--;
                    int sentenceEnd = shortYear.end();
                    while (sentenceEnd < normalizedContent.length()
                            && !boundary(normalizedContent.charAt(sentenceEnd))) sentenceEnd++;
                    String sourceSentence = normalizedContent.substring(sentenceStart, sentenceEnd);
                    String sentence = sourceSentence.trim();
                    Meaning meaning = classify(sentence, shortYear.start() - sentenceStart
                            - sourceSentence.indexOf(sentence), shortYear.group().length());
                    claimMentions.add(new DateMention(date, shortYear.group(), meaning, sentence,
                            "normalized-content:utf16:" + sentenceStart + "-" + sentenceEnd,
                            true, hasCondition(sentence), hasException(sentence)));
                    continue;
                } catch (DateTimeException invalid) { /* Keep an invalid date unresolved. */ }
            }
            unresolved.add(new UnresolvedDate(shortYear.group(),
                    "normalized-content:utf16:" + shortYear.start() + "-" + shortYear.end(),
                    "두 자리 연도는 세기를 추측하지 않습니다."));
        }
        List<DateMention> mentions = new ArrayList<>(claimMentions);
        mentions.addAll(nearbyContext);
        List<DateMention> scoped = claimMentions;
        // Publication and amendment dates describe the document, not the claim's effective date.
        // They may coexist with an explicit effective date without creating a time conflict.
        List<DateMention> applicability = scoped.stream().filter(mention ->
                mention.meaning() != Meaning.PUBLICATION && mention.meaning() != Meaning.AMENDMENT).toList();
        List<DateMention> relevant = applicability.isEmpty() ? scoped : applicability;
        Set<Meaning> meanings = relevant.stream().map(DateMention::meaning).collect(Collectors.toSet());
        Set<LocalDate> dates = relevant.stream().map(DateMention::date).collect(Collectors.toSet());
        TemporalEvidencePolicy recommendation = TemporalEvidencePolicy.HOLD;
        String reason = "미검출·확인 필요: 주장 원문에서 적용 날짜를 확인하지 못했습니다.";
        if (truncated) reason = "날짜가 많아 자동 분류를 중단했습니다.";
        else if (!unresolved.isEmpty()) reason = "미해석 날짜 표기가 있어 적용 시점을 직접 확인해야 합니다.";
        else if (dates.size() > 1 || meanings.size() > 1)
            reason = "주장에 여러 날짜가 있어 각 날짜의 역할과 조건을 직접 확인해야 합니다.";
        else if (!relevant.isEmpty()) {
            DateMention mention = relevant.getFirst();
            reason = switch (mention.meaning()) {
                case PUBLICATION, AMENDMENT -> "게시·개정 날짜는 제도 적용일의 근거가 아닙니다.";
                case EFFECTIVE, LAW_EFFECTIVE -> "원문에 시행·적용 표현이 있습니다. 대상과 예외를 확인해야 합니다.";
                case CONTRACT -> "계약 기준일이 보입니다. 계약 종류와 종료 범위를 확인해야 합니다.";
                case HISTORICAL -> "과거 기준일이 보입니다. 현재 제도 적용에는 사용할 수 없습니다.";
                case UNCLEAR -> "날짜의 의미를 원문만으로 특정할 수 없습니다.";
            };
            if (mention.meaning() == Meaning.EFFECTIVE)
                recommendation = TemporalEvidencePolicy.EXPLICIT_EFFECTIVE;
            else if (mention.meaning() == Meaning.LAW_EFFECTIVE)
                recommendation = TemporalEvidencePolicy.VERSION_EFFECTIVE;
        }
        if (!unresolved.isEmpty()) recommendation = TemporalEvidencePolicy.HOLD;
        return new Assistance(publishedAt, List.copyOf(mentions), List.copyOf(unresolved), recommendation, reason,
                truncated, "공식 URL의 현재 상태는 확인하지 않았습니다. 추천은 승인·READY가 아닙니다.");
    }

    private static boolean boundary(char value) {
        return value == '\n' || value == '\r' || value == '。' || value == '?' || value == '!';
    }

    private static Meaning classify(String sentence, int dateOffset, int dateLength) {
        if (contains(sentence, "게시 및 시행", "발표 및 시행", "공표 및 시행")) return Meaning.UNCLEAR;
        if (contains(sentence, "계약") && contains(sentence, "적용") && !contains(sentence, "법령", "이 법", "시행령")) {
            int contractAt = sentence.indexOf("계약");
            if (Math.abs(contractAt - dateOffset) <= 25) return Meaning.CONTRACT;
        }
        String[][] cues = { {"게시일", "게시", "발표", "공표"}, {"타법개정", "일부개정", "개정일"},
                {"시행", "적용", "발효"}, {"계약", "체결", "갱신"}, {"통계", "집계", "기준일", "기준 시점"} };
        int nearest = Integer.MAX_VALUE;
        Meaning choice = Meaning.UNCLEAR;
        boolean tie = false;
        for (int kind = 0; kind < cues.length; kind++) {
            for (String cue : cues[kind]) {
                int at = sentence.indexOf(cue);
                while (at >= 0) {
                    int distance = at + cue.length() <= dateOffset ? dateOffset - at - cue.length()
                            : at >= dateOffset + dateLength ? at - dateOffset - dateLength : 0;
                    Meaning meaning = switch (kind) {
                        case 0 -> Meaning.PUBLICATION;
                        case 1 -> Meaning.AMENDMENT;
                        case 2 -> contains(sentence, "법", "시행령", "조문")
                                ? Meaning.LAW_EFFECTIVE : Meaning.EFFECTIVE;
                        case 3 -> Meaning.CONTRACT;
                        default -> Meaning.HISTORICAL;
                    };
                    if (distance < nearest) { nearest = distance; choice = meaning; tie = false; }
                    else if (distance == nearest && choice != meaning) tie = true;
                    at = sentence.indexOf(cue, at + cue.length());
                }
            }
        }
        return nearest > 60 || tie ? Meaning.UNCLEAR : choice;
    }

    private static boolean hasCondition(String sentence) {
        return contains(sentence, "다만", "경우", "한정", "제외", "이전", "이후", "신규", "갱신", "부터");
    }

    private static boolean hasException(String sentence) {
        return contains(sentence, "다만", "제외", "예외", "소급되지", "적용되지");
    }

    private static boolean contains(String text, String... tokens) {
        for (String token : tokens) if (text.contains(token)) return true;
        return false;
    }

    public enum Meaning { PUBLICATION, AMENDMENT, EFFECTIVE, LAW_EFFECTIVE, CONTRACT, HISTORICAL, UNCLEAR }
    public record DateMention(LocalDate date, String sourceText, Meaning meaning, String sentence,
                              String locator, boolean inClaim, boolean hasCondition, boolean hasException) {}
    public record UnresolvedDate(String sourceText, String locator, String reason) {}
    public record Assistance(LocalDate documentPublishedAt, List<DateMention> mentions,
                             List<UnresolvedDate> unresolvedDates,
                             TemporalEvidencePolicy recommendedPolicy, String reason,
                             boolean truncated, String currentnessNotice) {}
}
