package com.financialhelper.source;

import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class GenericClaimReviewPreviewTest {
    private final GenericClaimReviewPreview preview = new GenericClaimReviewPreview();

    @Test void preservesOriginalOffsetAndExceptionWithoutMakingAnApprovedChunk() {
        String text = "대출 안내\n2025년 1월 1일 이후 신규 대출에 적용됩니다. 다만 기존 계약은 제외합니다.";
        var result = preview.prepare(document(text), "synthetic-v1", "{}");
        assertThat(result).hasSize(1);
        var item = result.getFirst();
        assertThat(item.support().body()).isEqualTo(text.substring(item.support().sourceStartOffset(), item.support().sourceEndOffset()));
        assertThat(item.context().text()).contains("다만 기존 계약은 제외합니다.");
        assertThat(item.related()).anyMatch(r -> r.relation() == GenericClaimCandidateExtractor.Relation.EXCEPTION);
        assertThat(item.status()).isEqualTo(GenericClaimCandidateExtractor.ReviewStatus.REVIEW_REQUIRED);
    }

    @Test void holdsAmbiguousExceptionAndReturnsNoCandidatesForUnrelatedContent() {
        assertThat(preview.prepare(document("오늘은 날씨가 좋습니다."), "synthetic-v1", "{}")).isEmpty();
        var items = preview.prepare(document("예금 안내\n예금 보호 대상은 별도 조건을 따릅니다.\n\n다만 일부 계좌는 제외합니다."), "synthetic-v1", "{}");
        assertThat(items).hasSize(1);
        assertThat(items.getFirst().status()).isEqualTo(GenericClaimCandidateExtractor.ReviewStatus.HOLD);
        assertThat(items.getFirst().related()).anyMatch(GenericClaimCandidateExtractor.Related::ambiguous);
    }

    @Test void rejectsChangedContentAndDoesNotMixVersions() {
        SourceDocument doc = document("보험 안내\n보험 청구는 공식 채널에서 신청할 수 있습니다.");
        when(doc.getContentSha256()).thenReturn("0".repeat(64));
        assertThatThrownBy(() -> preview.prepare(doc, "synthetic-v1", "{}")).isInstanceOf(IllegalArgumentException.class);
    }

    private SourceDocument document(String text) {
        SourceDocument doc = mock(SourceDocument.class);
        SourceRegistry registry = mock(SourceRegistry.class);
        byte[] raw = text.getBytes(StandardCharsets.UTF_8);
        when(registry.getSourceKey()).thenReturn("synthetic-new");
        when(doc.getSourceRegistry()).thenReturn(registry);
        when(doc.getId()).thenReturn(UUID.randomUUID());
        when(doc.getDocumentVersion()).thenReturn(1);
        when(doc.getStatus()).thenReturn(SourceDocumentStatus.ACTIVE);
        when(doc.getCanonicalUrl()).thenReturn("https://synthetic.invalid/doc");
        when(doc.getTitle()).thenReturn("합성 문서");
        when(doc.getOriginalContent()).thenReturn(raw);
        when(doc.getRawSha256()).thenReturn(SourceHashing.sha256(raw));
        when(doc.getNormalizedContent()).thenReturn(text);
        when(doc.getContentSha256()).thenReturn(SourceHashing.sha256(text));
        return doc;
    }
}
