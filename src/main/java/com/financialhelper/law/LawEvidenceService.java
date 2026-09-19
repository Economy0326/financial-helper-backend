package com.financialhelper.law;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;

/**
 * Validates direct Korean Law Open API responses for later human review.
 * This service never creates or changes a ProcedureVersion or action plan.
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

        if (!sameLaw(document.statuteName(), request.lawName())
                || !document.lawIdentifier().equals(selected.lawIdentifier())
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
        if (request.incidentDate() == null) {
            return exact.stream()
                    .filter(KoreanLawOpenApiClient.LawVersion::current)
                    .max(Comparator.comparing(KoreanLawOpenApiClient.LawVersion::effectiveDate))
                    .orElseThrow(() -> new KoreanLawOpenApiException(
                            "Open API search returned no current statute version"));
        }
        return exact.stream()
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
