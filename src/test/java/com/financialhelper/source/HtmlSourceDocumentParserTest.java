package com.financialhelper.source;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions
        .assertThat;

import static org.assertj.core.api.Assertions
        .assertThatThrownBy;

class HtmlSourceDocumentParserTest {

    private final HtmlSourceDocumentParser parser =
            new HtmlSourceDocumentParser();

    // 공식 HTML에서 본문, 제목, 게시일을 추출하고 불필요한 내용을 제거하는지 확인
    @Test
    void extractsNormalizedOfficialHtmlContent() {

        String html =
                """
                <!doctype html>
                <html>
                <head>
                  <title>공식 안내</title>
                  <meta
                    property="article:published_time"
                    content="2026-09-10T09:00:00+09:00">
                </head>
                <body>

                  <nav>
                    메뉴 메뉴 메뉴
                  </nav>

                  <main>
                    <h1>
                      공식 안내
                    </h1>

                    <p>
                      이 문장은 공식 원문의 본문입니다.
                    </p>

                    <p>
                      충분한 길이 검증을 위해 실제 문서처럼
                      여러 안내 문장을 넣습니다.
                    </p>

                    <p>
                      이 데이터는 이후 SourceChunk 생성 전에
                      원문 provenance와 함께 저장됩니다.
                    </p>

                    <p>
                      스크립트 내용은 정규화 본문에
                      포함되면 안 됩니다.
                    </p>

                    <script>
                      secret-script-content
                    </script>
                  </main>

                </body>
                </html>
                """;

        SourceIngestionData.Snapshot snapshot =
                new SourceIngestionData.Snapshot(
                        UUID.randomUUID(),
                        "test-source",
                        "테스트 기관",
                        "example.go.kr",
                        "https://example.go.kr/notice/1",
                        SourceAcquisitionType.HTML,

                        // 별도 selector가 없으므로 generic selector 사용
                        null
                );

        byte[] raw =
                html.getBytes(
                        StandardCharsets.UTF_8
                );

        SourceIngestionData.Fetched fetched =
                new SourceIngestionData.Fetched(
                        snapshot.canonicalUrl(),
                        "text/html;charset=UTF-8",
                        "etag-1",
                        null,
                        raw,
                        OffsetDateTime.of(
                                2026,
                                9,
                                11,
                                0,
                                0,
                                0,
                                0,
                                ZoneOffset.UTC
                        )
                );

        SourceIngestionData.Parsed parsed =
                parser.parse(
                        snapshot,
                        fetched
                );

        assertThat(
                parsed.title()
        )
                .isEqualTo(
                        "공식 안내"
                );

        assertThat(
                parsed.publishedAt()
        )
                .isEqualTo(
                        LocalDate.of(
                                2026,
                                9,
                                10
                        )
                );

        assertThat(
                parsed.normalizedContent()
        )
                .contains(
                        "공식 원문의 본문"
                )

                .doesNotContain(
                        "secret-script-content"
                )

                .doesNotContain(
                        "메뉴 메뉴 메뉴"
                );

        assertThat(
                parsed.contentSha256()
        )
                .hasSize(64);

        assertThat(
                parsed.rawSha256()
        )
                .hasSize(64);
    }

