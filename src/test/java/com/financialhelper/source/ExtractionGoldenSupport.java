package com.financialhelper.source;

import org.apache.poi.poifs.filesystem.POIFSFileSystem;
import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Offline test helpers. No Spring context, DB, tokenizer runtime or network. */
final class ExtractionGoldenSupport {
    private ExtractionGoldenSupport() {}

    static SourceIngestionData.Parsed parse(SourceDocumentParser parser,
            SourceAcquisitionType type, String contentType, byte[] raw) {
        var source = new SourceIngestionData.Snapshot(UUID.randomUUID(), "synthetic-only",
                "합성 기관", "example.invalid", "https://example.invalid/fixture",
                type, type == SourceAcquisitionType.HTML ? "#fixture" : null);
        var parsed = parser.parse(source, new SourceIngestionData.Fetched(source.canonicalUrl(),
                contentType, null, null, raw, OffsetDateTime.parse("2026-10-01T00:00:00Z")));
        assertThat(parsed.originalContent()).isEqualTo(raw);
        assertThat(parsed.rawSha256()).isEqualTo(SourceHashing.sha256(raw));
        assertThat(parsed.contentSha256()).isEqualTo(SourceHashing.sha256(parsed.normalizedContent()));
        return parsed;
    }

    static List<SourceChunkData.Definition> chunks(String content, int target, int max) {
        var persistence = mock(SourceChunkPersistenceService.class);
        var document = mock(SourceDocument.class);
        when(document.getId()).thenReturn(UUID.randomUUID());
        when(document.getNormalizedContent()).thenReturn(content);
        List<SourceChunkData.Definition> definitions = new ArrayList<>();
        doAnswer(call -> {
            definitions.add(call.getArgument(1));
            return mock(SourceChunk.class);
        }).when(persistence).saveIfAbsent(any(), any());
        SourceChunkTokenizer tokenizer = new SourceChunkTokenizer() {
            public int countDocumentTokens(String text) { return text.strip().split("\\s+").length; }
            public String identifier() { return "offline-whitespace"; }
            public String revision() { return "v1"; }
        };
        new SourceChunkingService(persistence, tokenizer,
                new SourceChunkingProperties(target, max, 0, "golden-test", tokenizer.identifier(),
                        tokenizer.revision()), JsonMapper.builder().build()).chunk(document);
        for (int i = 0; i < definitions.size(); i++) {
            var chunk = definitions.get(i);
            assertThat(chunk.sequence()).isEqualTo(i);
            assertThat(chunk.body()).isEqualTo(content.substring(chunk.sourceStartOffset(), chunk.sourceEndOffset()));
            assertThat(chunk.locator()).isEqualTo("normalized-content:utf16:"
                    + chunk.sourceStartOffset() + "-" + chunk.sourceEndOffset());
            if (i > 0) assertThat(chunk.sourceStartOffset()).isGreaterThanOrEqualTo(definitions.get(i - 1).sourceEndOffset());
        }
        return definitions;
    }

    static String bodies(List<SourceChunkData.Definition> chunks) {
        return String.join("\n", chunks.stream().map(SourceChunkData.Definition::body).toList());
    }

    /** Minimal OLE/paragraph records for this parser, not a rendered HWP table fixture. */
    static byte[] hwp(List<List<String>> sections) throws Exception {
        List<String> names = new ArrayList<>();
        for (int i = 0; i < sections.size(); i++) names.add("Section" + i);
        return hwp(sections, names);
    }

    static byte[] hwp(List<List<String>> sections, List<String> names) throws Exception {
        if (sections.size() != names.size()) throw new IllegalArgumentException("Section names must match bodies");
        try (var fs = new POIFSFileSystem(); var output = new ByteArrayOutputStream()) {
            byte[] header = new byte[256];
            byte[] signature = "HWP Document File".getBytes(StandardCharsets.US_ASCII);
            System.arraycopy(signature, 0, header, 0, signature.length);
            header[35] = 5;
            fs.getRoot().createDocument("FileHeader", new ByteArrayInputStream(header));
            var body = fs.getRoot().createDirectory("BodyText");
            for (int i = 0; i < sections.size(); i++) {
                var records = new ByteArrayOutputStream();
                for (String paragraph : sections.get(i)) {
                    byte[] text = paragraph.getBytes(StandardCharsets.UTF_16LE);
                    records.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
                            .putInt(0x43 | (text.length << 20)).array());
                    records.write(text);
                }
                body.createDocument(names.get(i), new ByteArrayInputStream(records.toByteArray()));
            }
            fs.writeFilesystem(output);
            return output.toByteArray();
        }
    }
}
