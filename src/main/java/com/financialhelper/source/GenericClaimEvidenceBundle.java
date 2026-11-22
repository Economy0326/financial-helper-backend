package com.financialhelper.source;

import java.util.List;
import java.util.Objects;

/** Prepares immutable candidate evidence; does not persist, approve, index or activate it. */
public final class GenericClaimEvidenceBundle {
    public List<Bundle> prepare(SourceDocument document, String extractorVersion) {
        return prepare(document, extractorVersion, false);
    }
    List<Bundle> prepareHistoricalForComparison(SourceDocument document, String extractorVersion) {
        return prepare(document, extractorVersion, true);
    }
    private List<Bundle> prepare(SourceDocument document, String extractorVersion, boolean historical) {
        if (extractorVersion == null || extractorVersion.isBlank()) {
            throw new IllegalArgumentException("Extractor version is required");
        }
        String text = Objects.requireNonNull(document.getNormalizedContent(), "normalized content");
        var extractor = new GenericClaimCandidateExtractor("generic-claim-v2".equals(extractorVersion) || "generic-claim-v3".equals(extractorVersion), "generic-claim-v3".equals(extractorVersion));
        return (historical ? extractor.extractHistoricalForComparison(document) : extractor.extractNewDocument(document)).stream()
                .map(candidate -> bundle(text, candidate, extractorVersion)).toList();
    }

    private Bundle bundle(String text, GenericClaimCandidateExtractor.Candidate candidate, String version) {
        var excerpt = candidate.excerpt();
        int end = excerpt.end();
        boolean ambiguous = false;
        for (var relation : candidate.related()) {
            var span = relation.span();
            if (span.start() < excerpt.start() || span.end() > candidate.context().end()
                    || !text.substring(span.start(), span.end()).equals(span.text())) {
                throw new IllegalStateException("Evidence relation is outside verified context");
            }
            end = Math.max(end, span.end());
            ambiguous |= relation.ambiguous();
        }
        String body = text.substring(excerpt.start(), end);
        String candidateId = SourceHashing.sha256(String.join("|", candidate.source().documentId().toString(),
                Integer.toString(candidate.source().documentVersion()), candidate.source().rawSha256(),
                candidate.source().contentSha256(), version, Integer.toString(excerpt.start()),
                Integer.toString(end), SourceHashing.sha256(body)));
        boolean hold = ambiguous || candidate.status() == GenericClaimCandidateExtractor.ReviewStatus.HOLD;
        return new Bundle(candidateId, version, candidate.source(), excerpt.start(), end, body,
                candidate.context(), candidate.related(), candidate.warnings(), hold);
    }

    public record Bundle(String candidateId, String extractorVersion, GenericClaimCandidateExtractor.Identity source,
            int start, int end, String body, GenericClaimCandidateExtractor.Span context,
            List<GenericClaimCandidateExtractor.Related> related, List<String> warnings, boolean hold) { }
}
