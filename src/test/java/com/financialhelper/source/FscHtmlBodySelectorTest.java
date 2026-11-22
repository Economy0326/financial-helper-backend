package com.financialhelper.source;

import org.jsoup.Jsoup;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class FscHtmlBodySelectorTest {
    @Test void selectsOnlySingleArticleBody() {
        String html = "<nav>합성 메뉴</nav><div class='body'><div class='cont'><p>합성 정책은 조건에 따라 적용됩니다.</p><figure>캡션<img src='x'></figure></div></div><footer>합성 주변 정보</footer>";
        String selector = FscHtmlBodySelector.select(Jsoup.parse(html));
        var preview = new ReviewOnlyHtmlBodyExtractor().extract(html, selector);
        assertThat(preview.normalizedText()).contains("합성 정책은").doesNotContain("메뉴", "캡션", "주변 정보");
        assertThat(preview.contentSha256()).isEqualTo(SourceHashing.sha256(preview.normalizedText()));
    }

    @Test void rejectsMissingOrMultipleBodies() {
        assertThatThrownBy(() -> FscHtmlBodySelector.select(Jsoup.parse("<div>empty</div>"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> FscHtmlBodySelector.select(Jsoup.parse("<div class='body'><div class='cont'>a</div><div class='cont'>b</div></div>"))).isInstanceOf(IllegalArgumentException.class);
    }
}
