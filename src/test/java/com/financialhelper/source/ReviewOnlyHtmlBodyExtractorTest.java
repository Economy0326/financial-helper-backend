package com.financialhelper.source;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReviewOnlyHtmlBodyExtractorTest {
    private final ReviewOnlyHtmlBodyExtractor extractor = new ReviewOnlyHtmlBodyExtractor();

    @Test void previewSeparatesArticleFromUiAndKeepsConditionsWithoutApprovingRights() {
        String html = "<nav>메뉴</nav><article id='body'><h2>합성 대출</h2><p>대출 대상입니다.</p>"
                + "<p>다만 기존 계약은 제외합니다.</p><figure><img src='private'><figcaption>사진 정보</figcaption></figure>"
                + "<script>비밀</script></article><footer>광고</footer>";
        var preview = extractor.extract(html, "#body");
        assertThat(preview.normalizedText()).contains("대출 대상입니다.", "다만 기존 계약은 제외합니다.")
                .doesNotContain("메뉴", "사진 정보", "비밀", "광고");
        assertThat(preview.contentSha256()).isEqualTo(SourceHashing.sha256(preview.normalizedText()));
        assertThat(preview.status()).isEqualTo("CONTENT_REVIEW_REQUIRED");
    }

    @Test void missingAmbiguousAndEmptyBodyFailClosed() {
        assertThatThrownBy(() -> extractor.extract("<div>본문</div>", "article"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> extractor.extract("<article>가</article><article>나</article>", "article"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> extractor.extract("<article><img src='x'></article>", "article"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
