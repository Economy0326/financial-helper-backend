package com.financialhelper.source;

import org.springframework.stereotype.Service;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Structure-first chunk preparation for an immutable SourceDocument version.
 * Token counting is delegated to the pinned KURE tokenizer runtime.
 */
@Service
public class SourceChunkingService {

    private static final Pattern ARTICLE_HEADING = Pattern.compile(
            "^제\\s*[0-9]+조(?:의[0-9]+)?(?:\\s|\\(|$).*"
    );

    private static final Pattern PAGE_MARKER = Pattern.compile(
            "\\[PAGE\\s+(\\d+)\\]"
    );

    private static final Pattern SECTION_HEADING = Pattern.compile(
            "^(?:제\\s*[0-9]+\\s*(?:장|절|관|편)|부칙|별표)(?:\\s|$).*"
    );

    private static final Pattern ARTICLE_REFERENCE = Pattern.compile(
            "^(제\\s*[0-9]+조(?:의[0-9]+)?(?:\\([^\\r\\n]*?\\))?)"
    );

    private final SourceChunkPersistenceService persistenceService;
    private final SourceChunkTokenizer tokenizer;
    private final SourceChunkingProperties properties;
    private final JsonMapper jsonMapper;

    public SourceChunkingService(
            SourceChunkPersistenceService persistenceService,
            SourceChunkTokenizer tokenizer,
            SourceChunkingProperties properties,
            JsonMapper jsonMapper
    ) {
        this.persistenceService = persistenceService;
        this.tokenizer = tokenizer;
        this.properties = properties;
        this.jsonMapper = jsonMapper;
    }

    /**
     * Chunk one immutable document version.  Repeated calls use the existing
     * SourceChunk persistence idempotency key and never inherit review state
     * from another document version.
     */
    public List<SourceChunk> chunk(SourceDocument sourceDocument) {
        if (sourceDocument == null || sourceDocument.getId() == null) {
            throw new IllegalArgumentException(
                    "sourceDocument must be persisted"
            );
        }

        String normalizedContent = sourceDocument.getNormalizedContent();
        if (normalizedContent == null || normalizedContent.isBlank()) {
            throw new SourceChunkingException(
                    "source document normalized content must not be blank"
            );
        }

        if (!properties.tokenizerIdentifier().equals(tokenizer.identifier())
                || !properties.tokenizerRevision().equals(tokenizer.revision())) {
            throw new SourceChunkingException(
                    "chunking tokenizer does not match the configured KURE tokenizer"
            );
        }

        String configurationJson = configurationJson();
        List<StructuralUnit> units = structuralUnits(normalizedContent);
        List<SourceChunk> chunks = new ArrayList<>();
        int sequence = 0;

        for (StructuralUnit unit : units) {
            for (Segment segment : split(unit, normalizedContent)) {
                String body = normalizedContent.substring(
                        segment.startOffset(),
                        segment.endOffset()
                );
                String metadataJson = metadataJson(unit, segment.tokenCount());

                chunks.add(
                        persistenceService.saveIfAbsent(
                                sourceDocument,
                                new SourceChunkData.Definition(
                                        properties.configVersion(),
                                        configurationJson,
                                        sequence++,
                                        body,
                                        unit.parentSection(),
                                        unit.articleReference(),
                                        pageReference(
                                                normalizedContent,
                                                segment.startOffset(),
                                                segment.endOffset()
                                        ),
                                        "normalized-content:utf16:"
                                                + segment.startOffset()
                                                + "-"
                                                + segment.endOffset(),
                                        segment.startOffset(),
                                        segment.endOffset(),
                                        metadataJson
                                )
                        )
                );
            }
        }

        if (chunks.isEmpty()) {
            throw new SourceChunkingException(
                    "source document produced no chunks"
            );
        }

        return List.copyOf(chunks);
    }

    private String configurationJson() {
        try {
            return jsonMapper.writeValueAsString(
                    Map.of(
                            "strategy", properties.configVersion(),
                            "targetTokens", properties.targetTokens(),
                            "maxTokens", properties.maxTokens(),
                            "overlapTokens", properties.overlapTokens(),
                            "tokenizerIdentifier", tokenizer.identifier(),
                            "tokenizerRevision", tokenizer.revision(),
                            "configuredTokenizerIdentifier",
                            properties.tokenizerIdentifier(),
                            "configuredTokenizerRevision",
                            properties.tokenizerRevision()
                    )
            );
        } catch (JacksonException exception) {
            throw new SourceChunkingException(
                    "could not serialize chunk configuration",
                    exception
            );
        }
    }

