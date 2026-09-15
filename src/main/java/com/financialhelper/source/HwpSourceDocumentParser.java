package com.financialhelper.source;

import org.apache.poi.poifs.filesystem.DocumentEntry;
import org.apache.poi.poifs.filesystem.DocumentInputStream;
import org.apache.poi.poifs.filesystem.DirectoryEntry;
import org.apache.poi.poifs.filesystem.Entry;
import org.apache.poi.poifs.filesystem.POIFSFileSystem;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * Minimal HWP 5.0 text extraction for official source documents.  It reads
 * only the OLE FileHeader and BodyText/Section streams, preserves the input
 * bytes unchanged, and fails closed when the expected HWP structure is not
 * present.  It is deliberately not a general office/OCR parser.
 */
@Component
public class HwpSourceDocumentParser implements SourceDocumentParser {

    private static final int FILE_HEADER_COMPRESSION_OFFSET = 36;
    // HWP 5.0 record tags: 0x42 is PARA_HEADER and 0x43 is PARA_TEXT.
    private static final int PARA_TEXT_TAG = 0x43;
    private static final int MAX_INFLATED_BYTES = 20 * 1024 * 1024;
    private static final int MIN_NORMALIZED_LENGTH = 120;
    private static final Pattern SECTION_NAME = Pattern.compile("Section\\d+");

    @Override
    public boolean supports(SourceAcquisitionType acquisitionType) {
        return acquisitionType == SourceAcquisitionType.HWP;
    }

    @Override
    public SourceIngestionData.Parsed parse(
            SourceIngestionData.Snapshot source,
            SourceIngestionData.Fetched fetched
    ) {
        if (!supports(source.acquisitionType())) {
            throw new SourceIngestionException(
                    "SOURCE_TYPE_UNSUPPORTED",
                    "HWP parser cannot parse this acquisition type"
            );
        }
        if (!looksLikeHwp(fetched)) {
            throw new SourceIngestionException(
                    "SOURCE_CONTENT_TYPE_UNSUPPORTED",
                    "Official source did not return HWP"
            );
        }

        String normalizedContent = extractText(fetched.originalContent());
        if (normalizedContent.length() < MIN_NORMALIZED_LENGTH) {
            throw new SourceIngestionException(
                    "SOURCE_CONTENT_TOO_SHORT",
                    "Official HWP content is too short to trust as a document"
            );
        }

        return new SourceIngestionData.Parsed(
                fetched.resolvedUrl(),
                source.sourceKey(),
                null,
                fetched.retrievedAt(),
                fetched.contentType(),
                fetched.httpEtag(),
                fetched.httpLastModified(),
                SourceHashing.sha256(fetched.originalContent()),
                SourceHashing.sha256(normalizedContent),
                fetched.originalContent(),
                normalizedContent
        );
    }

    private boolean looksLikeHwp(SourceIngestionData.Fetched fetched) {
        String contentType = fetched.contentType() == null
                ? ""
                : fetched.contentType().toLowerCase();
        String url = fetched.resolvedUrl() == null
                ? ""
                : fetched.resolvedUrl().toLowerCase();
        return contentType.contains("hwp")
                || contentType.contains("octet-stream")
                || contentType.contains("msdownload")
                || url.contains(".hwp");
    }

    private String extractText(byte[] originalContent) {
        try (POIFSFileSystem fileSystem = new POIFSFileSystem(
                new ByteArrayInputStream(originalContent))) {
            DirectoryEntry bodyText = directory(fileSystem.getRoot(), "BodyText");
            if (bodyText == null) {
                throw new IOException("HWP BodyText storage is missing");
            }
            boolean compressed = isCompressed(fileSystem.getRoot());
            List<DocumentEntry> sections = sections(bodyText);
            if (sections.isEmpty()) {
                throw new IOException("HWP BodyText has no Section stream");
            }

            StringBuilder paragraphs = new StringBuilder();
            for (DocumentEntry section : sections) {
                byte[] bytes = read(section);
                if (compressed) {
                    bytes = inflate(bytes);
                }
                appendParagraphText(bytes, paragraphs);
            }
            return normalize(paragraphs.toString());
        } catch (IOException | RuntimeException exception) {
            throw new SourceIngestionException(
                    "SOURCE_HWP_PARSE_FAILED",
                    "Could not parse official HWP source",
                    exception
            );
        }
    }

