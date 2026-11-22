package com.financialhelper.source;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions
        .assertThat;

import static org.assertj.core.api.Assertions
        .assertThatThrownBy;

class SourceUrlPolicyTest {

    private final SourceUrlPolicy policy =
            new SourceUrlPolicy();

    @Test void canonicalizesTrackingAndFragmentWithoutLosingDocumentIdentity() {
        var raw = "https://WWW.korea.kr:443/news/policyNewsView.do?utm_source=test&newsId=123456789&fbclid=x#section";
        var canonical = policy.normalizeRegisteredUrl(raw, "korea.kr");
        assertThat(canonical.toString()).isEqualTo("https://www.korea.kr/news/policyNewsView.do?newsId=123456789");
        assertThat(policy.normalizeRegisteredUrl(canonical.toString(), "korea.kr")).isEqualTo(canonical);
        assertThat(KoreaPolicyNews.allowed(canonical)).isTrue();
        assertThat(KoreaPolicyNews.allowed(policy.normalizeRegisteredUrl(raw.replace("&fbclid=x", "&unknown=1"), "korea.kr"))).isFalse();
        assertThatThrownBy(() -> policy.normalizeRegisteredUrl("https://www.korea.kr.evil.invalid/news?newsId=123456789#x", "korea.kr"))
                .isInstanceOf(SourceIngestionException.class);
        assertThatThrownBy(() -> policy.normalizeRegisteredUrl("https://user@www.korea.kr/news?newsId=123456789#x", "korea.kr"))
                .isInstanceOf(SourceIngestionException.class);
        assertThat(policy.normalizeRegisteredUrl("https://www.fsc.go.kr/no010101/1?doc=2&utm_medium=x#part", "fsc.go.kr").getRawQuery()).isEqualTo("doc=2");
    }

    // 공식 도메인의 HTTPS 하위 도메인은 허용되는지 확인
    @Test
    void allowsHttpsSubdomainOfOfficialDomain() {

        assertThat(
                policy.validate(
                        "https://www.fsc.go.kr/no010101/86271",
                        "fsc.go.kr"
                ).getHost()
        )
                .isEqualTo(
                        "www.fsc.go.kr"
                );
    }

    // 공식 도메인을 가장한 유사 도메인은 차단되는지 확인
    @Test
    void rejectsLookalikeDomain() {

        assertThatThrownBy(() ->
                policy.validate(
                        "https://fsc.go.kr.example.com/notice",
                        "fsc.go.kr"
                )
        )
                .isInstanceOf(
                        SourceIngestionException.class
                )
                .hasMessageContaining(
                        "outside the configured official domain"
                );
    }

    // HTTPS가 아닌 URL은 차단되는지 확인
    @Test
    void rejectsNonHttpsUrl() {

        assertThatThrownBy(() ->
                policy.validate(
                        "http://www.fsc.go.kr/notice",
                        "fsc.go.kr"
                )
        )
                .isInstanceOf(
                        SourceIngestionException.class
                );
    }

    @Test
    void rejectsPrivateLiteralAndUnexpectedPort() {
        assertThatThrownBy(() -> policy.validate("https://127.0.0.1/internal", "127.0.0.1"))
                .isInstanceOf(SourceIngestionException.class);
        assertThatThrownBy(() -> policy.validate("https://www.fsc.go.kr:15432/internal", "fsc.go.kr"))
                .isInstanceOf(SourceIngestionException.class);
    }
}
