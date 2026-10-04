package com.financialhelper.law;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;

/**
 * 추후 사람의 검토를 위해 Direct Korean Law Open API 응답을 검증한다.
 * 이 service는 ProcedureVersion이나 action plan을 생성하거나 변경하지 않는다.
 */
@Service
public class LawEvidenceService {

    private static final Logger log = LoggerFactory.getLogger(LawEvidenceService.class);
    public static final String ACQUISITION_KIND = "DIRECT_KOREAN_LAW_OPEN_API";
    private static final String TOOL_NAME = "lawService.do";
    private static final DateTimeFormatter BASIC = DateTimeFormatter.BASIC_ISO_DATE;

    private final KoreanLawOpenApiClient client;
    private final KoreanLawOpenApiProperties properties;

    public LawEvidenceService(
            KoreanLawOpenApiClient client,
            KoreanLawOpenApiProperties properties
    ) {
        this.client = client;
        this.properties = properties;
    }

    public LawEvidence lookup(LawEvidenceRequest request) {
        if (request == null) throw new IllegalArgumentException("request is required");
        List<KoreanLawOpenApiClient.LawVersion> versions = client.searchLaw(request.lawName());
        KoreanLawOpenApiClient.LawVersion selected = selectVersion(versions, request);
        log.info("Law evidence version selected lawId={}, mst={}, article={}, effectiveDate={}",
                selected.lawIdentifier(), selected.mst(), request.articleLocator(), selected.effectiveDate());
        KoreanLawOpenApiClient.LawDocument document = client.getLawText(selected, request.articleLocator());

        // The search phase has already established that the requested name belongs
        // to this lawIdentifier family.  A later official rename may change the
        // document title while preserving the law ID, so title equality here would
        // incorrectly reject the selected, version-pinned document.
        if (!document.lawIdentifier().equals(selected.lawIdentifier())
                || !document.mst().equals(selected.mst())
                || !document.effectiveDate().equals(selected.effectiveDate())
                || !document.promulgationDate().equals(selected.promulgationDate())) {
            throw new KoreanLawOpenApiException("Open API document identity/version mismatch");
        }
        if (request.articleLocator() != null
                && !normalize(document.articleLocator()).equals(normalize(request.articleLocator()))) {
            throw new KoreanLawOpenApiException("Open API response does not contain requested article");
        }
        if (request.incidentDate() != null
                && selected.effectiveDate().isAfter(request.incidentDate())) {
            throw new KoreanLawOpenApiException("Open API selected a future law version");
        }

        return new LawEvidence(
                document.statuteName(),
                document.lawIdentifier(),
                document.mst(),
                document.articleLocator(),
                document.promulgationDate().format(BASIC),
                document.effectiveDate().format(BASIC),
                null,
                null,
                document.articleText(),
                java.time.Instant.now(),
                properties.providerVersion(),
                properties.providerRevision(),
                TOOL_NAME,
                ACQUISITION_KIND,
                request.incidentDate() == null ? "CURRENT" : "INCIDENT_DATE",
                "VALIDATED_PENDING_REVIEW",
                document.derivedUrl()
        );
    }

    static KoreanLawOpenApiClient.LawVersion selectVersion(
            List<KoreanLawOpenApiClient.LawVersion> versions,
            LawEvidenceRequest request
    ) {
        if (versions == null || versions.isEmpty()) {
            throw new KoreanLawOpenApiException("Open API search returned no law versions");
        }
        List<KoreanLawOpenApiClient.LawVersion> exact = versions.stream()
                .filter(version -> sameLaw(version.statuteName(), request.lawName()))
                .toList();
        if (exact.isEmpty()) {
            throw new KoreanLawOpenApiException("Open API search returned a different statute");
        }
        // 법령명 개정은 같은 법령ID의 시행 version을 검색 결과에서 다른 이름으로
        // 돌려준다. 요청한 과거 명칭으로 찾은 family의 ID를 기준으로 모든 version을
        // 포함해야 incident-date selector가 이름 변경 직전 version에 고정되지 않는다.
        java.util.Set<String> lawIdentifiers = exact.stream()
                .map(KoreanLawOpenApiClient.LawVersion::lawIdentifier)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        List<KoreanLawOpenApiClient.LawVersion> family = versions.stream()
                .filter(version -> lawIdentifiers.contains(version.lawIdentifier()))
                .toList();
        if (request.incidentDate() == null) {
            return family.stream()
                    .filter(KoreanLawOpenApiClient.LawVersion::current)
                    .max(Comparator.comparing(KoreanLawOpenApiClient.LawVersion::effectiveDate))
                    .orElseThrow(() -> new KoreanLawOpenApiException(
                            "Open API search returned no current statute version"));
        }
        return family.stream()
                .filter(version -> !version.effectiveDate().isAfter(request.incidentDate()))
                .max(Comparator.comparing(KoreanLawOpenApiClient.LawVersion::effectiveDate))
                .orElseThrow(() -> new KoreanLawOpenApiException(
                        "Open API has no version applicable to incident date"));
    }

    private static boolean sameLaw(String left, String right) {
        return normalize(left).equals(normalize(right));
    }

    private static String normalize(String value) {
        return value == null ? "" : value.replaceAll("\\s+", "")
                .replace("·", "").replace("ㆍ", "").replace("‧", "")
                .replace("•", "").replace("・", "");
    }
}
