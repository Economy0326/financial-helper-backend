package com.financialhelper.source;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.time.LocalDate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A conservative, read-only candidate proposal for new normalized documents.
 * Nothing returned here is an approved claim, a chunk, or retrieval evidence.
 */
public final class GenericClaimCandidateExtractor {
    private static final String SECTION_NOTICE = "같은 절의 표·각주·인용과 적용 조건의 연결을 공식 원문에서 확인해야 합니다.";
    private static final String PLANNED_NOTICE = "계획 또는 예정의 시행 여부와 시점을 확인해야 합니다.";
    private static final Pattern PAGE = Pattern.compile("\\[PAGE\\s+(\\d+)]");
    private static final Pattern SENTENCE_END = Pattern.compile("[.!?。！？](?=\\s|$)");
    private static final Pattern FINANCE = Pattern.compile("금융|예금|적금|대출|보험|계좌|카드|결제|투자|증권|금리|이자|수수료|금융상품|보상|연금|분쟁|청구");
    private static final Pattern SIGNAL = Pattern.compile("의미|정의|이란|이라(?:고|는)|대상|범위|적용|신청|청구|신고|제출|절차|확인|보호|지급|부과|납부|계산|주의|이용|제공|할 수 있|해야 합|할 수 없");
    private static final Pattern ADDITIONAL_SIGNAL = Pattern.compile("대환|상환|만기|한도|이하|이상|차단|중단|제한|등록|전환|연장|증액|이동|가능|제외");
    private static final Pattern ADDITIONAL_CONDITION = Pattern.compile("이하|이상|한도|만기|운전자금");
    private final boolean additionalSignals;
    private final boolean versionThree;
    public GenericClaimCandidateExtractor() { this(false, false); }
    GenericClaimCandidateExtractor(boolean additionalSignals) { this(additionalSignals, false); }
    GenericClaimCandidateExtractor(boolean additionalSignals, boolean versionThree) { this.additionalSignals = additionalSignals; this.versionThree = versionThree; }
    private static final Pattern EXCEPTION = Pattern.compile("다만|단,|단\\s|예외|제외|적용되지 않|적용하지 않|불가|아니합니다");
    private static final Pattern ADDITIONAL_EXCEPTION = Pattern.compile("다만|(?<![가-힣])단(?:,|\\s)|예외|제외|적용되지 않|적용하지 않|불가|아니합니다");
    private static final Pattern CONDITION = Pattern.compile("경우|조건|때에|때에는|해당하면|해당하는|대상|이후|이전|이내|초과|미만|한정");
    private static final Pattern DATE = Pattern.compile("(?<!\\d)(?:\\d{4}[.\\-/년]\\s*\\d{1,2}[.\\-/월]\\s*\\d{1,2}|[‘’']\\d{2}년\\s*\\d{1,2}월\\s*\\d{1,2}일)");
    private static final Pattern REFERENCE = Pattern.compile("^(?:이|그|해당|위|아래|다음|이러한|이와 같은)(?:\\s|것|경우|제도|상품|절차|내용|기관)");
    private static final Pattern INCOMPLETE = Pattern.compile("(?:다음과 같습니다|아래와 같습니다|다음과 같음|다음과 같다|다음과 같으며|다음과 같이|다음 각 호|아래 표|별표|다음 각 목)\\s*[:：]?[.!]?$|[:：]$");
    private static final Pattern PLANNED = Pattern.compile("예정|추진|계획");
    private static final Pattern TABLE_REFERENCE = Pattern.compile("(?:^|\\s)(?:(?:아래|다음|위|해당)\\s*)?(?:별표|표)(?:\\s|에|의|를|와|에서|[0-9(（])");

    public List<Candidate> extractNewDocument(SourceDocument document) {
        return extract(document, false);
    }

    List<Candidate> extractHistoricalForComparison(SourceDocument document) {
        return extract(document, true);
    }

