package com.financialhelper.source;

import org.junit.jupiter.api.Test;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class DepositPdfReviewCandidateSelectorTest {
    @Test void reviewedQuestionRemainsVisibleWithoutAdmittingQ4OrPreamble() {
        Fixture fixture = fixture(false);
        var candidates = new DepositPdfReviewCandidateSelector().select(fixture.document, fixture.chunks);
        assertThat(candidates).extracting(DepositPdfReviewCandidateSelector.Candidate::question)
                .containsExactly(1, 2, 3, 5, 6, 7, 8, 9, 10);
        assertThat(candidates.getFirst().body()).startsWith("1. ");
        assertThat(candidates.getLast().mandatoryContextPassages())
                .contains("사고보험금", "※ (예)");
    }

    @Test void mergedQuestionBoundaryCannotEnterReviewPacket() {
        Fixture fixture = fixture(true);
        assertThatThrownBy(() -> new DepositPdfReviewCandidateSelector()
                .select(fixture.document, fixture.chunks))
                .isInstanceOf(IllegalStateException.class);
    }

    private static Fixture fixture(boolean mergeQuestionEight) {
        SourceDocument document = mock(SourceDocument.class);
        UUID documentId = UUID.randomUUID();
        when(document.getId()).thenReturn(documentId);
        when(document.getStatus()).thenReturn(SourceDocumentStatus.ACTIVE);
        when(document.getCanonicalUrl()).thenReturn(VerifiedPdfMetadata.DEPOSIT_PDF_URL);
        when(document.getRawSha256()).thenReturn(VerifiedPdfMetadata.DEPOSIT_RAW_SHA256);
        when(document.getTitle()).thenReturn("예금보호한도 상향 관련 주요 QA");
        when(document.getPublishedAt()).thenReturn(LocalDate.of(2025, 7, 22));
        List<SourceChunk> chunks = new ArrayList<>();
        StringBuilder text = new StringBuilder();
        for (int number = 0; number <= 10; number++) {
            String body = number == 0 ? "머리말\n" : number + ". 문답 본문 ";
            if (number == 2) body += "보호상품으로 운용되는 경우 주식 펀드 ";
            if (number == 6) body += "이자 약정이율 중앙회가 정하는 이율 ";
            if (number == 10) body += "보호상품으로 운용되는 금액 사고보험금 미지급된 ※ (예) ";
            int start = text.length();
            text.append(body);
            int end = text.length();
            SourceChunk chunk = mock(SourceChunk.class);
            when(chunk.getId()).thenReturn(UUID.randomUUID());
            when(chunk.getSourceDocument()).thenReturn(document);
            when(chunk.getSourceStartOffset()).thenReturn(start);
            when(chunk.getSourceEndOffset()).thenReturn(end);
            when(chunk.getSequence()).thenReturn(number);
            when(chunk.getReviewStatus()).thenReturn(number == 1
                    ? SourceChunkReviewStatus.APPROVED : SourceChunkReviewStatus.PENDING);
            when(chunk.getBody()).thenReturn(body);
            when(chunk.getLocator()).thenReturn("normalized-content:utf16:" + start + "-" + end);
            if (number > 0) {
                when(chunk.getParentSection()).thenReturn(
                        mergeQuestionEight && number == 8 ? "7. merged" : number + ". 질문");
                when(chunk.getPageReference()).thenReturn("page:1");
            }
            chunks.add(chunk);
        }
        when(document.getNormalizedContent()).thenReturn(text.toString());
        return new Fixture(document, chunks);
    }

    private record Fixture(SourceDocument document, List<SourceChunk> chunks) { }
}
