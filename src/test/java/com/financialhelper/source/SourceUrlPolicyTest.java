package com.financialhelper.source;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions
        .assertThat;

import static org.assertj.core.api.Assertions
        .assertThatThrownBy;

class SourceUrlPolicyTest {

    private final SourceUrlPolicy policy =
            new SourceUrlPolicy();

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
}