    private String metadataJson(
            StructuralUnit unit,
            int tokenCount
    ) {
        try {
            return jsonMapper.writeValueAsString(
                    Map.of(
                            "chunker", properties.configVersion(),
                            "tokenCount", tokenCount,
                            "tokenizerIdentifier", tokenizer.identifier(),
                            "tokenizerRevision", tokenizer.revision(),
                            "parentSectionPreserved",
                            unit.parentSection() != null,
                            "articleReferencePreserved",
                            unit.articleReference() != null,
                            "conditionAndExceptionTextPreserved", true
                    )
            );
        } catch (JacksonException exception) {
            throw new SourceChunkingException(
                    "could not serialize chunk metadata",
                    exception
            );
        }
    }

    private List<StructuralUnit> structuralUnits(String content) {
        List<Line> lines = lines(content);
        if (lines.isEmpty()) {
            return List.of();
        }

        List<StructuralUnit> units = new ArrayList<>();
        String parentSection = null;
        String articleReference = null;
        int unitStart = -1;
        int unitEnd = -1;

        for (Line line : lines) {
            boolean section = SECTION_HEADING.matcher(line.text()).matches();
            boolean article = ARTICLE_HEADING.matcher(line.text()).matches();

            if (section) {
                // Keep a section heading with the following article so the
                // parent locator is retained without creating a heading-only
                // retrieval hit.
                if (unitStart >= 0 && articleReference != null) {
                    units.add(
                            new StructuralUnit(
                                    unitStart,
                                    unitEnd,
                                    parentSection,
                                    articleReference
                            )
                    );
                }

                unitStart = line.startOffset();
                unitEnd = line.endOffset();
                parentSection = line.text();
                articleReference = null;

                continue;
            }

            if (article) {
                if (unitStart >= 0 && articleReference != null) {
                    units.add(
                            new StructuralUnit(
                                    unitStart,
                                    unitEnd,
                                    parentSection,
                                    articleReference
                            )
                    );
                    unitStart = line.startOffset();
                } else if (unitStart < 0) {
                    unitStart = line.startOffset();
                }
                unitEnd = line.endOffset();
                articleReference = normalizedArticleReference(line.text());

                continue;
            }

            if (unitStart < 0) {
                unitStart = line.startOffset();
            }
            unitEnd = line.endOffset();
        }

        if (unitStart >= 0) {
            units.add(
                    new StructuralUnit(
                            unitStart,
                            unitEnd,
                            parentSection,
                            articleReference
                    )
            );
        }

        return units;
    }

    /**
     * HWP paragraphs can contain an entire article clause on the same line as
     * its heading.  Keep the full line in the chunk body, but keep the
     * article locator bounded to the schema's 255-character column.
     */
    private String normalizedArticleReference(String line) {
        if (line.length() <= 255) {
            return line;
        }
        Matcher matcher = ARTICLE_REFERENCE.matcher(line);
        if (!matcher.find()) {
            throw new SourceChunkingException(
                    "article reference exceeds the configured locator length"
            );
        }
        return matcher.group(1);
    }

    private List<Segment> split(
            StructuralUnit unit,
            String content
    ) {
        List<Line> unitLines = lines(
                content.substring(unit.startOffset(), unit.endOffset())
        );
        if (unitLines.isEmpty()) {
            return List.of();
        }

        List<Segment> segments = new ArrayList<>();
        int baseOffset = unit.startOffset();
        int segmentStart = baseOffset + unitLines.get(0).startOffset();
        int segmentEnd = baseOffset + unitLines.get(0).endOffset();
        int segmentTokens = countTokens(
                content.substring(segmentStart, segmentEnd)
        );

        if (segmentTokens > properties.maxTokens()) {
            return splitOversizedLine(segmentStart, segmentEnd, content);
        }

        for (int index = 1; index < unitLines.size(); index++) {
            Line line = unitLines.get(index);
            int candidateEnd = baseOffset + line.endOffset();
            int candidateTokens = countTokens(
                    content.substring(segmentStart, candidateEnd)
            );

            boolean targetReached =
                    segmentTokens >= properties.targetTokens();

            if (candidateTokens > properties.maxTokens()
                    || (targetReached
                    && candidateTokens > properties.targetTokens())) {
                segments.add(
                        new Segment(
                                segmentStart,
                                segmentEnd,
                                segmentTokens
                        )
                );
                segmentStart = baseOffset + line.startOffset();
                segmentEnd = candidateEnd;
                segmentTokens = countTokens(
                        content.substring(segmentStart, segmentEnd)
                );

                if (segmentTokens > properties.maxTokens()) {
                    throw new SourceChunkingException(
                            "one structural line exceeds the configured token limit"
                    );
                }
            } else {
                segmentEnd = candidateEnd;
                segmentTokens = candidateTokens;
            }
        }

        segments.add(
                new Segment(
                        segmentStart,
                        segmentEnd,
                        segmentTokens
                )
        );
        return segments;
    }

