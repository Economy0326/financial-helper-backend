package com.financialhelper.law;

import org.springframework.stereotype.Service;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Retrieves and validates law text for later human review. It does not create
 * or change a ProcedureVersion or a FinancialActionPlan.
 */
@Service
public class LawEvidenceService {

    private static final String SEARCH_TOOL = "search_law";
    private static final String TEXT_TOOL = "get_law_text";
    private static final String APPLICABLE_TOOL = "legal_analysis";
    private static final String PROTOCOL_VERSION = "2024-11-05";
    private static final String PINNED_REPOSITORY =
            "https://github.com/chrisryugj/korean-law-mcp";
    private static final String PINNED_TAG = "v4.9.1";
    private static final String PINNED_COMMIT =
            "860cfcbce9c01c664766ec1badca8d4468b87488";
    private static final Pattern SEARCH_ENTRY = Pattern.compile(
            "(?m)^\\s*\\d+\\.\\s+(.+?)(?:\\s+\\[(현행|연혁)\\])?\\s*$");
    private static final Pattern LAW_ID = Pattern.compile("(?m)^\\s*-\\s*법령ID:\\s*(\\S+)");
    private static final Pattern MST = Pattern.compile("(?m)^\\s*-\\s*MST:\\s*(\\S+)");
    private static final Pattern PROM_DATE = Pattern.compile("(?m)^\\s*-\\s*공포일:\\s*([^/\\r\\n]+)");
    private static final Pattern EFFECTIVE_DATE = Pattern.compile(
            "(?m)^\\s*-\\s*공포일:[^\\r\\n]*/\\s*시행일:\\s*([^\\r\\n]+)");
    private static final Pattern TEXT_LAW_NAME = Pattern.compile("(?m)^법령명:\\s*(.+?)\\s*$");
    private static final Pattern TEXT_PROM_DATE = Pattern.compile("(?m)^공포일:\\s*(.+?)\\s*$");
    private static final Pattern TEXT_EFFECTIVE_DATE = Pattern.compile("(?m)^시행일:\\s*(.+?)\\s*$");
    private static final Pattern APPLICABLE_MST = Pattern.compile("\\(MST\\s+([0-9]+)\\)");
    private static final Pattern APPLICABLE_EFFECTIVE = Pattern.compile("\\[시행\\s+([0-9.]+)\\]");
    private static final Pattern DOTTED_DATE = Pattern.compile(
            "(\\d{4})\\s*[.\\-/]\\s*(\\d{1,2})\\s*[.\\-/]\\s*(\\d{1,2})");

    private final KoreanLawMcpClient client;
    private final KoreanLawMcpProperties properties;

    public LawEvidenceService(
            KoreanLawMcpClient client,
            KoreanLawMcpProperties properties
    ) {
        this.client = client;
        this.properties = properties;
    }