    private List<Candidate> extract(SourceDocument document, boolean historical) {
        Identity identity = validate(document, historical);
        String text = document.getNormalizedContent();
        if (versionThree) return extractV3(identity, document.getTitle(), text);
        List<Unit> units = Structure.split(text, additionalSignals);
        boolean financeContext = FINANCE.matcher(document.getTitle() == null ? "" : document.getTitle()).find()
                || FINANCE.matcher(text).find();
        if (!financeContext) return List.of();
        Map<String, Candidate> unique = new LinkedHashMap<>();
        for (int index = 0; index < units.size(); index++) {
            Unit unit = units.get(index);
            String body = unit.span().text();
            if (unit.heading() || unit.pageMarker() || !(SIGNAL.matcher(body).find()
                    || additionalSignals && ADDITIONAL_SIGNAL.matcher(body).find())) continue;
            boolean qualifier = isException(body) || CONDITION.matcher(body).find();
            if (qualifier && startsQualifier(body) && hasPreviousSupport(units, index)) continue;
            if (!looksLikeStatement(body) && !(additionalSignals && body.length() >= 10 && ADDITIONAL_SIGNAL.matcher(body).find())) continue;

            List<Related> related = new ArrayList<>();
            if (CONDITION.matcher(body).find() || additionalSignals && ADDITIONAL_CONDITION.matcher(body).find())
                related.add(new Related(Relation.CONDITION, unit.span(), false));
            if (isException(body)) related.add(new Related(Relation.EXCEPTION, unit.span(), false));
            if (DATE.matcher(body).find()) related.add(new Related(Relation.DATE, unit.span(), false));
            List<String> warnings = new ArrayList<>();
            boolean sectionNotice = structuredSectionNeedsReview(units, index);
            if (sectionNotice) warnings.add(SECTION_NOTICE);
            if (startsQualifier(body)) warnings.add("단서의 선행 근거를 확인해야 합니다.");
            if (startsNote(body)) warnings.add("각주가 연결되는 근거와 전체 맥락을 확인해야 합니다.");
            if (PLANNED.matcher(body).find()) warnings.add(PLANNED_NOTICE);
            if (REFERENCE.matcher(body).find()) warnings.add("지시어의 대상과 앞 문맥을 확인해야 합니다.");
            if (INCOMPLETE.matcher(body).find() || !hasTerminal(body)) warnings.add("문장이 불완전하거나 뒤의 목록이 필요할 수 있습니다.");
            if (isTableOrCorrupt(body)) warnings.add("표 또는 추출 손상으로 원문 구조 확인이 필요합니다.");
            if (TABLE_REFERENCE.matcher(body).find()) warnings.add("참조한 표의 조건과 예외를 확인해야 합니다.");

            int contextStart = unit.span().start();
            int contextEnd = unit.span().end();
            if (index > 0 && !units.get(index - 1).heading() && !units.get(index - 1).pageMarker()) {
                contextStart = units.get(index - 1).span().start();
            }
            for (int next = index + 1; next < units.size(); next++) {
                Unit linked = units.get(next);
                if (linked.heading()) break;
                if (linked.pageMarker()) {
                    if (next + 1 < units.size()) {
                        Unit acrossPage = units.get(next + 1);
                        String acrossText = acrossPage.span().text();
                        if (!acrossPage.heading() && !acrossPage.pageMarker()
                                && (startsQualifier(acrossText) || startsNote(acrossText))) {
                            Relation relation = startsNote(acrossText) ? Relation.NOTE
                                    : isException(acrossText) ? Relation.EXCEPTION : Relation.CONDITION;
                            related.add(new Related(relation, acrossPage.span(), true));
                            contextEnd = acrossPage.span().end();
                            warnings.add("페이지를 넘는 단서의 연결과 원문을 확인해야 합니다.");
                        }
                    }
                    break;
                }
                String nextText = linked.span().text();
                boolean implicitException = additionalSignals && !startsQualifier(nextText)
                        && !startsNote(nextText) && isException(nextText);
                if (!startsQualifier(nextText) && !startsNote(nextText) && !implicitException) break;
                // An adjacent exclusion without an explicit reference may belong to a different rule.
                // Preserve it for review, but never assert the relationship automatically.
                boolean ambiguous = linked.paragraph() != unit.paragraph() || implicitException;
                Relation relation = startsNote(nextText) ? Relation.NOTE
                        : isException(nextText) ? Relation.EXCEPTION : Relation.CONDITION;
                related.add(new Related(relation, linked.span(), ambiguous));
                contextEnd = linked.span().end();
                if (ambiguous) warnings.add(implicitException
                        ? "인접한 제외사항이 이 근거에 적용되는지 확인해야 합니다."
                        : "인접 문단의 단서가 이 근거에 적용되는지 확인해야 합니다.");
                if (startsNote(nextText) || isTableOrCorrupt(nextText)) warnings.add("각주·표의 전체 맥락을 확인해야 합니다.");
            }
            if (index + 1 < units.size()) {
                Unit following = units.get(index + 1);
                if (!following.heading() && !following.pageMarker()) contextEnd = Math.max(contextEnd, following.span().end());
                if (!following.heading() && !following.pageMarker() && (isTableOrCorrupt(following.span().text())
                        || TABLE_REFERENCE.matcher(following.span().text()).find())) {
                    warnings.add("인접한 표와 적용 범위의 관계를 확인해야 합니다.");
                }
            }
            if (sectionNotice) {
                int first = index, last = index;
                while (first > 0 && !explicitStructuredHeading(units.get(first - 1))) first--;
                while (last + 1 < units.size() && !explicitStructuredHeading(units.get(last + 1))) last++;
                contextStart = Math.min(contextStart, units.get(first).span().start());
                contextEnd = Math.max(contextEnd, units.get(last).span().end());
            }
            Span context = Span.of(text, contextStart, contextEnd);
            verify(text, unit.span());
            related.forEach(value -> verify(text, value.span()));
            verify(text, context);
            String reason = reason(body);
            Candidate candidate = new Candidate(identity, unit.span(), context, unit.page(), reason,
                    List.copyOf(related), List.copyOf(warnings), hasBlockingWarnings(warnings) ? ReviewStatus.HOLD : ReviewStatus.REVIEW_REQUIRED,
                    List.of());
            String key = body.replaceAll("\\s+", " ").trim() + "|" + related.stream()
                    .map(value -> value.relation() + ":" + value.span().text().replaceAll("\\s+", " ").trim())
                    .toList();
            Candidate prior = unique.get(key);
            if (prior == null) unique.put(key, candidate);
            else {
                List<Span> duplicates = new ArrayList<>(prior.duplicateExcerpts());
                duplicates.add(candidate.excerpt());
                List<String> duplicateWarnings = new ArrayList<>(prior.warnings());
                if (!prior.context().text().equals(candidate.context().text())) {
                    duplicateWarnings.add("중복 문장의 서로 다른 문맥과 적용 범위를 확인해야 합니다.");
                }
                candidate.warnings().stream().filter(warning -> !duplicateWarnings.contains(warning))
                        .forEach(duplicateWarnings::add);
                unique.put(key, new Candidate(prior.source(), prior.excerpt(), prior.context(), prior.page(), prior.selectionReason(),
                        prior.related(), List.copyOf(duplicateWarnings),
                        hasBlockingWarnings(duplicateWarnings) ? ReviewStatus.HOLD : ReviewStatus.REVIEW_REQUIRED, List.copyOf(duplicates)));
            }
        }
        return List.copyOf(unique.values());
    }

