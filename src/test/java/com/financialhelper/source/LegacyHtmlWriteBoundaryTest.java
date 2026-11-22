package com.financialhelper.source;

import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class LegacyHtmlWriteBoundaryTest {
    @Test void legacyHtmlCannotReachFetcherOrWriter() {
        var registry = mock(SourceRegistry.class);
        var registries = mock(SourceRegistryRepository.class);
        var domains = mock(OfficialSourceDomainRepository.class);
        var fetcher = mock(SourceContentFetcher.class);
        var writer = mock(SourceDocumentWriter.class);
        when(registry.getAcquisitionType()).thenReturn(SourceAcquisitionType.HTML);
        when(registries.findBySourceKeyAndEnabledTrue("fsc-card-use-qa")).thenReturn(Optional.of(registry));
        var service = new SourceIngestionService(registries, domains, fetcher, List.of(), writer);
        assertThatThrownBy(() -> service.ingestOne("fsc-card-use-qa"))
                .isInstanceOf(SourceIngestionException.class).extracting("code")
                .isEqualTo("SOURCE_HTML_TEXT_ONLY_REQUIRED");
        verifyNoInteractions(fetcher, writer, domains);
    }

    @Test void legacyRawHtmlCannotBePersistedEvenThroughWriter() {
        var registries = mock(SourceRegistryRepository.class);
        var domains = mock(OfficialSourceDomainRepository.class);
        var documents = mock(SourceDocumentRepository.class);
        var writer = new SourceDocumentWriter(registries, domains, documents);
        var snapshot = new SourceIngestionData.Snapshot(UUID.randomUUID(), "fsc-card-use-qa", "synthetic", "example.invalid", "https://example.invalid/", SourceAcquisitionType.HTML, ".body");
        byte[] raw = "<script>synthetic</script><p>합성 본문</p>".getBytes(StandardCharsets.UTF_8);
        var parsed = new SourceIngestionData.Parsed(snapshot.canonicalUrl(), "합성", null, OffsetDateTime.now(), "text/html", null, null, SourceHashing.sha256(raw), SourceHashing.sha256("합성 본문"), raw, "합성 본문");
        assertThatThrownBy(() -> writer.saveIfCurrent(snapshot, parsed))
                .isInstanceOf(SourceIngestionException.class).extracting("code")
                .isEqualTo("SOURCE_HTML_TEXT_ONLY_REQUIRED");
        verifyNoInteractions(registries, domains, documents);
    }

    @Test void htmlCannotBeDisguisedAsPlainTextWithMatchingHashes() {
        var registries = mock(SourceRegistryRepository.class);
        var domains = mock(OfficialSourceDomainRepository.class);
        var documents = mock(SourceDocumentRepository.class);
        var writer = new SourceDocumentWriter(registries, domains, documents);
        var snapshot = new SourceIngestionData.Snapshot(UUID.randomUUID(), "fsc-card-use-qa", "synthetic", "example.invalid", "https://example.invalid/", SourceAcquisitionType.HTML, ".body");
        String html = "<p>합성 문서</p>";
        byte[] bytes = html.getBytes(StandardCharsets.UTF_8);
        String hash = SourceHashing.sha256(bytes);
        var parsed = new SourceIngestionData.Parsed(snapshot.canonicalUrl(), "합성", null, OffsetDateTime.now(), "text/plain;charset=UTF-8", null, null, hash, hash, bytes, html);
        assertThatThrownBy(() -> writer.saveIfCurrent(snapshot, parsed)).isInstanceOf(SourceIngestionException.class)
                .extracting("code").isEqualTo("SOURCE_HTML_TEXT_ONLY_REQUIRED");
        verifyNoInteractions(registries, domains, documents);
    }

    @Test void entityInsertGuardRejectsRawHtmlWhenWriterIsBypassed() {
        var registry = mock(SourceRegistry.class);
        when(registry.getAcquisitionType()).thenReturn(SourceAcquisitionType.HTML);
        byte[] raw = "<p>합성</p>".getBytes(StandardCharsets.UTF_8);
        var parsed = new SourceIngestionData.Parsed("https://example.invalid/", "합성", null, OffsetDateTime.now(), "text/html", null, null, SourceHashing.sha256(raw), SourceHashing.sha256("합성"), raw, "합성");
        var document = new SourceDocument(registry, 1, parsed);
        assertThatThrownBy(document::rejectRawHtmlOnInsert).isInstanceOf(SourceIngestionException.class)
                .extracting("code").isEqualTo("SOURCE_HTML_TEXT_ONLY_REQUIRED");
    }
}
