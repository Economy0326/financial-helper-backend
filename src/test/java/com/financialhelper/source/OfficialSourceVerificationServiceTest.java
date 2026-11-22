package com.financialhelper.source;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class OfficialSourceVerificationServiceTest {
    private static final OffsetDateTime NOW = OffsetDateTime.of(2026, 11, 14, 0, 0, 0, 0, ZoneOffset.UTC);
    private static final String URL = "https://www.official.example/document";
    private static final String DOMAIN = "official.example";
    private final SourceReviewerService authority = mock(SourceReviewerService.class);
    private final SourceRegistryRepository registries = mock(SourceRegistryRepository.class);
    private final OfficialSourceDomainRepository domains = mock(OfficialSourceDomainRepository.class);
    private final SourceDocumentRepository documents = mock(SourceDocumentRepository.class);
    private final SourceContentFetcher fetcher = mock(SourceContentFetcher.class);
    private final SourceUrlPolicy urlPolicy = new SourceUrlPolicy();
    private final SourceDocumentParser htmlParser = new HtmlSourceDocumentParser();
    private final SourceDocumentParser pdfParser = new PdfSourceDocumentParser();
    private final OfficialSourceVerificationService service = new OfficialSourceVerificationService(
            authority, registries, domains, documents, fetcher, urlPolicy, List.of(htmlParser, pdfParser));

    @Test void exactPdfUsesRawBytesAndNeverAssertsLegalCurrentness() throws Exception {
        byte[] bytes = pdf("Official deposit information. ".repeat(8));
        var fixture = fixture("pdf-fixture", DOMAIN, URL, SourceAcquisitionType.PDF, bytes, "application/pdf");
        when(fetcher.fetch(any())).thenReturn(fetched(URL, "application/pdf", bytes, 200));
        var result = service.verify(fixture.key(), fixture.documentId());
        assertThat(result.result()).isEqualTo("RAW_BYTES_MATCH");
        assertThat(result.method()).isEqualTo("PDF_RAW_SHA256");
        assertThat(result.httpStatus()).isEqualTo(200);
        assertThat(result.downloadSucceeded()).isTrue();
        assertThat(result.observedRawSha256()).isEqualTo(SourceHashing.sha256(bytes));
        assertThat(result.checkedAt()).isEqualTo(NOW);
        assertThat(result.supersedingGuidanceUnknown()).isTrue();
        assertThat(result.sourceIdentityVerified()).isFalse(); // Generic fixture has no trusted identity rule.
        verifyNoInteractionsBeyondReads();
    }

    @Test void differentPdfBytesNeverBecomeAMatch() throws Exception {
        byte[] first = pdf("First official notice. ".repeat(8));
        byte[] other = pdf("Second official notice. ".repeat(8));
        var fixture = fixture("pdf-fixture", DOMAIN, URL, SourceAcquisitionType.PDF, first, "application/pdf");
        when(fetcher.fetch(any())).thenReturn(fetched(URL, "application/pdf", other, 200));
        var result = service.verify(fixture.key(), fixture.documentId());
        assertThat(result.result()).isEqualTo("CONTENT_CHANGED_REVIEW_REQUIRED");
        assertThat(result.observedRawSha256()).isNotEqualTo(result.storedRawSha256());
    }

    @Test void dynamicHtmlNormalizedMatchIsNotAnExactMatch() {
        byte[] first = html("one");
        byte[] other = html("two");
        var fixture = fixture("html-fixture", DOMAIN, URL, SourceAcquisitionType.HTML, first, "text/html");
        when(fetcher.fetch(any())).thenReturn(fetched(URL, "text/html", other, 200));
        var result = service.verify(fixture.key(), fixture.documentId());
        assertThat(result.result()).isEqualTo("HTML_NORMALIZED_MATCH_RAW_CHANGED_REVIEW_REQUIRED");
        assertThat(result.method()).isEqualTo("HTML_RAW_AND_NORMALIZED_SHA256");
        assertThat(result.observedRawSha256()).isNotEqualTo(result.storedRawSha256());
    }

    @Test void textRegistrationUsesTheSameNormalizationWhenCheckingForChanges() {
        byte[] first = html("one");
        var fixture = fixture("text-fixture", DOMAIN, URL, SourceAcquisitionType.HTML, first, "text/html");
        var registry = registries.findBySourceKeyAndEnabledTrue(fixture.key()).orElseThrow();
        when(registry.getContentSelector()).thenReturn("main");
        var document = documents.findBySourceRegistry_IdAndStatus(registry.getId(), SourceDocumentStatus.ACTIVE).orElseThrow();
        String stored = TextOnlySourceRegistrationService.extractRegistrationText(registry, new String(first, StandardCharsets.UTF_8)).content();
        byte[] storedBytes = stored.getBytes(StandardCharsets.UTF_8);
        when(document.getOriginalContent()).thenReturn(storedBytes);
        when(document.getNormalizedContent()).thenReturn(stored);
        when(document.getRawSha256()).thenReturn(SourceHashing.sha256(storedBytes));
        when(document.getContentSha256()).thenReturn(SourceHashing.sha256(stored));
        when(fetcher.fetch(any())).thenReturn(fetched(URL, "text/html", html("two"), 200));
        var observed = service.verifyRegistered(fixture.key(), fixture.documentId(), true);
        assertThat(DocumentChangeService.classify(SourceHashing.sha256(stored), observed)).isEqualTo("UNCHANGED");
        byte[] edited = new String(html("two"), StandardCharsets.UTF_8).replace("conditions.", "changed conditions.").getBytes(StandardCharsets.UTF_8);
        when(fetcher.fetch(any())).thenReturn(fetched(URL, "text/html", edited, 200));
        var changed = service.verifyRegistered(fixture.key(), fixture.documentId(), true);
        assertThat(DocumentChangeService.classify(SourceHashing.sha256(stored), changed)).isEqualTo("CHANGED");
    }

    @Test void http200MaintenanceAndLoginPagesAreNotDocuments() {
        var fixture = fixture("html-fixture", DOMAIN, URL, SourceAcquisitionType.HTML, html("one"), "text/html");
        byte[] maintenance = "<html><head><title>시스템 점검</title></head><body>서비스 중단</body></html>".getBytes(StandardCharsets.UTF_8);
        when(fetcher.fetch(any())).thenReturn(fetched(URL, "text/html", maintenance, 200));
        var result = service.verify(fixture.key(), fixture.documentId());
        assertThat(result.result()).isEqualTo("SOURCE_ERROR_PAGE");
        assertThat(result.downloadSucceeded()).isTrue();
        assertThat(result.observedRawSha256()).isEqualTo(SourceHashing.sha256(maintenance));
        byte[] login = "<html><head><title>Portal</title></head><body><input type='password'></body></html>".getBytes(StandardCharsets.UTF_8);
        when(fetcher.fetch(any())).thenReturn(fetched(URL, "text/html", login, 200));
        assertThat(service.verify(fixture.key(), fixture.documentId()).result()).isEqualTo("SOURCE_ERROR_PAGE");
    }

    @Test void matchingSyntheticLawBytesStillRequireLegalVersionVerification() throws Exception {
        String key = "law-financial-consumer-protection-19";
        String url = OfficialInformationClaimSelector.officialUrl(key);
        byte[] bytes;
        try (var input = getClass().getResourceAsStream("/official/extraction/synthetic-law-article-19.html")) {
            if (input == null) throw new IllegalStateException("Fixture missing");
            bytes = input.readAllBytes();
        }
        var fixture = fixture(key, "law.go.kr", url, SourceAcquisitionType.HTML, bytes, "text/html");
        when(fetcher.fetch(any())).thenReturn(fetched(url, "text/html", bytes, 200));
        var result = service.verify(fixture.key(), fixture.documentId());
        assertThat(result.result()).isEqualTo("RAW_BYTES_MATCH");
        assertThat(result.legalVersionVerificationRequired()).isTrue();
        assertThat(result.supersedingGuidanceUnknown()).isTrue();
        assertThat(result.sourceIdentityVerified()).isTrue();
    }

    @Test void timeoutAndHttpFailureAreNotMatches() {
        var fixture = fixture("html-fixture", DOMAIN, URL, SourceAcquisitionType.HTML, html("one"), "text/html");
        when(fetcher.fetch(any())).thenThrow(new SourceIngestionException("SOURCE_REQUEST_FAILED", "timeout"));
        var timeout = service.verify(fixture.key(), fixture.documentId());
        assertThat(timeout.result()).isEqualTo("SOURCE_REQUEST_FAILED");
        assertThat(timeout.httpStatus()).isNull();
        assertThat(timeout.downloadSucceeded()).isFalse();
        doThrow(new SourceIngestionException("SOURCE_HTTP_ERROR", "HTTP 503", 503)).when(fetcher).fetch(any());
        var unavailable = service.verify(fixture.key(), fixture.documentId());
        assertThat(unavailable.httpStatus()).isEqualTo(503);
        assertThat(unavailable.result()).isEqualTo("SOURCE_HTTP_ERROR");
        assertThat(unavailable.downloadSucceeded()).isFalse();
    }

    @Test void rejectsUnauthorisedAndUnregisteredSourcesBeforeFetching() {
        doThrow(new SourceReviewException(org.springframework.http.HttpStatus.FORBIDDEN, "SOURCE_REVIEW_FORBIDDEN"))
                .when(authority).requireReviewer();
        assertThatThrownBy(() -> service.verify("unknown", UUID.randomUUID())).isInstanceOf(SourceReviewException.class);
        verifyNoInteractions(fetcher);
    }

    @Test void disabledOfficialDomainCannotTriggerFetch() {
        var fixture = fixture("html-fixture", DOMAIN, URL, SourceAcquisitionType.HTML, html("one"), "text/html");
        when(domains.existsByDomainAndEnabledTrue(DOMAIN)).thenReturn(false);
        assertThatThrownBy(() -> service.verify(fixture.key(), fixture.documentId()))
                .isInstanceOf(SourceIngestionException.class).extracting("code").isEqualTo("SOURCE_DOMAIN_DISABLED");
        verifyNoInteractions(fetcher);
    }

    @Test void corruptStoredDocumentCannotTriggerFetch() {
        var fixture = fixture("html-fixture", DOMAIN, URL, SourceAcquisitionType.HTML, html("one"), "text/html");
        var registry = registries.findBySourceKeyAndEnabledTrue(fixture.key()).orElseThrow();
        var document = documents.findBySourceRegistry_IdAndStatus(registry.getId(), SourceDocumentStatus.ACTIVE).orElseThrow();
        when(document.getRawSha256()).thenReturn("0".repeat(64));
        assertThat(service.verify(fixture.key(), fixture.documentId()).result())
                .isEqualTo("STORED_SOURCE_INTEGRITY_MISMATCH");
        verifyNoInteractions(fetcher);
    }

    @Test void aDifferentOrUnexpectedFinalHostCannotBeReportedAsMatch() {
        var fixture = fixture("html-fixture", DOMAIN, URL, SourceAcquisitionType.HTML, html("one"), "text/html");
        when(fetcher.fetch(any())).thenReturn(fetched("https://evil.example/document", "text/html", html("one"), 200));
        assertThat(service.verify(fixture.key(), fixture.documentId()).result()).isEqualTo("SOURCE_DOMAIN_NOT_ALLOWED");
    }

    @Test void rejectsUnsafeRedirectWithoutRequestingTheDestination() throws Exception {
        var client = mock(HttpClient.class);
        @SuppressWarnings("unchecked") HttpResponse<java.io.InputStream> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(302);
        when(response.body()).thenReturn(new ByteArrayInputStream(new byte[0]));
        when(response.headers()).thenReturn(HttpHeaders.of(Map.of("Location", List.of("https://evil.example/steal")), (a, b) -> true));
        doReturn(response).when(client).send(any(), any(HttpResponse.BodyHandler.class));
        var actualFetcher = new HttpSourceContentFetcher(properties(), urlPolicy, client);
        var source = new SourceIngestionData.Snapshot(UUID.randomUUID(), "fixture", "fixture", DOMAIN,
                URL, SourceAcquisitionType.HTML, null);
        assertThatThrownBy(() -> actualFetcher.fetch(source)).isInstanceOf(SourceIngestionException.class)
                .extracting("code").isEqualTo("SOURCE_DOMAIN_NOT_ALLOWED");
        verify(client, times(1)).send(any(), any(HttpResponse.BodyHandler.class));
    }

    @Test void realFetcherTurnsNetworkTimeoutIntoFailure() throws Exception {
        var client = mock(HttpClient.class);
        when(client.send(any(), any(HttpResponse.BodyHandler.class))).thenThrow(new HttpTimeoutException("timeout"));
        var actualFetcher = new HttpSourceContentFetcher(properties(), urlPolicy, client);
        var source = new SourceIngestionData.Snapshot(UUID.randomUUID(), "fixture", "fixture", DOMAIN,
                URL, SourceAcquisitionType.HTML, null);
        assertThatThrownBy(() -> actualFetcher.fetch(source)).isInstanceOf(SourceIngestionException.class)
                .extracting("code").isEqualTo("SOURCE_REQUEST_TIMEOUT");
    }

    @Test void streamedResponseHasBothSizeAndTimeLimits() throws Exception {
        var client = mock(HttpClient.class);
        @SuppressWarnings("unchecked") HttpResponse<java.io.InputStream> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.headers()).thenReturn(HttpHeaders.of(Map.of("Content-Type", List.of("text/html")), (a, b) -> true));
        doReturn(response).when(client).send(any(), any(HttpResponse.BodyHandler.class));
        var shortLimits = new SourceIngestionProperties(false, Duration.ofSeconds(1),
                Duration.ofMillis(150), 10, 1, "fixture");
        var actualFetcher = new HttpSourceContentFetcher(shortLimits, urlPolicy, client);
        var source = new SourceIngestionData.Snapshot(UUID.randomUUID(), "fixture", "fixture", DOMAIN,
                URL, SourceAcquisitionType.HTML, null);
        when(response.body()).thenReturn(new ByteArrayInputStream(new byte[11]));
        assertThatThrownBy(() -> actualFetcher.fetch(source)).isInstanceOf(SourceIngestionException.class)
                .extracting("code").isEqualTo("SOURCE_RESPONSE_TOO_LARGE");
        when(response.body()).thenReturn(new java.io.InputStream() {
            @Override public int read() throws java.io.IOException {
                try { Thread.sleep(5000); return -1; }
                catch (InterruptedException interrupted) { throw new java.io.InterruptedIOException(); }
            }
        });
        org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(Duration.ofSeconds(2), () ->
                assertThatThrownBy(() -> actualFetcher.fetch(source)).isInstanceOf(SourceIngestionException.class)
                        .extracting("code").isEqualTo("SOURCE_REQUEST_TIMEOUT"));
    }

    @Test void registryChangeDuringFetchInvalidatesTheResult() {
        var fixture = fixture("html-fixture", DOMAIN, URL, SourceAcquisitionType.HTML, html("one"), "text/html");
        var registry = registries.findBySourceKeyAndEnabledTrue(fixture.key()).orElseThrow();
        when(registries.findBySourceKeyAndEnabledTrue(fixture.key())).thenReturn(Optional.of(registry), Optional.empty());
        when(fetcher.fetch(any())).thenReturn(fetched(URL, "text/html", html("one"), 200));
        assertThat(service.verify(fixture.key(), fixture.documentId()).result()).isEqualTo("SOURCE_REGISTRY_CHANGED");
    }

    private Fixture fixture(String key, String domain, String url, SourceAcquisitionType type, byte[] bytes, String mime) {
        UUID registryId = UUID.randomUUID();
        UUID documentId = UUID.randomUUID();
        var registry = mock(SourceRegistry.class);
        when(registry.getId()).thenReturn(registryId);
        when(registry.getSourceKey()).thenReturn(key);
        when(registry.getOrganizationName()).thenReturn("fixture");
        when(registry.getOfficialDomain()).thenReturn(domain);
        when(registry.getCanonicalUrl()).thenReturn(url);
        when(registry.getAcquisitionType()).thenReturn(type);
        when(registries.findBySourceKeyAndEnabledTrue(key)).thenReturn(Optional.of(registry));
        when(domains.existsByDomainAndEnabledTrue(domain)).thenReturn(true);
        var snapshot = SourceIngestionData.Snapshot.from(registry);
        var stored = (type == SourceAcquisitionType.PDF ? pdfParser : htmlParser)
                .parse(snapshot, fetched(url, mime, bytes, 200));
        var document = mock(SourceDocument.class);
        when(document.getId()).thenReturn(documentId);
        when(document.getDocumentVersion()).thenReturn(1);
        when(document.getCanonicalUrl()).thenReturn(url);
        when(document.getResolvedUrl()).thenReturn(url);
        when(document.getRawSha256()).thenReturn(stored.rawSha256());
        when(document.getContentSha256()).thenReturn(stored.contentSha256());
        when(document.getOriginalContent()).thenReturn(bytes);
        when(document.getNormalizedContent()).thenReturn(stored.normalizedContent());
        when(documents.findBySourceRegistry_IdAndStatus(registryId, SourceDocumentStatus.ACTIVE)).thenReturn(Optional.of(document));
        return new Fixture(key, documentId);
    }

    private void verifyNoInteractionsBeyondReads() {
        verify(documents, never()).save(any());
        verify(documents, never()).delete(any());
    }

    private static SourceIngestionData.Fetched fetched(String url, String mime, byte[] bytes, int status) {
        return new SourceIngestionData.Fetched(url, mime, null, null, bytes, NOW, status);
    }

    private static byte[] html(String script) {
        return ("<html><head><title>Official notice</title><script>var x='" + script + "';</script></head>"
                + "<body><main>" + "Official consumer information and conditions. ".repeat(6) + "</main></body></html>")
                .getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] pdf(String text) throws Exception {
        try (var doc = new PDDocument(); var output = new ByteArrayOutputStream()) {
            var page = new PDPage();
            doc.addPage(page);
            try (var content = new PDPageContentStream(doc, page)) {
                content.beginText();
                content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 10);
                content.newLineAtOffset(40, 700);
                content.showText(text);
                content.endText();
            }
            doc.save(output);
            return output.toByteArray();
        }
    }

    private static SourceIngestionProperties properties() {
        return new SourceIngestionProperties(false, Duration.ofSeconds(2), Duration.ofSeconds(3), 100000, 2, "fixture");
    }

    private record Fixture(String key, UUID documentId) {}
}