    private static Identity validate(SourceDocument document, boolean historical) {
        if (document == null || document.getId() == null || document.getDocumentVersion() < 1
                || document.getStatus() != (historical ? SourceDocumentStatus.SUPERSEDED : SourceDocumentStatus.ACTIVE) || document.getSourceRegistry() == null) {
            throw new IllegalArgumentException("An active persisted document version is required");
        }
        var registry = document.getSourceRegistry();
        if (registry.getSourceKey() == null || registry.getSourceKey().isBlank()
                || document.getCanonicalUrl() == null || document.getCanonicalUrl().isBlank()) {
            throw new IllegalArgumentException("Document registry identity is required");
        }
        if (OfficialInformationClaimSelector.supports(registry.getSourceKey())
                || VerifiedPdfMetadata.DEPOSIT_PDF_URL.equals(document.getCanonicalUrl())) {
            throw new IllegalArgumentException("Existing reviewed document uses its existing selector");
        }
        String normalized = document.getNormalizedContent();
        if (normalized == null || document.getOriginalContent() == null
                || !Objects.equals(document.getRawSha256(), SourceHashing.sha256(document.getOriginalContent()))
                || !Objects.equals(document.getContentSha256(), SourceHashing.sha256(normalized))) {
            throw new IllegalArgumentException("Document content or hash is inconsistent");
        }
        return new Identity(document.getId(), document.getDocumentVersion(), document.getRawSha256(),
                document.getContentSha256(), document.getCanonicalUrl(), document.getPublishedAt());
    }