    public LawEvidence lookup(LawEvidenceRequest request) {
        if (request == null) throw new IllegalArgumentException("request is required");
        requirePinnedServer();

        KoreanLawMcpClient.ToolResult search = client.call(
                SEARCH_TOOL,
                Map.of("query", request.lawName(), "display", 50));
        LawHit hit = selectExactHit(search.text(), request.lawName());
        if (request.incidentDate() == null && !"현행".equals(hit.status())) {
            throw new KoreanLawMcpException(
                    "law MCP returned no current version for the statute");
        }

        String mst = hit.mst();
        String effectiveDate = parseDate(hit.effectiveDate());
        String applicabilityBasis = "CURRENT";
        if (request.incidentDate() != null) {
            Map<String, Object> applicableArgs = new LinkedHashMap<>();
            applicableArgs.put("mode", "applicable_law");
            applicableArgs.put("lawName", request.lawName());
            applicableArgs.put("date", request.incidentDate().toString());
            if (request.articleLocator() != null) {
                applicableArgs.put("jo", request.articleLocator());
            }
            KoreanLawMcpClient.ToolResult applicable = client.call(APPLICABLE_TOOL, applicableArgs);
            if (!normalize(applicable.text()).contains(normalize(request.lawName()))) {
                throw new KoreanLawMcpException(
                        "law MCP applicability response names a different statute");
            }
            String applicableMst = firstGroup(APPLICABLE_MST, applicable.text());
            String applicableDate = parseDate(firstGroup(APPLICABLE_EFFECTIVE, applicable.text()));
            if (applicableMst == null || applicableDate == null) {
                throw new KoreanLawMcpException(
                        "law MCP did not provide a usable historical MST/effective date");
            }
            LocalDate effective = parseIsoDate(applicableDate);
            if (effective.isAfter(request.incidentDate())) {
                throw new KoreanLawMcpException(
                        "law MCP selected a future version for the incident date");
            }
            mst = applicableMst;
            effectiveDate = applicableDate;
            applicabilityBasis = "INCIDENT_DATE";
        }

        Map<String, Object> textArgs = new LinkedHashMap<>();
        textArgs.put("mst", mst);
        if (request.articleLocator() != null) textArgs.put("jo", request.articleLocator());
        if (request.incidentDate() != null) textArgs.put("efYd", compactDate(effectiveDate));
        KoreanLawMcpClient.ToolResult textResult = fetchTextWithIdentifierFallback(
                textArgs, hit, request, effectiveDate);
        String lawName = requiredGroup(TEXT_LAW_NAME, textResult.text(), "law name");
        if (!sameLaw(lawName, request.lawName())) {
            throw new KoreanLawMcpException("law MCP returned a different statute");
        }

        String parsedEffective = parseDate(firstGroup(TEXT_EFFECTIVE_DATE, textResult.text()));
        if (parsedEffective != null) {
            if (effectiveDate != null && !effectiveDate.equals(parsedEffective)) {
                throw new KoreanLawMcpException(
                        "law MCP text version does not match selected effective date");
            }
            effectiveDate = parsedEffective;
        }
        if (effectiveDate == null) {
            throw new KoreanLawMcpException("law MCP response has no effective date");
        }
        LocalDate effective = parseIsoDate(effectiveDate);
        if (request.incidentDate() != null && effective.isAfter(request.incidentDate())) {
            throw new KoreanLawMcpException(
                    "law text effective date is after the incident date");
        }

        String text = textResult.text();
        if (request.articleLocator() != null
                && !normalize(text).contains(normalize(request.articleLocator()))) {
            throw new KoreanLawMcpException(
                    "law MCP response does not contain the requested article locator");
        }

        // v4.9.1 tool responses do not include an exact official URL. Keep the
        // navigational URL explicitly derived rather than presenting it as canonical.
        String derivedUrl = "https://www.law.go.kr/법령/"
                + URLEncoder.encode(lawName, StandardCharsets.UTF_8);
        String promDate = parseDate(firstGroup(TEXT_PROM_DATE, text));
        if (promDate == null) promDate = hit.promulgationDate();

        return new LawEvidence(
                lawName,
                hit.lawIdentifier(),
                mst,
                request.articleLocator() == null ? "FULL_TEXT" : request.articleLocator(),
                promDate == null ? "UNKNOWN" : promDate,
                effectiveDate,
                null,
                null,
                text,
                Instant.now(),
                properties.expectedServerVersion(),
                properties.pinnedCommit(),
                textResult.toolName(),
                "MCP_TOOL_TEXT",
                applicabilityBasis,
                "VALIDATED_PENDING_REVIEW",
                derivedUrl
        );
    }

    /**
     * The pinned API currently accepts the law ID reliably for detail reads,
     * while a search result also exposes MST. Try the selected MST first so
     * the provenance remains visible, then retry by the exact law ID only when
     * the server rejects that identifier. The retry is outside any database
     * transaction and is still checked against the selected effective date.
     */
    private KoreanLawMcpClient.ToolResult fetchTextWithIdentifierFallback(
            Map<String, Object> mstArguments,
            LawHit hit,
            LawEvidenceRequest request,
            String effectiveDate
    ) {
        try {
            return client.call(TEXT_TOOL, mstArguments);
        } catch (KoreanLawMcpException mstFailure) {
            Map<String, Object> lawIdArguments = new LinkedHashMap<>();
            lawIdArguments.put("lawId", hit.lawIdentifier());
            if (request.articleLocator() != null) {
                lawIdArguments.put("jo", request.articleLocator());
            }
            if (request.incidentDate() != null) {
                lawIdArguments.put("efYd", compactDate(effectiveDate));
            }
            try {
                return client.call(TEXT_TOOL, lawIdArguments);
            } catch (KoreanLawMcpException lawIdFailure) {
                lawIdFailure.addSuppressed(mstFailure);
                throw lawIdFailure;
            }
        }
    }

