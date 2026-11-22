package com.financialhelper.source;

import org.jsoup.Jsoup;
import org.junit.jupiter.api.Test;
import java.net.URI;
import static org.assertj.core.api.Assertions.*;

class KoreaPolicyNewsTest {
    @Test void licenseRequiresAnUnambiguousArticleTypeOneTextMark() {
        String mark = "이 자료는 텍스트에 한하여 공공누리 제1유형(출처표시)의 조건에 따라 이용할 수 있습니다.";
        var marked = Jsoup.parse("<div class='kogl'><div class='guide'><div class='type'>" + mark + "</div></div></div>");
        assertThat(TextOnlySourceRegistrationService.hasTypeOneTextLicense("korea.kr", marked)).isTrue();
        assertThat(TextOnlySourceRegistrationService.hasTypeOneTextLicense("fsc.go.kr", marked)).isFalse();
        assertThat(TextOnlySourceRegistrationService.hasTypeOneTextLicense("korea.kr", Jsoup.parse(mark))).isFalse();
        assertThat(TextOnlySourceRegistrationService.hasTypeOneTextLicense("korea.kr", Jsoup.parse(marked.html().replace("제1유형", "제2유형")))).isFalse();
        marked.selectFirst(".guide").append("<div class='type'>" + mark + "</div>");
        assertThat(TextOnlySourceRegistrationService.hasTypeOneTextLicense("korea.kr", marked)).isFalse();
    }
    @Test void onlyExactPolicyNewsHostPathAndQueryAreAllowed() {
        assertThat(KoreaPolicyNews.allowed(URI.create("https://www.korea.kr/news/policyNewsView.do?newsId=148960978"))).isTrue();
        for (String url : new String[]{"https://other.korea.kr/news/policyNewsView.do?newsId=148960978",
                "https://www.korea.kr/other?newsId=148960978", "https://www.korea.kr/news/policyNewsView.do?newsId=148960978&x=1",
                "http://www.korea.kr/news/policyNewsView.do?newsId=148960978"})
            assertThat(KoreaPolicyNews.allowed(URI.create(url))).isFalse();
    }

    @Test void requiresUniqueFscAgencyAndBody() {
        var doc = Jsoup.parse("<div class='article_head'><div class='info'>2025.01.01 <a>금융위원회<span>부처별 뉴스 이동</span></a></div></div><div class='article_body'><div class='view_cont'>본문</div></div>");
        assertThat(KoreaPolicyNews.select(doc)).isEqualTo(KoreaPolicyNews.BODY);
        assertThatThrownBy(() -> KoreaPolicyNews.select(Jsoup.parse("<div class='article_head'><div class='info'>다른 기관</div></div><div class='article_body'><div class='view_cont'>본문</div></div>"))).isInstanceOf(IllegalArgumentException.class);
        doc.selectFirst(".article_body").append("<div class='view_cont'>중복</div>");
        assertThatThrownBy(() -> KoreaPolicyNews.select(doc)).isInstanceOf(IllegalArgumentException.class);
    }
}