    /* V3 reads the same immutable normalized text; separators are boundaries, never evidence. */
    private static final Pattern V3_BOUNDARY = Pattern.compile("\\|\\s*(?:미디어 제외|연락처 제외|인용 제외|인용 문맥 확인|인용 끝|각주 참조)\\s*\\|");
    private static final Pattern V3_HEADING = Pattern.compile("^(?:#{1,6}\\s+|[◆◇■□]\\s*|(?:제\\s*\\d+\\s*(?:장|절|조))|(?:\\d+\\.\\s+)).*");
    private static final Pattern V3_QUESTION = Pattern.compile("(?s).*[?？]\\s*$");
    // A limit, maturity or product name alone is a subject, not an applicability condition.
    private static final Pattern V3_ADDITIONAL_CONDITION = Pattern.compile("이하|이상|제한(?:을|이|은|도)?\\s*(?:두지\\s*않|없)");

    private List<Candidate> extractV3(Identity identity, String title, String text) {
        if (!FINANCE.matcher(title == null ? "" : title).find() && !FINANCE.matcher(text).find()) return List.of();
        List<Unit> units = v3Units(text);
        Map<String, Candidate> unique = new LinkedHashMap<>();
        java.util.Set<Integer> attachedQualifiers = new java.util.HashSet<>();
        for (int i = 0; i < units.size(); i++) {
            Unit unit = units.get(i);
            String body = unit.span().text();
            if (unit.heading() || unit.pageMarker() || V3_QUESTION.matcher(body).matches()
                    || body.startsWith("| 구조화 표") || INCOMPLETE.matcher(body).find()
                    || !(SIGNAL.matcher(body).find() || ADDITIONAL_SIGNAL.matcher(body).find()
                    || ADDITIONAL_EXCEPTION.matcher(body).find())
                    || !looksLikeStatement(body)) continue;
            // A qualifier already attached to the preceding sentence is not a second fact.
            if (attachedQualifiers.contains(unit.span().start())) continue;
            List<Related> related = new ArrayList<>();
            List<String> warnings = new ArrayList<>();
            boolean condition = CONDITION.matcher(body).find() || V3_ADDITIONAL_CONDITION.matcher(body).find();
            boolean exception = ADDITIONAL_EXCEPTION.matcher(body).find();
            Matcher divider = Pattern.compile("(?:다만|(?<![가-힣])단,)").matcher(body);
            if (condition && exception && divider.find() && divider.start() > 0) {
                related.add(new Related(Relation.CONDITION, Span.of(text, unit.span().start(), unit.span().start() + divider.start()), false));
                related.add(new Related(Relation.EXCEPTION, Span.of(text, unit.span().start() + divider.start(), unit.span().end()), false));
            } else if (exception) {
                related.add(new Related(Relation.EXCEPTION, unit.span(), false));
                if (condition) {
                    Matcher conditionSpan = CONDITION.matcher(body);
                    boolean foundCondition = conditionSpan.find();
                    if (!foundCondition) { conditionSpan = V3_ADDITIONAL_CONDITION.matcher(body); foundCondition = conditionSpan.find(); }
                    boolean negatedTarget = foundCondition && "대상".equals(conditionSpan.group())
                            && body.substring(conditionSpan.end()).matches("(?s)^\\s*(?:이|은|는|에서|으로)?\\s*(?:아니|제외|불가).*");
                    if (foundCondition && !negatedTarget && conditionSpan.end() < body.length())
                        related.add(new Related(Relation.CONDITION, Span.of(text, unit.span().start(), unit.span().start() + conditionSpan.end()), true));
                    warnings.add("조건과 예외의 적용 관계를 원문에서 확인해야 합니다.");
                }
            } else if (condition) related.add(new Related(Relation.CONDITION, unit.span(), false));
            if (DATE.matcher(body).find()) related.add(new Related(Relation.DATE, unit.span(), false));
            int contextStart = unit.span().start(), contextEnd = unit.span().end();
            if (i > 0 && !units.get(i - 1).pageMarker() && !units.get(i - 1).heading()) contextStart = units.get(i - 1).span().start();
            if (i + 1 < units.size()) {
                Unit next = units.get(i + 1);
                if (!next.pageMarker() && !next.heading() && !V3_QUESTION.matcher(next.span().text()).matches()) {
                    contextEnd = next.span().end();
                    if (startsQualifier(next.span().text()) || startsNote(next.span().text())) {
                        boolean ambiguous = next.paragraph() != unit.paragraph();
                        Relation role = startsNote(next.span().text()) ? Relation.NOTE
                                : ADDITIONAL_EXCEPTION.matcher(next.span().text()).find() ? Relation.EXCEPTION
                                : next.span().text().stripLeading().matches("^(?:이|해당) 경우.*") ? Relation.NOTE : Relation.CONDITION;
                        related.add(new Related(role, next.span(), ambiguous));
                        if (ambiguous) warnings.add("인접 문단의 단서가 이 근거에 적용되는지 확인해야 합니다.");
                    }
                }
            }
            if (REFERENCE.matcher(body).find()) warnings.add("지시어의 대상과 앞 문맥을 확인해야 합니다.");
            if (startsQualifier(body)) warnings.add("단서의 선행 근거를 확인해야 합니다.");
            if (startsNote(body)) warnings.add("각주가 연결되는 근거와 전체 맥락을 확인해야 합니다.");
            if (PLANNED.matcher(body).find()) warnings.add(PLANNED_NOTICE);
            if (TABLE_REFERENCE.matcher(body).find() || isTableOrCorrupt(body)) warnings.add("표 또는 원문 구조의 조건과 예외를 확인해야 합니다.");
            Span context = Span.of(text, contextStart, contextEnd);
            related.forEach(r -> verify(text, r.span()));
            Candidate candidate = new Candidate(identity, unit.span(), context, unit.page(), reason(body), List.copyOf(related),
                    List.copyOf(warnings), hasBlockingWarnings(warnings) ? ReviewStatus.HOLD : ReviewStatus.REVIEW_REQUIRED, List.of());
            // Identical wording in different surrounding contexts may have different scope.
            String key = body.replaceAll("\\s+", " ").trim() + "|" + related.stream().map(r -> r.relation() + ":" + r.span().text().replaceAll("\\s+", " ").trim()).toList()
                    + "|" + context.text().replaceAll("\\s+", " ").trim();
            unique.putIfAbsent(key, candidate);
            related.stream().filter(r -> !r.ambiguous() && r.span().start() >= unit.span().end())
                    .forEach(r -> attachedQualifiers.add(r.span().start()));
        }
        return List.copyOf(unique.values());
    }

