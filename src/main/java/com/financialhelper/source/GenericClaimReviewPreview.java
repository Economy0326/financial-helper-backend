package com.financialhelper.source;

import java.util.ArrayList;
import java.util.List;

/** Read-only adapter. Its output is neither persisted nor eligible for review or READY. */
public final class GenericClaimReviewPreview {
    private final GenericClaimCandidateExtractor extractor = new GenericClaimCandidateExtractor();

    public List<Preview> prepare(SourceDocument document, String configVersion, String configJson) {
        if (configVersion == null || configVersion.isBlank() || configJson == null || configJson.isBlank()) {
            throw new IllegalArgumentException("Chunk configuration is required");
        }
        List<Preview> result = new ArrayList<>();
        int sequence = 0;
        for (var candidate : extractor.extractNewDocument(document)) {
            var excerpt = candidate.excerpt();
            var definition = new SourceChunkData.Definition(configVersion, configJson, sequence++, excerpt.text(),
                    null, null, candidate.page() == null ? null : "page:" + candidate.page(), excerpt.locator(),
                    excerpt.start(), excerpt.end(), "{}");
            // Relations remain attached to the candidate; they are not independently approved chunks.
            result.add(new Preview(candidate.source(), definition, candidate.context(), candidate.related(),
                    candidate.warnings(), candidate.status()));
        }
        return List.copyOf(result);
    }

    public record Preview(GenericClaimCandidateExtractor.Identity source, SourceChunkData.Definition support,
            GenericClaimCandidateExtractor.Span context, List<GenericClaimCandidateExtractor.Related> related,
            List<String> warnings, GenericClaimCandidateExtractor.ReviewStatus status) { }
}