    // 설정된 CSS selector의 본문만 추출하고 주변의 다른 게시물은 제외하는지 확인
    @Test
    void configuredContentSelectorExtractsOnlyTargetContent() {

        String html =
                """
                <!doctype html>
                <html>
                <head>
                    <title>카드뉴스</title>
                </head>
                <body>

                    <main>

                        <div class="photo-list-body">

                            <h1>
                                카드 분실 대응 안내
                            </h1>

                            <div class="description">
                                신용카드를 분실했다면 즉시 카드사에 신고해야 합니다.
                                제3자가 카드를 습득하여 부정사용할 가능성이 있기 때문입니다.
                                카드 부정사용이 의심되는 경우에는 거래 내역을 확인해야 합니다.
                                금융소비자는 공식 안내에 따라 필요한 보호 조치를 진행해야 합니다.
                                추가 피해를 방지하기 위해 신속하게 카드사와 관련 기관에 신고해야 합니다.
                            </div>

                        </div>

                        <div class="related-list">
                            다른 게시물 제목입니다.
                            최신 금융뉴스입니다.
                            이전 게시물입니다.
                            다음 게시물입니다.
                            페이지 1 2 3 4 5
                        </div>

                    </main>

                </body>
                </html>
                """;

        SourceIngestionData.Snapshot snapshot =
                new SourceIngestionData.Snapshot(
                        UUID.randomUUID(),
                        "fsc-card",
                        "금융위원회",
                        "fsc.go.kr",
                        "https://www.fsc.go.kr/edu/cardnews?cnId=test",
                        SourceAcquisitionType.HTML,

                        // 실제 FSC 카드뉴스에서 확인한 본문 selector
                        ".photo-list-body"
                );

        SourceIngestionData.Fetched fetched =
                fetched(
                        snapshot,
                        html
                );

        SourceIngestionData.Parsed parsed =
                parser.parse(
                        snapshot,
                        fetched
                );

        assertThat(
                parsed.normalizedContent()
        )
                .contains(
                        "카드 분실 대응 안내"
                )

                .contains(
                        "신용카드를 분실했다면"
                )

                .doesNotContain(
                        "다른 게시물 제목입니다."
                )

                .doesNotContain(
                        "페이지 1 2 3 4 5"
                );
    }

    // 설정한 CSS selector가 사라지면 전체 body로 fallback하지 않고 실패하는지 확인
    @Test
    void missingConfiguredContentSelectorFails() {

        String html =
                """
                <!doctype html>
                <html>
                <head>
                    <title>변경된 페이지</title>
                </head>
                <body>

                    <main>

                        <div class="changed-layout">
                            공식 페이지의 DOM 구조가 변경된 상황을 가정합니다.
                            이 내용 자체는 충분히 길지만 기존에 설정했던 selector는 존재하지 않습니다.
                            금융 근거 데이터에서는 이런 경우 전체 페이지를 임의로 저장하면 안 됩니다.
                            selector가 사라졌다면 명확한 수집 실패로 처리해야 합니다.
                            그래야 잘못된 navigation이나 다른 게시물이 근거로 저장되는 일을 막을 수 있습니다.
                        </div>

                    </main>

                </body>
                </html>
                """;

        SourceIngestionData.Snapshot snapshot =
                new SourceIngestionData.Snapshot(
                        UUID.randomUUID(),
                        "fsc-card",
                        "금융위원회",
                        "fsc.go.kr",
                        "https://www.fsc.go.kr/edu/cardnews?cnId=test",
                        SourceAcquisitionType.HTML,

                        // HTML에는 존재하지 않는 selector
                        ".photo-list-body"
                );

        SourceIngestionData.Fetched fetched =
                fetched(
                        snapshot,
                        html
                );

        assertThatThrownBy(() ->
                parser.parse(
                        snapshot,
                        fetched
                )
        )
                .isInstanceOf(
                        SourceIngestionException.class
                )

                .hasMessageContaining(
                        "Configured content selector was not found"
                );
    }

    private SourceIngestionData.Fetched fetched(
            SourceIngestionData.Snapshot snapshot,
            String html
    ) {

        byte[] raw =
                html.getBytes(
                        StandardCharsets.UTF_8
                );

        return new SourceIngestionData.Fetched(
                snapshot.canonicalUrl(),
                "text/html;charset=UTF-8",
                "etag-test",
                null,
                raw,
                OffsetDateTime.of(
                        2026,
                        9,
                        11,
                        0,
                        0,
                        0,
                        0,
                        ZoneOffset.UTC
                )
        );
    }

}