    private static List<Unit> v3Units(String text) {
        List<Unit> result = new ArrayList<>();
        int offset = 0, paragraph = 0;
        for (String line : text.split("\\n", -1)) {
            int start = offset, end = start + line.length(); offset = end + 1;
            if (line.isBlank()) { paragraph++; continue; }
            Matcher boundary = V3_BOUNDARY.matcher(line);
            int part = 0;
            while (boundary.find()) {
                v3Segment(text, result, start + part, start + boundary.start(), paragraph);
                result.add(new Unit(Span.of(text, start + boundary.start(), start + boundary.end()), paragraph, null, false, true));
                part = boundary.end();
            }
            v3Segment(text, result, start + part, end, paragraph);
            paragraph++;
        }
        return List.copyOf(result);
    }
    private static void v3Segment(String text, List<Unit> result, int start, int end, int paragraph) {
        while (start < end && Character.isWhitespace(text.charAt(start))) start++;
        while (end > start && Character.isWhitespace(text.charAt(end - 1))) end--;
        if (start >= end) return;
        String part = text.substring(start, end);
        if (part.startsWith("| 구조화 표") || PAGE.matcher(part).matches()) {
            result.add(new Unit(Span.of(text, start, end), paragraph, null, false, true)); return;
        }
        if (V3_HEADING.matcher(part).matches() && !hasTerminal(part)) {
            result.add(new Unit(Span.of(text, start, end), paragraph, null, true, false)); return;
        }
        Matcher punctuation = SENTENCE_END.matcher(part); int cursor = start;
        while (punctuation.find()) { v3Add(text, result, cursor, start + punctuation.end(), paragraph); cursor = start + punctuation.end(); }
        v3Add(text, result, cursor, end, paragraph);
    }
    private static void v3Add(String text, List<Unit> result, int start, int end, int paragraph) {
        while (start < end && Character.isWhitespace(text.charAt(start))) start++;
        while (end > start && Character.isWhitespace(text.charAt(end - 1))) end--;
        if (start < end) result.add(new Unit(Span.of(text, start, end), paragraph, null, false, false));
    }

