package com.financialhelper.source;

import org.jsoup.Jsoup;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/** Read-only observation. It neither stores proof nor authorizes a review or READY. */
@Service
public class OfficialSourceVerificationService {
    private final SourceReviewerService authority;
    private final SourceRegistryRepository registries;
    private final OfficialSourceDomainRepository domains;
    private final SourceDocumentRepository documents;
    private final SourceContentFetcher fetcher;
    private final SourceUrlPolicy urlPolicy;
    private final List<SourceDocumentParser> parsers;

    public OfficialSourceVerificationService(SourceReviewerService authority, SourceRegistryRepository registries,
            OfficialSourceDomainRepository domains, SourceDocumentRepository documents,
            @org.springframework.beans.factory.annotation.Qualifier("pinnedOfficialSourceContentFetcher") SourceContentFetcher fetcher,
            SourceUrlPolicy urlPolicy, List<SourceDocumentParser> parsers) {
        this.authority = authority;
        this.registries = registries;
        this.domains = domains;
        this.documents = documents;
        this.fetcher = fetcher;
        this.urlPolicy = urlPolicy;
        this.parsers = List.copyOf(parsers);
    }

    /** Never hold a DB transaction or lock during network I/O. */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public Result verify(String sourceKey, UUID documentId) {
        authority.requireReviewer();
        Result result = verifyRegistered(sourceKey, documentId, false);
        authority.requireReviewer();
        return result;
    }

    /** Internal scheduler path; callers must resolve an already registered source. */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    Result verifyRegistered(String sourceKey, UUID documentId, boolean textRegistration) {
        SourceRegistry registry = registries.findBySourceKeyAndEnabledTrue(sourceKey)
                .orElseThrow(() -> new SourceIngestionException("SOURCE_NOT_FOUND", "Enabled source was not found"));
        SourceIngestionData.Snapshot snapshot = SourceIngestionData.Snapshot.from(registry);
        if (!domains.existsByDomainAndEnabledTrue(snapshot.officialDomain())) {
            throw new SourceIngestionException("SOURCE_DOMAIN_DISABLED", "Official domain is not enabled");
        }
        urlPolicy.validate(snapshot.canonicalUrl(), snapshot.officialDomain());
        SourceDocument document = active(snapshot.sourceRegistryId(), documentId);
        int version = document.getDocumentVersion();
        String storedRaw = document.getRawSha256();
        String storedText = document.getContentSha256();
        String storedFinalUrl = document.getResolvedUrl();
        String officialDomain = snapshot.officialDomain().toLowerCase(Locale.ROOT);
        boolean legalVersionRequired = officialDomain.equals("law.go.kr") || officialDomain.endsWith(".law.go.kr");
        if (!snapshot.canonicalUrl().equals(document.getCanonicalUrl())
                || !SourceHashing.sha256(document.getOriginalContent()).equals(storedRaw)
                || !SourceHashing.sha256(document.getNormalizedContent()).equals(storedText)) {
            return failure(snapshot, documentId, version, storedRaw, legalVersionRequired,
                    "STORED_SOURCE_INTEGRITY_MISMATCH", null, null);
        }
        if (snapshot.acquisitionType() != SourceAcquisitionType.PDF
                && snapshot.acquisitionType() != SourceAcquisitionType.HTML) {
            return failure(snapshot, documentId, version, storedRaw, legalVersionRequired,
                    "SOURCE_TYPE_UNSUPPORTED", null, null);
        }
        SourceIngestionData.Fetched fetched = null;
        try {
            fetched = fetcher.fetch(snapshot);
            urlPolicy.validate(fetched.resolvedUrl(), snapshot.officialDomain());
            if (fetched.httpStatus() == null || fetched.httpStatus() != 200) {
                return failure(snapshot, documentId, version, storedRaw, legalVersionRequired,
                        "SOURCE_HTTP_STATUS_UNVERIFIED", fetched.httpStatus(), fetched.resolvedUrl());
            }
            if (snapshot.acquisitionType() == SourceAcquisitionType.HTML && isUnavailableHtml(fetched)) {
                return new Result(documentId, version, storedRaw, snapshot.canonicalUrl(), fetched.resolvedUrl(),
                        fetched.retrievedAt(), fetched.httpStatus(), true,
                        SourceHashing.sha256(fetched.originalContent()), null, "NOT_VERIFIED",
                        "SOURCE_ERROR_PAGE", legalVersionRequired, true, false);
            }
            SourceDocumentParser parser = parsers.stream().filter(p -> p.supports(snapshot.acquisitionType()))
                    .findFirst().orElseThrow(() -> new SourceIngestionException(
                            "SOURCE_TYPE_UNSUPPORTED", "Source parser is unavailable"));
            SourceIngestionData.Parsed parsed = parser.parse(snapshot, fetched);
            OfficialInformationClaimSelector.validateFetchedCandidate(snapshot, parsed);
            String observedText = parsed.contentSha256();
            if (textRegistration && snapshot.acquisitionType() == SourceAcquisitionType.HTML) {
                try (var input = new java.io.ByteArrayInputStream(fetched.originalContent())) {
                    String html = Jsoup.parse(input, null, fetched.resolvedUrl()).outerHtml();
                    observedText = SourceHashing.sha256(TextOnlySourceRegistrationService.extractRegistrationText(registry, html).content());
                } catch (java.io.IOException invalid) {
                    throw new SourceIngestionException("SOURCE_HTML_PARSE_FAILED", "Could not inspect official HTML", invalid);
                }
            }
            SourceRegistry current = registries.findBySourceKeyAndEnabledTrue(sourceKey)
                    .orElseThrow(() -> new SourceIngestionException("SOURCE_REGISTRY_CHANGED", "Source changed"));
            if (!snapshot.equals(SourceIngestionData.Snapshot.from(current))
                    || !domains.existsByDomainAndEnabledTrue(snapshot.officialDomain())) {
                return failure(snapshot, documentId, version, storedRaw, legalVersionRequired,
                        "SOURCE_REGISTRY_CHANGED", fetched.httpStatus(), fetched.resolvedUrl());
            }
            SourceDocument after = active(snapshot.sourceRegistryId(), documentId);
            if (after.getDocumentVersion() != version || !storedRaw.equals(after.getRawSha256())
                    || !storedText.equals(after.getContentSha256())
                    || !Objects.equals(storedFinalUrl, after.getResolvedUrl())
                    || !snapshot.canonicalUrl().equals(after.getCanonicalUrl())
                    || !storedRaw.equals(SourceHashing.sha256(after.getOriginalContent()))
                    || !storedText.equals(SourceHashing.sha256(after.getNormalizedContent()))) {
                return failure(snapshot, documentId, version, storedRaw, legalVersionRequired,
                        "SOURCE_DOCUMENT_CHANGED", fetched.httpStatus(), fetched.resolvedUrl());
            }
            String result;
            if (!Objects.equals(fetched.resolvedUrl(), storedFinalUrl)) result = "FINAL_URL_CHANGED_REVIEW_REQUIRED";
            else if (storedRaw.equals(parsed.rawSha256())) result = "RAW_BYTES_MATCH";
            else if (snapshot.acquisitionType() == SourceAcquisitionType.HTML
                    && storedText.equals(observedText)) result = "HTML_NORMALIZED_MATCH_RAW_CHANGED_REVIEW_REQUIRED";
            else result = "CONTENT_CHANGED_REVIEW_REQUIRED";
            boolean identityVerified = "RAW_BYTES_MATCH".equals(result)
                    && (OfficialInformationClaimSelector.supports(sourceKey)
                    || snapshot.acquisitionType() == SourceAcquisitionType.PDF
                    && VerifiedPdfMetadata.forFetched(snapshot, fetched, parsed.rawSha256()).isPresent());
            return new Result(documentId, version, storedRaw, snapshot.canonicalUrl(), fetched.resolvedUrl(),
                    fetched.retrievedAt(), fetched.httpStatus(), true, parsed.rawSha256(), observedText,
                    snapshot.acquisitionType() == SourceAcquisitionType.PDF ? "PDF_RAW_SHA256" : "HTML_RAW_AND_NORMALIZED_SHA256",
                    result, legalVersionRequired, true, identityVerified);
        } catch (SourceIngestionException failure) {
            if (fetched != null) {
                return new Result(documentId, version, storedRaw, snapshot.canonicalUrl(), fetched.resolvedUrl(),
                        fetched.retrievedAt(), fetched.httpStatus(), true, SourceHashing.sha256(fetched.originalContent()),
                        null, "NOT_VERIFIED", failure.getCode(), legalVersionRequired, true, false);
            }
            return failure(snapshot, documentId, version, storedRaw, legalVersionRequired, failure.getCode(),
                    failure.getHttpStatus(), failure.getResponseUrl());
        }
    }