    /**
     * Some official HTML pages expose their whole article as one text node.
     * Keep the original offsets and split only at punctuation/whitespace
     * boundaries when that structural line exceeds the token budget.  This is
     * deliberately a boundary split, never a substring truncation.
     */
    private List<Segment> splitOversizedLine(
            int startOffset,
            int endOffset,
            String content
    ) {
        List<Segment> segments = new ArrayList<>();
        int cursor = startOffset;
        while (cursor < endOffset) {
            int boundary = largestSafeBoundary(cursor, endOffset, content);
            if (boundary <= cursor) {
                throw new SourceChunkingException(
                        "one structural token exceeds the configured token limit"
                );
            }
            int bodyEnd = trimTrailingWhitespace(boundary, cursor, content);
            if (bodyEnd <= cursor) {
                cursor = boundary;
                continue;
            }
            segments.add(new Segment(
                    cursor,
                    bodyEnd,
                    countTokens(content.substring(cursor, bodyEnd))
            ));
            cursor = skipWhitespace(boundary, endOffset, content);
        }
        return segments;
    }

    private int largestSafeBoundary(int startOffset, int endOffset, String content) {
        int low = startOffset + 1;
        int high = endOffset;
        int safe = -1;
        while (low <= high) {
            int middle = low + ((high - low) / 2);
            if (countTokens(content.substring(startOffset, middle))
                    <= properties.maxTokens()) {
                safe = middle;
                low = middle + 1;
            } else {
                high = middle - 1;
            }
        }
        if (safe < 0) {
            return -1;
        }
        for (int index = safe; index > startOffset; index--) {
            char value = content.charAt(index - 1);
            if (Character.isWhitespace(value) || isSentenceBoundary(value)) {
                return index;
            }
        }
        return safe;
    }

    private boolean isSentenceBoundary(char value) {
        return value == '.' || value == '!' || value == '?' || value == '。'
                || value == '；' || value == ';';
    }

    private int trimTrailingWhitespace(int boundary, int startOffset, String content) {
        int result = boundary;
        while (result > startOffset && Character.isWhitespace(content.charAt(result - 1))) {
            result--;
        }
        return result;
    }

    private int skipWhitespace(int offset, int endOffset, String content) {
        int result = offset;
        while (result < endOffset && Character.isWhitespace(content.charAt(result))) {
            result++;
        }
        return result;
    }

    private int countTokens(String text) {
        int count = tokenizer.countDocumentTokens(text);
        if (count <= 0) {
            throw new SourceChunkingException(
                    "KURE tokenizer returned no document tokens"
            );
        }
        return count;
    }

    /** Reads only explicit page markers emitted by a document parser. */
    private String pageReference(String content, int startOffset, int endOffset) {
        int firstPage = -1;
        int lastPage = -1;
        var matcher = PAGE_MARKER.matcher(content);
        while (matcher.find() && matcher.start() < endOffset) {
            int page = Integer.parseInt(matcher.group(1));
            if (matcher.end() <= startOffset) {
                firstPage = page;
                lastPage = page;
                continue;
            }
            if (firstPage < 0) {
                firstPage = page;
            }
            lastPage = page;
        }
        if (firstPage < 0) {
            return null;
        }
        return firstPage == lastPage
                ? "p." + firstPage
                : "p." + firstPage + "-" + lastPage;
    }

    private List<Line> lines(String content) {
        List<Line> lines = new ArrayList<>();
        int start = 0;
        while (start <= content.length()) {
            int newline = content.indexOf('\n', start);
            int end = newline < 0 ? content.length() : newline;
            if (!content.substring(start, end).isBlank()) {
                lines.add(
                        new Line(
                                start,
                                end,
                                content.substring(start, end)
                        )
                );
            }
            if (newline < 0) {
                break;
            }
            start = newline + 1;
        }
        return lines;
    }

    private record Line(int startOffset, int endOffset, String text) {
    }

    private record StructuralUnit(
            int startOffset,
            int endOffset,
            String parentSection,
            String articleReference
    ) {
    }

    private record Segment(
            int startOffset,
            int endOffset,
            int tokenCount
    ) {
    }
}