    private static boolean hasPreviousSupport(List<Unit> units, int index) {
        if (index == 0) return false;
        Unit previous = units.get(index - 1);
        return !previous.heading() && !previous.pageMarker() && SIGNAL.matcher(previous.span().text()).find();
    }
    private static boolean startsQualifier(String text) {
        return text.stripLeading().matches("^(?:다만|단[, ]|예외|제외|이 경우|해당 경우|조건).*");
    }
    private static boolean startsNote(String text) { return text.stripLeading().matches("^(?:※|\\*|주[) :]).*"); }
    private static boolean looksLikeStatement(String text) { return text.length() >= 10 && (hasTerminal(text) || INCOMPLETE.matcher(text).find() || isTableOrCorrupt(text)); }
    private static boolean hasTerminal(String text) { return text.matches("(?s).*(?:[.!?。！？]|(?:다|니다|음))$"); }
    private static boolean isTableOrCorrupt(String text) { return text.indexOf('|') >= 0 || text.indexOf('\t') >= 0 || text.indexOf('\uFFFD') >= 0; }
    private static boolean hasBlockingWarnings(List<String> warnings) {
        return warnings.stream().anyMatch(w -> !SECTION_NOTICE.equals(w) && !PLANNED_NOTICE.equals(w));
    }
    private static boolean structuredSectionNeedsReview(List<Unit> units, int index) {
        int start = index, end = index;
        while (start > 0 && !explicitStructuredHeading(units.get(start - 1))) start--;
        while (end + 1 < units.size() && !explicitStructuredHeading(units.get(end + 1))) end++;
        for (int i = start; i <= end; i++) {
            String text = units.get(i).span().text();
            if (text.contains("| 구조화 표") || text.contains("| 각주 참조") || text.contains("| 인용 문맥 확인") || text.contains("| 인용 제외") || text.contains("| 미디어 제외") || text.contains("| 연락처 제외")
                    || text.matches("(?s).*[“”\"‘’].*")) return true;
        }
        return false;
    }
    private static boolean explicitStructuredHeading(Unit unit) {
        return unit.heading() && unit.span().text().startsWith("# ");
    }
    private boolean isException(String text) {
        return (additionalSignals ? ADDITIONAL_EXCEPTION : EXCEPTION).matcher(text).find();
    }
    private String reason(String text) {
        if (isException(text)) return "원문에 예외 또는 제외 표현이 있습니다.";
        if (CONDITION.matcher(text).find()) return "원문에 적용 대상 또는 조건 표현이 있습니다.";
        if (text.matches("(?s).*(?:의미|정의|이란|이라(?:고|는)).*")) return "원문에 용어·제도 설명 표현이 있습니다.";
        return "원문에 절차·안내 표현이 있습니다.";
    }
    private static void verify(String text, Span span) {
        if (span.start() < 0 || span.end() > text.length() || span.start() >= span.end()
                || !text.substring(span.start(), span.end()).equals(span.text())) {
            throw new IllegalStateException("Candidate locator does not match normalized content");
        }
    }

