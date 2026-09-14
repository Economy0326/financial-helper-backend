package com.financialhelper.source;

import org.springframework.stereotype.Service;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Structure-first chunk preparation for an immutable SourceDocument version.
 * Token counting is delegated to the pinned KURE tokenizer runtime.
 */
@Service
public class SourceChunkingService {

    private static final Pattern ARTICLE_HEADING = Pattern.compile(
            "^제\\s*[0-9]+조(?:의[0-9]+)?(?:\\s|$).*"
    );

    private static final Pattern SECTION_HEADING = Pattern.compile(
            "^(?:제\\s*[0-9]+\\s*(?:장|절|관|편)|부칙|별표)(?:\\s|$).*"
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
                                        null,
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
                            "strategy", "structure-first-v1",
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
                            "chunker", "structure-first-v1",
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
                articleReference = line.text();

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
            throw new SourceChunkingException(
                    "one structural line exceeds the configured token limit"
            );
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

    private int countTokens(String text) {
        int count = tokenizer.countDocumentTokens(text);
        if (count <= 0) {
            throw new SourceChunkingException(
                    "KURE tokenizer returned no document tokens"
            );
        }
        return count;
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
