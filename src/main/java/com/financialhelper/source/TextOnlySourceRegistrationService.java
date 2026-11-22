package com.financialhelper.source;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

/** Registers reviewer-supplied HTML as text only; no network access or implicit publication approval. */
@Service
public class TextOnlySourceRegistrationService {
    private final SourceRegistryRepository registries;
    private final SourceDocumentRepository documents;
    private final SourceDocumentWriter writer;
    private final DocumentClaimReviewerService reviewer;
    private final JdbcTemplate jdbc;
    private final OfficialSourceDomainRepository domains;
    private final SourceContentFetcher fetcher;
    private final HtmlSourceDocumentParser parser;
    private final SourceUrlPolicy urlPolicy;

    public TextOnlySourceRegistrationService(SourceRegistryRepository registries, SourceDocumentRepository documents,
            SourceDocumentWriter writer, DocumentClaimReviewerService reviewer, JdbcTemplate jdbc,
            OfficialSourceDomainRepository domains,
            @org.springframework.beans.factory.annotation.Qualifier("pinnedOfficialSourceContentFetcher") SourceContentFetcher fetcher,
            HtmlSourceDocumentParser parser, SourceUrlPolicy urlPolicy) {
        this.registries = registries; this.documents = documents; this.writer = writer; this.reviewer = reviewer; this.jdbc = jdbc;
        this.domains = domains; this.fetcher = fetcher; this.parser = parser; this.urlPolicy = urlPolicy;
    }