    public enum ReviewStatus { REVIEW_REQUIRED, HOLD }
    public enum Relation { CONDITION, EXCEPTION, DATE, NOTE }
    /** Publication date is document metadata, never an inferred effective date. */
    public record Identity(UUID documentId, int documentVersion, String rawSha256, String contentSha256,
                           String canonicalUrl, LocalDate publishedAt) {}
    public record Span(int start, int end, String text, String locator) {
        static Span of(String document, int start, int end) {
            return new Span(start, end, document.substring(start, end), "normalized-content:utf16:" + start + "-" + end);
        }
    }
    public record Related(Relation relation, Span span, boolean ambiguous) {}
    public record Candidate(Identity source, Span excerpt, Span context, Integer page, String selectionReason,
                            List<Related> related, List<String> warnings, ReviewStatus status, List<Span> duplicateExcerpts) {}

    private record Unit(Span span, int paragraph, Integer page, boolean heading, boolean pageMarker) {}

    /** Offsets use Java UTF-16 indexes, matching SourceChunkData and normalized content. */
    private static final class Structure {
        static List<Unit> split(String text, boolean additionalSignals) {
            List<Unit> units = new ArrayList<>();
            int position = 0, paragraph = 0;
            Integer page = null;
            for (String line : text.split("\\n", -1)) {
                int start = position, end = start + line.length();
                position = end + 1;
                while (start < end && Character.isWhitespace(text.charAt(start))) start++;
                while (end > start && Character.isWhitespace(text.charAt(end - 1))) end--;
                if (start == end) { paragraph++; continue; }
                String content = text.substring(start, end);
                if (content.startsWith("| 구조화 표")) {
                    units.add(new Unit(Span.of(text, start, end), paragraph, page, false, false));
                    continue;
                }
                Matcher marker = PAGE.matcher(content);
                if (marker.matches()) {
                    page = Integer.valueOf(marker.group(1));
                    units.add(new Unit(Span.of(text, start, end), paragraph, page, false, true));
                    continue;
                }
                boolean heading = !content.startsWith("|") && (content.matches("^(?:#{1,6}\\s+.*|제\\s*\\d+\\s*(?:장|절|조).*|\\d+\\.\\s*[^.!?。！？]{1,60})$")
                        || content.length() <= 45 && !SIGNAL.matcher(content).find()
                        && !(additionalSignals && ADDITIONAL_SIGNAL.matcher(content).find()) && !hasTerminal(content));
                if (heading) { units.add(new Unit(Span.of(text, start, end), paragraph, page, true, false)); continue; }
                Matcher punctuation = SENTENCE_END.matcher(content);
                int segmentStart = start;
                while (punctuation.find()) {
                    int segmentEnd = start + punctuation.end();
                    add(text, units, segmentStart, segmentEnd, paragraph, page);
                    segmentStart = segmentEnd;
                }
                add(text, units, segmentStart, end, paragraph, page);
            }
            return List.copyOf(units);
        }
        private static void add(String text, List<Unit> units, int start, int end, int paragraph, Integer page) {
            while (start < end && Character.isWhitespace(text.charAt(start))) start++;
            while (end > start && Character.isWhitespace(text.charAt(end - 1))) end--;
            if (start < end) units.add(new Unit(Span.of(text, start, end), paragraph, page, false, false));
        }
    }
}
