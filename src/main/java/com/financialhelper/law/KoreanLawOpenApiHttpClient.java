package com.financialhelper.law;

import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** JSON client for the official lawSearch/lawService Open API endpoints. */
@Component
public class KoreanLawOpenApiHttpClient implements KoreanLawOpenApiClient {

    private static final String HOST = "https://www.law.go.kr";
    private static final String JSON = "JSON";
    private static final DateTimeFormatter BASIC = DateTimeFormatter.BASIC_ISO_DATE;

    private final KoreanLawOpenApiProperties properties;
    private final JsonMapper jsonMapper;
    private final HttpClient httpClient;

    public KoreanLawOpenApiHttpClient(
            KoreanLawOpenApiProperties properties,
            JsonMapper jsonMapper
    ) {
        this.properties = properties;
        this.jsonMapper = jsonMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.connectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    @Override
    public List<LawVersion> searchLaw(String lawName) {
        requireEnabled();
        if (lawName == null || lawName.isBlank()) {
            throw new IllegalArgumentException("lawName is required");
        }

        List<LawVersion> versions = new ArrayList<>();
        int page = 1;
        int total = Integer.MAX_VALUE;
        while (versions.size() < total && page <= 20) {
            JsonNode search = request("lawSearch.do", List.of(
                    param("target", "eflaw"),
                    param("type", JSON),
                    param("query", lawName),
                    param("nw", "1,3"),
                    param("display", "100"),
                    param("page", Integer.toString(page)),
                    param("sort", "efdes")
            ));
            JsonNode lawSearch = requiredObject(search, "LawSearch");
            total = parseNonNegativeInt(lawSearch.path("totalCnt"), "totalCnt");
            JsonNode rows = lawSearch.path("law");
            if (rows.isMissingNode() || rows.isNull()) {
                break;
            }
            int before = versions.size();
            for (JsonNode row : asArray(rows)) {
                String name = text(row, "법령명한글");
                if (!sameLaw(name, lawName)) {
                    continue;
                }
                LocalDate effective = parseDate(text(row, "시행일자"), "시행일자");
                LocalDate promulgation = parseDate(text(row, "공포일자"), "공포일자");
                String mst = text(row, "법령일련번호");
                String id = text(row, "법령ID");
                boolean current = "현행".equals(text(row, "현행연혁코드"));
                String derivedUrl = sanitizeDetailLink(row.path("법령상세링크").asText(null), mst, effective);
                versions.add(new LawVersion(name, id, mst, promulgation, effective, current, derivedUrl));
            }
            if (versions.size() == before && rows.size() == 0) {
                break;
            }
            page++;
            if (rows.size() == 0) {
                break;
            }
        }
        if (versions.isEmpty()) {
            throw new KoreanLawOpenApiException("Open API search returned no exact law version");
        }
        return versions.stream()
                .sorted(Comparator.comparing(LawVersion::effectiveDate).reversed())
                .toList();
    }

    @Override
    public LawDocument getLawText(LawVersion version, String articleLocator) {
        requireEnabled();
        if (version == null) {
            throw new IllegalArgumentException("version is required");
        }
        String jo = articleLocator == null ? null : toArticleCode(articleLocator);
        List<String> params = new ArrayList<>();
        // The eflaw body endpoint is required for a specific effective-date version;
        // target=law resolves the current body and can disagree with historical metadata.
        params.add(param("target", "eflaw"));
        params.add(param("type", JSON));
        params.add(param("MST", version.mst()));
        params.add(param("efYd", version.effectiveDate().format(BASIC)));
        if (jo != null) params.add(param("JO", jo));
        JsonNode law = requiredObject(request("lawService.do", params), "법령");
        JsonNode basic = requiredObject(law, "기본정보");
        String name = text(basic, "법령명_한글");
        String lawId = text(basic, "법령ID");
        LocalDate promulgation = parseDate(text(basic, "공포일자"), "공포일자");
        LocalDate effective = parseDate(text(basic, "시행일자"), "시행일자");
        if (!sameLaw(name, version.statuteName()) || !lawId.equals(version.lawIdentifier())) {
            throw new KoreanLawOpenApiException("Open API law identity does not match selected version");
        }
        if (!effective.equals(version.effectiveDate()) || !promulgation.equals(version.promulgationDate())) {
            throw new KoreanLawOpenApiException("Open API law dates do not match selected version");
        }
        JsonNode articleRoot = law.path("조문").path("조문단위");
        List<JsonNode> articles = asArray(articleRoot);
        if (articles.isEmpty()) {
            throw new KoreanLawOpenApiException("Open API law response has no article");
        }
        String requested = articleLocator == null ? null : normalizeLocator(articleLocator);
        List<JsonNode> matching = articles.stream()
                .filter(article -> requested == null || requested.equals(articleLocator(article)))
                .toList();
        if (matching.isEmpty()) {
            throw new KoreanLawOpenApiException("Open API response does not contain requested article");
        }
        String resolvedLocator = requested == null ? "FULL_TEXT" : articleLocator(matching.getFirst());
        String body = matching.stream()
                .map(KoreanLawOpenApiHttpClient::flattenArticle)
                .filter(text -> !text.isBlank())
                .reduce((left, right) -> left + "\n" + right)
                .orElse("");
        if (body.isBlank()) {
            throw new KoreanLawOpenApiException("Open API article body is empty");
        }
        return new LawDocument(
                name,
                lawId,
                version.mst(),
                resolvedLocator,
                body,
                promulgation,
                effective,
                version.derivedUrl()
        );
    }

    private JsonNode request(String endpoint, List<String> params) {
        StringBuilder query = new StringBuilder();
        append(query, "OC", properties.lawOc());
        for (String param : params) {
            int split = param.indexOf('=');
            append(query, param.substring(0, split), param.substring(split + 1));
        }
        URI uri = URI.create(trimSlash(properties.baseUrl()) + "/" + endpoint + "?" + query);
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(properties.requestTimeout())
                .header("Accept", "application/json")
                .GET()
                .build();
        try {
            HttpResponse<String> response = httpClient.send(
                    request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new KoreanLawOpenApiException("Korean Law Open API HTTP error " + response.statusCode());
            }
            if (response.body() == null || response.body().isBlank()) {
                throw new KoreanLawOpenApiException("Korean Law Open API returned an empty response");
            }
            try {
                return jsonMapper.readTree(response.body());
            } catch (JacksonException exception) {
                throw new KoreanLawOpenApiException("Korean Law Open API returned malformed JSON", exception);
            }
        } catch (IOException exception) {
            throw new KoreanLawOpenApiException("Korean Law Open API request failed", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new KoreanLawOpenApiException("Korean Law Open API request interrupted", exception);
        }
    }

    private void requireEnabled() {
        if (!properties.enabled()) {
            throw new KoreanLawOpenApiException("Korean Law Open API client is disabled");
        }
        if (properties.lawOc() == null || properties.lawOc().isBlank()) {
            throw new KoreanLawOpenApiException("LAW_OC is required for Korean Law Open API");
        }
    }

    private static JsonNode requiredObject(JsonNode parent, String field) {
        JsonNode value = parent.path(field);
        if (!value.isObject()) {
            throw new KoreanLawOpenApiException("Korean Law Open API response is missing " + field);
        }
        return value;
    }

    private static List<JsonNode> asArray(JsonNode node) {
        if (node.isArray()) {
            List<JsonNode> values = new ArrayList<>();
            node.forEach(values::add);
            return values;
        }
        if (node.isObject()) return List.of(node);
        return List.of();
    }

    private static String text(JsonNode parent, String field) {
        String value = parent.path(field).asText("").trim();
        if (value.isBlank()) {
            throw new KoreanLawOpenApiException("Korean Law Open API response is missing " + field);
        }
        return value;
    }

    private static int parseNonNegativeInt(JsonNode value, String field) {
        try {
            int parsed = Integer.parseInt(value.asText());
            if (parsed < 0) throw new NumberFormatException();
            return parsed;
        } catch (NumberFormatException exception) {
            throw new KoreanLawOpenApiException("Korean Law Open API has invalid " + field, exception);
        }
    }

    private static LocalDate parseDate(String value, String field) {
        try {
            return LocalDate.parse(value.replaceAll("\\D", ""), BASIC);
        } catch (DateTimeParseException exception) {
            throw new KoreanLawOpenApiException("Korean Law Open API has invalid " + field, exception);
        }
    }

    private static String articleLocator(JsonNode article) {
        String number = article.path("조문번호").asText("").trim();
        String branch = article.path("조문가지번호").asText("").trim();
        if (number.isBlank()) throw new KoreanLawOpenApiException("Open API article number is missing");
        if (!branch.isBlank() && !"0".equals(branch)) return "제" + number + "조의" + branch;
        return "제" + number + "조";
    }

    private static String flattenArticle(JsonNode article) {
        StringBuilder result = new StringBuilder();
        appendText(result, article, "조문내용");
        appendChildren(result, article.path("항"), "항내용");
        return result.toString().trim();
    }

    private static void appendChildren(StringBuilder result, JsonNode node, String contentField) {
        for (JsonNode child : asArray(node)) {
            appendText(result, child, contentField);
            appendChildren(result, child.path("호"), "호내용");
            appendChildren(result, child.path("목"), "목내용");
        }
    }

    private static void appendText(StringBuilder result, JsonNode node, String field) {
        String value = node.path(field).asText("").trim();
        if (!value.isBlank()) {
            if (result.length() > 0) result.append('\n');
            result.append(value);
        }
    }

    private static String normalizeLocator(String value) {
        return value.replaceAll("\\s+", "");
    }

    private static String toArticleCode(String locator) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("제(\\d+)조(?:의(\\d+))?")
                .matcher(normalizeLocator(locator));
        if (!matcher.matches()) {
            throw new KoreanLawOpenApiException("unsupported article locator");
        }
        int article = Integer.parseInt(matcher.group(1));
        int branch = matcher.group(2) == null ? 0 : Integer.parseInt(matcher.group(2));
        if (article > 9999 || branch > 99) throw new KoreanLawOpenApiException("article locator is out of range");
        return "%04d%02d".formatted(article, branch);
    }

    private static String sanitizeDetailLink(String raw, String mst, LocalDate effectiveDate) {
        if (raw == null || raw.isBlank()) {
            return HOST + "/DRF/lawService.do?target=eflaw&MST=" +
                    URLEncoder.encode(mst, StandardCharsets.UTF_8) + "&efYd="
                    + effectiveDate.format(BASIC) + "&type=JSON";
        }
        URI uri = URI.create(raw.startsWith("http") ? raw : HOST + (raw.startsWith("/") ? raw : "/" + raw));
        StringBuilder result = new StringBuilder(HOST).append(uri.getRawPath());
        if (uri.getRawQuery() != null && !uri.getRawQuery().isBlank()) {
            List<String> kept = java.util.Arrays.stream(uri.getRawQuery().split("&"))
                    .filter(pair -> !pair.regionMatches(true, 0, "OC=", 0, 3))
                    .toList();
            if (!kept.isEmpty()) result.append('?').append(String.join("&", kept));
        }
        return result.toString();
    }

    private static String param(String name, String value) {
        return name + "=" + value;
    }

    private static void append(StringBuilder query, String name, String value) {
        if (value == null || value.isBlank()) {
            throw new KoreanLawOpenApiException(name + " is required");
        }
        if (query.length() > 0) query.append('&');
        query.append(URLEncoder.encode(name, StandardCharsets.UTF_8));
        query.append('=').append(URLEncoder.encode(value, StandardCharsets.UTF_8));
    }

    private static String trimSlash(String value) {
        return value.replaceAll("/+$", "");
    }

    private static boolean sameLaw(String left, String right) {
        return left.replaceAll("\\s+", "").equals(right.replaceAll("\\s+", ""));
    }
}