    /** Registry metadata only: does not fetch, store content, or decide usage rights. */
    @Transactional
    public RegistryCandidate registerCandidate(String domain, String url) {
        SourceReviewerService.requireReviewer(jdbc);
        if (domain == null || url == null || url.length() > 2000) throw failure("SOURCE_TEXT_REGISTRY_INVALID");
        String normalizedDomain = domain.strip().toLowerCase(java.util.Locale.ROOT);
        var organizations = jdbc.query("SELECT organization_name FROM official_source_domain WHERE domain=? AND enabled=true",
                (rs, n) -> rs.getString(1), normalizedDomain);
        if (organizations.size() != 1) throw failure("SOURCE_DOMAIN_DISABLED");
        String canonical;
        try { canonical = urlPolicy.normalizeRegisteredUrl(url.strip(), normalizedDomain).toASCIIString(); }
        catch (SourceIngestionException invalid) { throw failure(invalid.getCode()); }
        if ("korea.kr".equals(normalizedDomain) && !KoreaPolicyNews.allowed(java.net.URI.create(canonical)))
            throw failure("SOURCE_URL_NOT_ALLOWED");
        var rows = jdbc.query("SELECT id,enabled,acquisition_type,source_key,official_domain,canonical_url FROM source_registry WHERE lower(official_domain)=?",
                (rs, n) -> new RegistryRow(rs.getObject(1, UUID.class), rs.getBoolean(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6)), normalizedDomain)
                .stream().filter(row -> {
                    try { return canonical.equals(urlPolicy.normalizeRegisteredUrl(row.url(), normalizedDomain).toASCIIString()); }
                    catch (SourceIngestionException invalid) { return false; }
                }).toList();
        if (rows.isEmpty()) {
            UUID id = UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO source_registry(id,source_key,organization_name,official_domain,canonical_url,acquisition_type)
                    VALUES (?,?,?,?,?,'HTML') ON CONFLICT (canonical_url) DO NOTHING
                    """, id, "review-document-" + id.toString().replace("-", ""), organizations.getFirst(), normalizedDomain, canonical);
            rows = jdbc.query("SELECT id,enabled,acquisition_type,source_key,official_domain,canonical_url FROM source_registry WHERE canonical_url=?",
                    (rs, n) -> new RegistryRow(rs.getObject(1, UUID.class), rs.getBoolean(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6)), canonical);
        }
        if (rows.size() != 1 || !rows.getFirst().enabled() || !"HTML".equals(rows.getFirst().type())
                || !normalizedDomain.equalsIgnoreCase(rows.getFirst().domain())
                || LegacyHtmlSourcePolicy.allowsLegacyPath(rows.getFirst().id(), rows.getFirst().key())) throw failure("SOURCE_TEXT_REGISTRY_INVALID");
        var source = rows.getFirst();
        var active = documents.findBySourceRegistry_IdAndStatus(source.id(), SourceDocumentStatus.ACTIVE).orElse(null);
        return new RegistryCandidate(source.id(), canonical, organizations.getFirst(), active == null ? null : active.getId());
    }

    private record RegistryRow(UUID id, boolean enabled, String type, String key, String domain, String url) {}
    public record RegistryCandidate(UUID registryId, String officialUrl, String organization, UUID documentId) {}

    /** Fetch only a registered URL, without holding a database transaction over HTTPS. */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public FetchedRegistration fetchRegistered(UUID registryId, boolean storageApproved, boolean privacyReviewed, boolean thirdPartyReviewed) {
        SourceReviewerService.requireReviewer(jdbc);
        if (!storageApproved || !privacyReviewed || !thirdPartyReviewed) throw failure("SOURCE_TEXT_REVIEW_REQUIRED");
        return fetchCandidate(registryId);
    }

    /** No document, HTML, claim, or rights decision is persisted or returned. */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public CandidateMetadata previewCandidate(UUID registryId) {
        SourceReviewerService.requireReviewer(jdbc);
        var candidate = fetchCandidate(registryId);
        var current = registries.findById(registryId).orElseThrow(() -> failure("SOURCE_NOT_FOUND"));
        if (!current.isEnabled() || !candidate.snapshot().equals(SourceIngestionData.Snapshot.from(current))
                || !domains.existsByDomainAndEnabledTrue(current.getOfficialDomain())) throw failure("SOURCE_REGISTRY_CHANGED");
        SourceReviewerService.requireReviewer(jdbc);
        var dom = Jsoup.parse(candidate.html());
        boolean typeOne = hasTypeOneTextLicense(candidate.snapshot().officialDomain(), dom);
        return new CandidateMetadata(candidate.title(), candidate.publishedAt(), candidate.snapshot().canonicalUrl(),
                "NOT_REVIEWED", typeOne ? "PUBLIC_NURI_TYPE_1_TEXT" : "UNVERIFIED",
                typeOne ? policyPreviewHash(candidate) : null);
    }

    private FetchedRegistration fetchCandidate(UUID registryId) {
        var registry = registries.findById(registryId).orElseThrow(() -> failure("SOURCE_NOT_FOUND"));
        if (!registry.isEnabled() || registry.getAcquisitionType() != SourceAcquisitionType.HTML
                || !domains.existsByDomainAndEnabledTrue(registry.getOfficialDomain())) throw failure("SOURCE_TEXT_REGISTRY_INVALID");
        var snapshot = SourceIngestionData.Snapshot.from(registry);
        if ("korea.kr".equalsIgnoreCase(snapshot.officialDomain()) && !KoreaPolicyNews.allowed(java.net.URI.create(snapshot.canonicalUrl())))
            throw failure("SOURCE_URL_NOT_ALLOWED");
        var fetched = fetcher.fetch(snapshot);
        if (fetched.httpStatus() == null || fetched.httpStatus() != 200
                || !snapshot.canonicalUrl().equals(fetched.resolvedUrl())) throw failure("SOURCE_URL_REVIEW_REQUIRED");
        if (OfficialSourceVerificationService.isUnavailableHtml(fetched)) throw failure("SOURCE_ERROR_PAGE");
        var parsed = parser.parse(snapshot, fetched);
        try {
            var dom = Jsoup.parse(new java.io.ByteArrayInputStream(fetched.originalContent()), null, fetched.resolvedUrl());
            return new FetchedRegistration(snapshot, dom.outerHtml(), parsed.title(), parsed.publishedAt());
        } catch (java.io.IOException invalid) { throw failure("SOURCE_TEXT_EXTRACTION_INCOMPLETE"); }
    }

    /** Verify registry identity under lock before saving or preparing any claims. */
    @Transactional
    public Result registerFetched(FetchedRegistration fetched, String basisUrl, String reason,
            boolean privacyReviewed, boolean thirdPartyReviewed, boolean storageApproved) {
        var registry = registries.findForUpdateById(fetched.snapshot().sourceRegistryId()).orElseThrow(() -> failure("SOURCE_NOT_FOUND"));
        if (!registry.isEnabled() || !fetched.snapshot().equals(SourceIngestionData.Snapshot.from(registry))
                || !domains.existsByDomainAndEnabledTrue(registry.getOfficialDomain())) throw failure("SOURCE_REGISTRY_CHANGED");
        var result = register(registry.getId(), fetched.html(), fetched.title(), fetched.publishedAt(), basisUrl,
                reason, privacyReviewed, thirdPartyReviewed, storageApproved);
        var candidates = reviewer.claims(result.documentId(), result.storedTextSha256());
        if (candidates.isEmpty() || candidates.stream().allMatch(DocumentClaimReviewerService.Claim::hold))
            throw failure("SOURCE_TEXT_NO_REVIEWABLE_CLAIMS");
        return new Result(result.documentId(), result.documentVersion(), result.outcome(), result.storedTextSha256(),
                "FETCHED_FROM_REGISTERED_SOURCE", result.contentReview());
    }

    public record FetchedRegistration(SourceIngestionData.Snapshot snapshot, String html, String title, LocalDate publishedAt) {}
    public record CandidateMetadata(String title, LocalDate publishedAt, String officialUrl, String usageStatus, String licenseType, String previewSha256) {}

    static String policyPreviewHash(FetchedRegistration fetched) {
        var dom = Jsoup.parse(fetched.html());
        var root = dom.selectFirst(KoreaPolicyNews.select(dom));
        var license = dom.selectFirst(".kogl .guide .type");
        if (root == null || license == null) throw new IllegalArgumentException("preview unavailable");
        return SourceHashing.sha256(String.join("\n", fetched.snapshot().canonicalUrl(), fetched.title(),
                String.valueOf(fetched.publishedAt()), license.text(), root.outerHtml()));
    }

    static boolean hasTypeOneTextLicense(String domain, org.jsoup.nodes.Document dom) {
        var marks = dom.select(".kogl .guide .type");
        String license = marks.size() == 1 ? marks.first().text().replaceAll("\\s+", " ") : "";
        return "korea.kr".equalsIgnoreCase(domain) && license.contains("텍스트에 한하여")
                && license.contains("공공누리 제1유형") && license.contains("출처표시");
    }

    /** Reviewer-only, in-memory inspection; this does not create a document or rights decision. */
    public StructuredTablePreview.Result previewTables(UUID registryId, String html) {
        SourceReviewerService.requireReviewer(jdbc);
        if (html == null || html.isBlank() || html.length() > 2_000_000) throw failure("SOURCE_TEXT_REVIEW_REQUIRED");
        var registry = registries.findById(registryId).orElseThrow(() -> failure("SOURCE_NOT_FOUND"));
        if (!registry.isEnabled() || registry.getAcquisitionType() != SourceAcquisitionType.HTML) throw failure("SOURCE_TEXT_REGISTRY_INVALID");
        var dom = Jsoup.parse(html);
        String selector = registry.getContentSelector();
        if ("fsc.go.kr".equalsIgnoreCase(registry.getOfficialDomain())) {
            try { selector = FscHtmlBodySelector.select(dom); }
            catch (IllegalArgumentException ex) { throw failure("SOURCE_TEXT_SELECTOR_AMBIGUOUS"); }
        }
        if ("korea.kr".equalsIgnoreCase(registry.getOfficialDomain())) {
            try { selector = KoreaPolicyNews.select(dom); }
            catch (IllegalArgumentException ex) { throw failure("SOURCE_TEXT_SELECTOR_AMBIGUOUS"); }
        }
        if (selector == null || selector.isBlank() || dom.select(selector).size() != 1) throw failure("SOURCE_TEXT_SELECTOR_AMBIGUOUS");
        return new StructuredTablePreview().extract(dom.selectFirst(selector));
    }

    @Transactional
    public Result register(UUID registryId, String html, String title, LocalDate publishedAt, String basisUrl,
                           String reason, boolean privacyReviewed, boolean thirdPartyReviewed, boolean storageApproved) {
        UUID account = SourceReviewerService.requireReviewer(jdbc);
        if (!privacyReviewed || !thirdPartyReviewed || !storageApproved || title == null || title.isBlank()
                || title.length() > 500 || html == null || html.isBlank() || html.length() > 2_000_000)
            throw failure("SOURCE_TEXT_REVIEW_REQUIRED");
        var registry = registries.findForUpdateById(registryId).orElseThrow(() -> failure("SOURCE_NOT_FOUND"));
        if (!registry.isEnabled() || registry.getAcquisitionType() != SourceAcquisitionType.HTML
                || LegacyHtmlSourcePolicy.allowsLegacyPath(registry.getId(), registry.getSourceKey())
                || (!("fsc.go.kr".equalsIgnoreCase(registry.getOfficialDomain()) || "korea.kr".equalsIgnoreCase(registry.getOfficialDomain()))
                    && (registry.getContentSelector() == null || registry.getContentSelector().isBlank())))
            throw failure("SOURCE_TEXT_REGISTRY_INVALID");
        var prepared = extractRegistrationText(registry, html);
        String bodySelector = prepared.selector();
        boolean structured = prepared.structured();
        String content = prepared.content();
        var previous = documents.findBySourceRegistry_IdAndStatus(registryId, SourceDocumentStatus.ACTIVE).orElse(null);
        if (previous != null && Boolean.FALSE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM source_text_registration WHERE source_document_id=?)", Boolean.class, previous.getId())))
            throw failure("SOURCE_TEXT_LEGACY_VERSION_REVIEW_REQUIRED");
        if (previous != null) {
            var rights = reviewer.usageReviews(previous.getId(), previous.getRawSha256());
            if (rights.isEmpty() || !"ALLOWED".equals(rights.getFirst().decision())
                    || !List.of(rights.getFirst().allowedScope().split(",")).contains("STORE"))
                throw failure("SOURCE_TEXT_REVIEW_REQUIRED");
        }
        byte[] storedBytes = content.getBytes(StandardCharsets.UTF_8);
        String storedHash = SourceHashing.sha256(storedBytes);
        if (previous != null && previous.getContentSha256().equals(storedHash)
                && (!previous.getTitle().equals(title.strip()) || !java.util.Objects.equals(previous.getPublishedAt(), publishedAt)))
            throw failure("SOURCE_TEXT_METADATA_REVIEW_REQUIRED");
        var parsed = new SourceIngestionData.Parsed(registry.getCanonicalUrl(), title.strip(), publishedAt,
                OffsetDateTime.now(ZoneOffset.UTC), "text/plain;charset=UTF-8", null, null,
                storedHash, storedHash, storedBytes, content);
        SourceIngestionResult result = writer.saveIfCurrent(SourceIngestionData.Snapshot.from(registry), parsed);
        documents.flush();
        SourceDocument document = documents.findBySourceRegistry_IdAndStatus(registryId, SourceDocumentStatus.ACTIVE).orElseThrow();
        var existing = jdbc.query("SELECT usage_review_id FROM source_text_registration WHERE source_document_id=?",
                (rs,n) -> rs.getObject(1, UUID.class), document.getId());
        if (existing.isEmpty()) {
            var usage = reviewer.decideUsage(document.getId(), storedHash, document.getDocumentVersion(), basisUrl,
                    List.of("STORE", "EXTRACT", "REVIEW"), true, true, "ALLOWED", reason, null);
            jdbc.update("""
                INSERT INTO source_text_registration(source_document_id,submitted_html_sha256,extraction_selector,
                    extraction_version,usage_review_id,document_version,stored_text_sha256,registered_by) VALUES (?,?,?,?,?,?,?,?)
                """, document.getId(), SourceHashing.sha256(html.getBytes(StandardCharsets.UTF_8)), bodySelector,
                    structured ? "review-only-html-structured-v2" : "review-only-html-v1", usage.id(), document.getDocumentVersion(), storedHash, account);
        }
        reviewer.prepare(document.getId(), storedHash);
        return new Result(document.getId(), document.getDocumentVersion(), result.outcome().name(), storedHash,
                "SUBMITTED_HTML_UNVERIFIED", "CONTENT_REVIEW_REQUIRED");
    }

    static PreparedText extractRegistrationText(SourceRegistry registry, String html) {
        var dom = Jsoup.parse(html);
        String bodySelector = registry.getContentSelector();
        if ("fsc.go.kr".equalsIgnoreCase(registry.getOfficialDomain())) {
            try { bodySelector = FscHtmlBodySelector.select(dom); }
            catch (IllegalArgumentException invalid) { throw failure("SOURCE_TEXT_SELECTOR_AMBIGUOUS"); }
        }
        if ("korea.kr".equalsIgnoreCase(registry.getOfficialDomain())) {
            if (!KoreaPolicyNews.allowed(java.net.URI.create(registry.getCanonicalUrl()))) throw failure("SOURCE_URL_NOT_ALLOWED");
            try { bodySelector = KoreaPolicyNews.select(dom); }
            catch (IllegalArgumentException invalid) { throw failure("SOURCE_TEXT_SELECTOR_AMBIGUOUS"); }
        }
        var roots = dom.select(bodySelector);
        if (roots.size() != 1) throw failure("SOURCE_TEXT_SELECTOR_AMBIGUOUS");
        Element root = roots.first();
        if ("korea.kr".equalsIgnoreCase(registry.getOfficialDomain())) {
            try { sanitizePolicyNews(root); }
            catch (IllegalArgumentException unsupported) { throw failure("SOURCE_TEXT_CONTENT_REVIEW_REQUIRED"); }
        }
        // Reject structures that would otherwise lose scope or require human rights/privacy assessment.
        if (!root.select("pre,iframe,object,embed,input,textarea,select,[hidden],[aria-hidden=true],[style*=display:none],[style*=visibility:hidden]").isEmpty()
                || root.select("figure,figcaption").stream().anyMatch(e -> !e.text().isBlank())
                || root.select("img").stream().anyMatch(e -> !e.hasAttr("alt") || !e.attr("alt").isBlank()
                    || !"presentation".equalsIgnoreCase(e.attr("role")))
                || root.text().matches("(?s).*(?:[\\w.+-]+@[\\w.-]+\\.[A-Za-z]{2,}|0\\d{1,2}[- .]\\d{3,4}[- .]\\d{4}).*"))
            throw failure("SOURCE_TEXT_CONTENT_REVIEW_REQUIRED");
        boolean structured = transformStructuredText(root);
        // Jsoup wholeText alone can concatenate adjacent HTML blocks when there is no
        // source whitespace. Preserve block boundaries in the stored text contract.
        root.select("p,div,section,article,li,dt,dd,h1,h2,h3,h4,h5,h6").stream().filter(element -> element != root).forEach(element -> {
            element.before(new org.jsoup.nodes.TextNode("\n"));
            element.after(new org.jsoup.nodes.TextNode("\n"));
        });
        var extracted = new ReviewOnlyHtmlBodyExtractor().extract(dom.outerHtml(), bodySelector);
        String content = extracted.normalizedText();
        if (content.length() < 20 || content.indexOf('\uFFFD') >= 0) throw failure("SOURCE_TEXT_EXTRACTION_INCOMPLETE");
        return new PreparedText(bodySelector, structured, content);
    }

    record PreparedText(String selector, boolean structured, String content) {}

    static boolean transformStructuredText(Element root) {
        boolean structured = !root.select("table,sup,blockquote,q").isEmpty();
        if (!structured) return false;
        root.select("sup").forEach(e -> { e.before(new org.jsoup.nodes.TextNode(" | 각주 참조 ")); e.after(new org.jsoup.nodes.TextNode(" | ")); });
        root.select("blockquote,q").forEach(e -> { e.before(new org.jsoup.nodes.TextNode("\n| 인용 문맥 확인 |\n")); e.after(new org.jsoup.nodes.TextNode("\n| 인용 끝 |\n")); });
        var allTables = new java.util.ArrayList<>(root.select("table"));
        var elements = allTables.stream().filter(e -> e.parent() != null && e.parent().closest("table") == null).toList();
        for (int i = 0; i < elements.size(); i++) {
            var original = elements.get(i);
            int sourceTableIndex = allTables.indexOf(original);
            var wrapper = new Element("div"); wrapper.appendChild(original.clone());
            var tables = new StructuredTablePreview().extract(wrapper).tables();
            if (tables.isEmpty()) throw new IllegalArgumentException("table structure unavailable");
            var table = tables.getFirst();
            // A one-cell wrapper containing only an explicit numbered section heading has no row/column data.
            // Preserve it as a heading so an unrelated preceding or following section is not held for this table.
            var paragraphs = original.select("p");
            if (tables.size() == 1 && table.cells().size() == 1 && table.caption() == null
                    && table.cells().getFirst().rowSpan() == 1 && table.cells().getFirst().columnSpan() == 1
                    && paragraphs.size() == 1 && original.text().equals(paragraphs.first().text())) {
                String heading = paragraphs.first().text();
                if (heading.length() <= 100 && heading.matches("(?s)^\\s*[ⅠⅡⅢⅣⅤⅥⅦⅧⅨⅩ]\\s*[.．]?\\s*\\S.*")
                        && !heading.matches("(?s).*[.!?。！？]$")) {
                    original.replaceWith(new org.jsoup.nodes.TextNode("\n# " + heading + "\n"));
                    continue;
                }
            }
            StringBuilder text = new StringBuilder("\n| 구조화 표 ").append(sourceTableIndex).append(" | 원문 표 위치: preview-table-v1:").append(sourceTableIndex).append(" |");
            if (table.caption() != null) text.append(" 표 제목: ").append(table.caption()).append(" |");
            for (var cell : table.cells()) text.append(" 표 ").append(sourceTableIndex).append(" 행 ").append(cell.row())
                    .append(" 열 ").append(cell.column()).append(" 행병합 ").append(cell.rowSpan())
                    .append(" 열병합 ").append(cell.columnSpan()).append(cell.header() ? " 머리글: " : " 셀: ")
                    .append(cell.text().replace('\n', ' ')).append(" |");
            if ("HOLD".equals(table.status()) || tables.size() > 1) text.append(" 구조 검증 미완료 표 텍스트: ")
                    .append(original.text()).append(" |");
            text.append(" 구조화 표 끝 |\n");
            original.replaceWith(new org.jsoup.nodes.TextNode(text.toString()));
        }
        root.select("h1,h2,h3,h4,h5,h6").forEach(e -> e.prependText("# "));
        // Roman-numbered short paragraphs are explicit section titles in many official publications.
        root.select("p").stream().filter(e -> e.text().length() <= 100
                && e.text().matches("(?s)^\\s*[ⅠⅡⅢⅣⅤⅥⅦⅧⅨⅩ]\\s*[.．]?\\s*\\S.*")
                && !e.text().matches("(?s).*[.!?。！？]$")).forEach(e -> e.prependText("# "));
        return true;
    }

    static void sanitizePolicyNews(Element root) {
        if (root.select("img,video,audio,figcaption").stream().anyMatch(e -> e.closest("figure,picture") == null))
            throw new IllegalArgumentException("Media without an identifiable enclosing figure requires review");
        // Remove media as a unit, retaining a marker so nearby claims remain reviewable but not silently independent.
        var media = new java.util.ArrayList<>(root.select("figure,picture,video,audio,img,svg"));
        for (var element : media) {
            if (element.parent() == null || element.parents().stream().anyMatch(p -> media.contains(p))) continue;
            element.replaceWith(new org.jsoup.nodes.TextNode(" | 미디어 제외 | "));
        }
        var quotations = new java.util.ArrayList<>(root.select("blockquote,q"));
        for (var element : quotations) {
            if (element.parent() == null || element.parents().stream().anyMatch(p -> quotations.contains(p))) continue;
            element.replaceWith(new org.jsoup.nodes.TextNode("\n| 인용 제외 |\n"));
        }
        for (var element : new java.util.ArrayList<>(root.select("p,li"))) {
            if (element.text().matches("(?s)^\\s*(?:문의|취재문의|자료문의|담당자|연락처)\\s*[:：].*"))
                element.replaceWith(new org.jsoup.nodes.TextNode("\n| 연락처 제외 |\n"));
        }
    }

    private static SourceReviewException failure(String code) { return new SourceReviewException(HttpStatus.CONFLICT, code); }
    public record Result(UUID documentId, int documentVersion, String outcome, String storedTextSha256,
                         String sourceVerification, String contentReview) {}
}