    private DirectoryEntry directory(DirectoryEntry parent, String name)
            throws IOException {
        if (!parent.hasEntry(name)) {
            return null;
        }
        Entry entry = parent.getEntry(name);
        return entry instanceof DirectoryEntry directory ? directory : null;
    }

    private boolean isCompressed(DirectoryEntry root) throws IOException {
        if (!root.hasEntry("FileHeader")) {
            throw new IOException("HWP FileHeader stream is missing");
        }
        byte[] header = read((DocumentEntry) root.getEntry("FileHeader"));
        if (header.length <= FILE_HEADER_COMPRESSION_OFFSET) {
            throw new IOException("HWP FileHeader is truncated");
        }
        return (header[FILE_HEADER_COMPRESSION_OFFSET] & 0x01) != 0;
    }

    private List<DocumentEntry> sections(DirectoryEntry bodyText) {
        List<DocumentEntry> sections = new ArrayList<>();
        bodyText.getEntries().forEachRemaining(entry -> {
            if (entry instanceof DocumentEntry document
                    && SECTION_NAME.matcher(entry.getName()).matches()) {
                sections.add(document);
            }
        });
        sections.sort(Comparator.comparing(Entry::getName));
        return sections;
    }

    private byte[] read(DocumentEntry entry) throws IOException {
        try (DocumentInputStream input = new DocumentInputStream(entry)) {
            return input.readAllBytes();
        }
    }

    private byte[] inflate(byte[] compressed) throws IOException {
        IOException rawFailure = null;
        for (boolean nowrap : new boolean[]{true, false}) {
            Inflater inflater = new Inflater(nowrap);
            try {
                inflater.setInput(compressed);
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                while (!inflater.finished()) {
                    int count = inflater.inflate(buffer);
                    if (count == 0) {
                        if (inflater.needsDictionary() || inflater.needsInput()) {
                            throw new DataFormatException("incomplete HWP compressed stream");
                        }
                    } else {
                        output.write(buffer, 0, count);
                        if (output.size() > MAX_INFLATED_BYTES) {
                            throw new IOException("HWP Section stream is too large");
                        }
                    }
                }
                return output.toByteArray();
            } catch (DataFormatException exception) {
                rawFailure = new IOException("invalid HWP compressed stream", exception);
            } finally {
                inflater.end();
            }
        }
        throw rawFailure == null
                ? new IOException("invalid HWP compressed stream")
                : rawFailure;
    }

    private void appendParagraphText(byte[] records, StringBuilder output)
            throws IOException {
        int offset = 0;
        while (offset + 4 <= records.length) {
            int header = littleEndianInt(records, offset);
            offset += 4;
            int size = (header >>> 20) & 0x0FFF;
            if (size == 0x0FFF) {
                if (offset + 4 > records.length) {
                    throw new IOException("truncated HWP record size");
                }
                size = littleEndianInt(records, offset);
                offset += 4;
            }
            if (size < 0 || offset + size > records.length) {
                throw new IOException("invalid HWP record size");
            }
            int tag = header & 0x03FF;
            if (tag == PARA_TEXT_TAG && size >= 2) {
                String text = new String(
                        records,
                        offset,
                        size - (size % 2),
                        StandardCharsets.UTF_16LE
                );
                appendSafeText(text, output);
                output.append('\n');
            }
            offset += size;
        }
        if (offset != records.length) {
            throw new IOException("trailing bytes in HWP record stream");
        }
    }

    private void appendSafeText(String text, StringBuilder output) {
        for (int index = 0; index < text.length(); index++) {
            char value = text.charAt(index);
            if (value == '\r' || value == '\n' || value == '\t'
                    || !Character.isISOControl(value)) {
                output.append(value);
            } else {
                output.append(' ');
            }
        }
    }

    private int littleEndianInt(byte[] bytes, int offset) {
        return (bytes[offset] & 0xFF)
                | ((bytes[offset + 1] & 0xFF) << 8)
                | ((bytes[offset + 2] & 0xFF) << 16)
                | ((bytes[offset + 3] & 0xFF) << 24);
    }

    private String normalize(String text) {
        return text == null ? "" : text
                .replace('\u00A0', ' ')
                .replace("\r\n", "\n")
                .replace('\r', '\n')
                .lines()
                .map(String::strip)
                .filter(line -> !line.isBlank())
                .reduce((left, right) -> left + "\n" + right)
                .orElse("")
                .trim();
    }
}