    private SourceDocument active(UUID registryId, UUID documentId) {
        SourceDocument document = documents.findBySourceRegistry_IdAndStatus(registryId, SourceDocumentStatus.ACTIVE)
                .orElseThrow(() -> new SourceIngestionException("SOURCE_DOCUMENT_CHANGED", "Active document not found"));
        if (!document.getId().equals(documentId)) {
            throw new SourceIngestionException("SOURCE_DOCUMENT_CHANGED", "Active document changed");
        }
        return document;
    }

    static boolean isUnavailableHtml(SourceIngestionData.Fetched fetched) {
        org.jsoup.nodes.Document html;
        try (var bytes = new java.io.ByteArrayInputStream(fetched.originalContent())) {
            html = Jsoup.parse(bytes, null, fetched.resolvedUrl());
        } catch (java.io.IOException failure) {
            throw new SourceIngestionException("SOURCE_HTML_PARSE_FAILED", "Could not inspect official HTML", failure);
        }
        String title = html.title().toLowerCase(Locale.ROOT);
        String url = java.net.URI.create(fetched.resolvedUrl()).getPath().toLowerCase(Locale.ROOT);
        return html.selectFirst("input[type=password], meta[http-equiv=refresh]") != null || title.contains("시스템 점검")
                || title.contains("서비스 점검") || title.contains("접근 오류")
                || title.contains("로그인") || title.contains("접근 제한") || title.contains("404")
                || title.contains("maintenance") || title.contains("login") || title.startsWith("error")
                || url.contains("/sorry/") || url.matches(".*/(?:login|signin)(?:[./].*)?");
    }

    private static Result failure(SourceIngestionData.Snapshot source, UUID id, int version, String raw,
            boolean law, String reason, Integer status, String finalUrl) {
        return new Result(id, version, raw, source.canonicalUrl(), finalUrl, OffsetDateTime.now(ZoneOffset.UTC),
                status, status != null && status >= 200 && status < 300, null, null, "NOT_VERIFIED", reason, law, true, false);
    }

    /** supersedingGuidanceUnknown is always true; legal applicability is never inferred. */
    public record Result(UUID sourceDocumentId, int documentVersion, String storedRawSha256,
                         String officialUrl, String finalUrl, OffsetDateTime checkedAt, Integer httpStatus,
                         boolean downloadSucceeded, String observedRawSha256, String observedContentSha256,
                         String method, String result, boolean legalVersionVerificationRequired,
                         boolean supersedingGuidanceUnknown, boolean sourceIdentityVerified) {}
}
