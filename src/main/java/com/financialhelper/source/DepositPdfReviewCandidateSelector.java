package com.financialhelper.source;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Prepares review candidates only; it neither creates a V38 review nor approves evidence. */
public final class DepositPdfReviewCandidateSelector {
    private static final List<Integer> REVIEWED_QUESTIONS = List.of(1, 2, 3, 5, 6, 7, 8, 9, 10);
    private static final Map<Integer, List<String>> REQUIRED_CONTEXT = Map.of(
            2, List.of("보호상품으로 운용되는 경우", "주식", "펀드"),
            6, List.of("이자", "약정이율", "중앙회가 정하는 이율"),
            10, List.of("보호상품으로 운용되는 금액", "사고보험금", "미지급된", "※ (예)"));

    public List<Candidate> select(SourceDocument document, List<SourceChunk> chunks) {
        if (document == null || document.getStatus() != SourceDocumentStatus.ACTIVE
                || !VerifiedPdfMetadata.DEPOSIT_PDF_URL.equals(document.getCanonicalUrl())
                || !VerifiedPdfMetadata.DEPOSIT_RAW_SHA256.equals(document.getRawSha256())
                || !"예금보호한도 상향 관련 주요 QA".equals(document.getTitle())
                || !LocalDate.of(2025, 7, 22).equals(document.getPublishedAt())
                || chunks == null || chunks.size() != 11) {
            throw new IllegalStateException("deposit PDF review candidate identity is unverified");
        }
        String content = document.getNormalizedContent();
        if (content == null || content.isBlank()) {
            throw new IllegalStateException("deposit PDF content is unavailable");
        }
        List<Candidate> candidates = new ArrayList<>();
        for (int sequence = 0; sequence < chunks.size(); sequence++) {
            SourceChunk chunk = chunks.get(sequence);
            int question = sequence;
            if (chunk == null || chunk.getId() == null
                    || chunk.getSourceDocument() == null
                    || !document.getId().equals(chunk.getSourceDocument().getId())
                    || chunk.getSourceStartOffset() < 0
                    || chunk.getSourceEndOffset() > content.length()
                    || chunk.getSourceEndOffset() <= chunk.getSourceStartOffset()
                    || chunk.getSequence() != sequence
                    || (chunk.getReviewStatus() != SourceChunkReviewStatus.PENDING
                    && chunk.getReviewStatus() != SourceChunkReviewStatus.APPROVED)
                    || !chunk.getBody().equals(content.substring(chunk.getSourceStartOffset(),
                    chunk.getSourceEndOffset()))
                    || !("normalized-content:utf16:" + chunk.getSourceStartOffset() + "-"
                    + chunk.getSourceEndOffset()).equals(chunk.getLocator())) {
                throw new IllegalStateException("deposit PDF chunk identity is unverified");
            }
            if (question == 0) {
                if (chunk.getParentSection() != null) {
                    throw new IllegalStateException("deposit PDF preamble boundary is unverified");
                }
                continue;
            }
            if (chunk.getParentSection() == null
                    || !chunk.getParentSection().startsWith(question + ". ")
                    || !chunk.getBody().startsWith(question + ". ")
                    || chunk.getPageReference() == null) {
                throw new IllegalStateException("deposit PDF question boundary is unverified");
            }
            if (!REVIEWED_QUESTIONS.contains(question)) continue;
            List<String> required = REQUIRED_CONTEXT.getOrDefault(question, List.of());
            if (required.stream().anyMatch(text -> !chunk.getBody().contains(text))) {
                throw new IllegalStateException("deposit PDF mandatory context is unavailable for question "
                        + question);
            }
            candidates.add(new Candidate(question, chunk.getId(), chunk.getPageReference(),
                    chunk.getLocator(), chunk.getBody(), required));
        }
        if (candidates.size() != REVIEWED_QUESTIONS.size()) {
            throw new IllegalStateException("deposit PDF review candidates are incomplete");
        }
        return List.copyOf(candidates);
    }

    public record Candidate(int question, java.util.UUID supportChunkId, String pageReference,
                            String locator, String body, List<String> mandatoryContextPassages) {
        public Candidate {
            mandatoryContextPassages = List.copyOf(mandatoryContextPassages);
        }
    }
}