    private void requirePinnedServer() {
        if (!PINNED_REPOSITORY.equals(properties.pinnedRepository())
                || !PINNED_TAG.equals(properties.pinnedTag())
                || !PINNED_COMMIT.equals(properties.pinnedCommit())) {
            throw new KoreanLawMcpException(
                    "law MCP repository/tag/commit is not the pinned v4.9.1 source");
        }
        KoreanLawMcpClient.ServerMetadata metadata = client.metadata();
        if (!"korean-law".equals(metadata.name())) {
            throw new KoreanLawMcpException("unexpected law MCP server name");
        }
        if (!properties.expectedServerVersion().equals(metadata.version())) {
            throw new KoreanLawMcpException("law MCP server version mismatch");
        }
        if (!PROTOCOL_VERSION.equals(metadata.protocolVersion())) {
            throw new KoreanLawMcpException("law MCP protocol version mismatch");
        }
    }

    private static LawHit selectExactHit(String text, String requested) {
        List<LawHit> hits = parseHits(text);
        return hits.stream()
                .filter(hit -> sameLaw(hit.name(), requested))
                .sorted(Comparator.comparing((LawHit hit) -> !"현행".equals(hit.status()))
                        .thenComparing(LawHit::effectiveDate, Comparator.nullsLast(Comparator.reverseOrder())))
                .findFirst()
                .orElseThrow(() -> new KoreanLawMcpException(
                        "law MCP search did not return the requested statute"));
    }

    private static List<LawHit> parseHits(String text) {
        List<LawHit> result = new ArrayList<>();
        Matcher matcher = SEARCH_ENTRY.matcher(text);
        List<MatchBlock> blocks = new ArrayList<>();
        while (matcher.find()) blocks.add(new MatchBlock(matcher, text));
        for (int i = 0; i < blocks.size(); i++) {
            MatchBlock block = blocks.get(i);
            int end = i + 1 < blocks.size() ? blocks.get(i + 1).start() : text.length();
            String body = text.substring(block.end(), end);
            String lawId = firstGroup(LAW_ID, body);
            String mst = firstGroup(MST, body);
            if (lawId == null || mst == null) continue;
            result.add(new LawHit(
                    block.name(),
                    block.status(),
                    lawId,
                    mst,
                    parseDate(firstGroup(PROM_DATE, body)),
                    parseDate(firstGroup(EFFECTIVE_DATE, body))));
        }
        return result;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.replaceAll("\\s+", "")
                .replace("·", "").replace("ㆍ", "").replace("‧", "")
                .replace("•", "").replace("・", "");
    }

    private static boolean sameLaw(String left, String right) {
        String a = normalize(left);
        String b = normalize(right);
        return a.equals(b);
    }

    private static String firstGroup(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text == null ? "" : text);
        return matcher.find() ? matcher.group(1).trim() : null;
    }

    private static String requiredGroup(Pattern pattern, String text, String label) {
        String value = firstGroup(pattern, text);
        if (value == null || value.isBlank()) {
            throw new KoreanLawMcpException("law MCP response has no " + label);
        }
        return value;
    }

    private static String parseDate(String raw) {
        if (raw == null) return null;
        Matcher dotted = DOTTED_DATE.matcher(raw);
        if (dotted.find()) {
            try {
                return LocalDate.of(
                                Integer.parseInt(dotted.group(1)),
                                Integer.parseInt(dotted.group(2)),
                                Integer.parseInt(dotted.group(3)))
                        .format(DateTimeFormatter.BASIC_ISO_DATE);
            } catch (DateTimeException | NumberFormatException ignored) {
                return null;
            }
        }
        String digits = raw.replaceAll("\\D", "");
        if (!digits.matches("\\d{8}")) return null;
        try {
            LocalDate.parse(digits, DateTimeFormatter.BASIC_ISO_DATE);
            return digits;
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }

    private static LocalDate parseIsoDate(String ymd) {
        return LocalDate.parse(ymd, DateTimeFormatter.BASIC_ISO_DATE);
    }

    private static String compactDate(String ymd) {
        return ymd.replaceAll("\\D", "");
    }

    private record LawHit(
            String name,
            String status,
            String lawIdentifier,
            String mst,
            String promulgationDate,
            String effectiveDate
    ) {
    }

    private record MatchBlock(
            int start,
            int end,
            String name,
            String status
    ) {
        private MatchBlock(Matcher matcher, String ignored) {
            this(matcher.start(), matcher.end(), matcher.group(1).trim(), matcher.group(2));
        }
    }
}
