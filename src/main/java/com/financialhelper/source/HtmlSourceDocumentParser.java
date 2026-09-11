package com.financialhelper.source;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;

@Component
public class HtmlSourceDocumentParser {

    // 본문이 너무 짧으면 안됨
    private static final int
            MIN_NORMALIZED_LENGTH = 120;

    // source별 content_selector가 없을 때 사용하는 기본 후보들
    private static final List<String>
            CONTENT_SELECTORS =
            List.of(
                    "main",
                    "article",
                    "[role=main]",
                    "#content",
                    "#contents",
                    ".content",
                    ".contents"
            );

    public SourceIngestionData.Parsed parse(
            SourceIngestionData.Snapshot source,
            SourceIngestionData.Fetched fetched
    ) {

        if (
                source.acquisitionType()
                        != SourceAcquisitionType.HTML
        ) {
            throw new SourceIngestionException(
                    "SOURCE_TYPE_UNSUPPORTED",
                    "Only HTML acquisition is supported in V2-1"
            );
        }

        String contentType =
                fetched.contentType();

        if (
                contentType != null
                        && !contentType
                        .toLowerCase()
                        .contains("html")
        ) {
            throw new SourceIngestionException(
                    "SOURCE_CONTENT_TYPE_UNSUPPORTED",
                    "Official source did not return HTML"
            );
        }

        // byte 형태의 HTML 원본을 jsoup의 document 객체로 변환
        Document document =
                parseDocument(
                        fetched.originalContent(),
                        fetched.resolvedUrl()
                );

        // 실제 의미 있는 본문 텍스트만 최대한 정리해서 뽑기
        // source에 content_selector가 지정되어 있으면 해당 영역을 우선 사용
        String normalizedContent =
                normalizeContent(
                        document,
                        source
                );

        if (
                normalizedContent.length()
                        < MIN_NORMALIZED_LENGTH
        ) {
            throw new SourceIngestionException(
                    "SOURCE_CONTENT_TOO_SHORT",
                    "Official source content is too short to trust as a document"
            );
        }

        return new SourceIngestionData.Parsed(
                fetched.resolvedUrl(),

                extractTitle(
                        document,
                        source.sourceKey()
                ),

                extractPublishedAt(
                        document
                ),

                fetched.retrievedAt(),

                fetched.contentType(),

                fetched.httpEtag(),

                fetched.httpLastModified(),

                // rawSha256
                SourceHashing.sha256(
                        fetched.originalContent()
                ),

                // contentSha256
                SourceHashing.sha256(
                        normalizedContent
                ),

                fetched.originalContent(),

                normalizedContent
        );
    }

    private Document parseDocument(
            byte[] originalContent,
            String baseUri
    ) {

        try (
                ByteArrayInputStream inputStream =
                        new ByteArrayInputStream(
                                originalContent
                        )
        ) {

            return Jsoup.parse(
                    inputStream,
                    null,
                    baseUri
            );

        } catch (
                IOException exception
        ) {

            throw new SourceIngestionException(
                    "SOURCE_HTML_PARSE_FAILED",
                    "Could not parse official HTML source",
                    exception
            );
        }
    }

    private String extractTitle(
            Document document,
            String sourceKey
    ) {

        Element ogTitle =
                document.selectFirst(
                        "meta[property=og:title]"
                );

        if (ogTitle != null) {

            String content =
                    ogTitle.attr(
                            "content"
                    ).trim();

            if (!content.isBlank()) {
                return content;
            }
        }

        String title =
                document.title()
                        .trim();

        if (!title.isBlank()) {
            return title;
        }

        Element heading =
                document.selectFirst(
                        "h1"
                );

        if (
                heading != null
                        && !heading.text()
                        .isBlank()
        ) {
            return heading.text()
                    .trim();
        }

        return sourceKey;
    }

    private LocalDate extractPublishedAt(
            Document document
    ) {

        List<String> selectors =
                List.of(
                        "meta[property=article:published_time]",
                        "meta[name=date]",
                        "meta[name=DC.date]",
                        "meta[itemprop=datePublished]"
                );

        for (
                String selector
                : selectors
        ) {

            Element element =
                    document.selectFirst(
                            selector
                    );

            if (element == null) {
                continue;
            }

            LocalDate date =
                    parseDatePrefix(
                            element.attr(
                                    "content"
                            )
                    );

            if (date != null) {
                return date;
            }
        }

        for (
                Element time
                : document.select(
                        "time[datetime]"
                )
        ) {

            LocalDate date =
                    parseDatePrefix(
                            time.attr(
                                    "datetime"
                            )
                    );

            if (date != null) {
                return date;
            }
        }

        return null;
    }

    // 날짜 문자열에서 앞의 10글자만 보는 메소드
    private LocalDate parseDatePrefix(
            String value
    ) {

        if (value == null) {
            return null;
        }

        String normalized =
                value.trim();

        if (
                normalized.length()
                        < 10
        ) {
            return null;
        }

        try {

            return LocalDate.parse(
                    normalized.substring(
                            0,
                            10
                    )
            );

        } catch (
                DateTimeParseException exception
        ) {

            return null;
        }
    }

    private String normalizeContent(
            Document document,
            SourceIngestionData.Snapshot source
    ) {

        Element contentRoot =
                selectContentRoot(
                        document,
                        source
                );

        // 실제 document를 직접 수정하지 않고
        // 선택한 본문 영역을 복사해서 정제
        Element cleanRoot =
                contentRoot.clone();

        cleanRoot.select(
                """
                script,
                style,
                noscript,
                svg,
                canvas,
                iframe,
                nav,
                footer,
                header,
                form
                """
        ).remove();

        String text =
                cleanRoot.wholeText()

                        .replace(
                                '\u00A0',
                                ' '
                        )

                        .replace(
                                "\r\n",
                                "\n"
                        )

                        .replace(
                                '\r',
                                '\n'
                        );

        return text.lines()

                .map(
                        String::strip
                )

                .map(line ->
                        line.replaceAll(
                                "[\\t\\x0B\\f ]+",
                                " "
                        )
                )

                .filter(line ->
                        !line.isBlank()
                )

                // 변동성 메타데이터 => ex) 조회수
                .filter(line ->
                        !isVolatileMetadata(
                                line
                        )
                )

                .reduce(
                        (left, right) ->
                                left
                                        + "\n"
                                        + right
                )

                .orElse("")

                .trim();
    }

    private boolean isVolatileMetadata(
            String line
    ) {

        String compact =
                line.replace(
                        " ",
                        ""
                );

        return compact.matches(
                ".*조회수[:：]?[0-9,]+.*"
        );
    }

    private Element selectContentRoot(
            Document document,
            SourceIngestionData.Snapshot source
    ) {

        String configuredSelector =
                source.contentSelector();

        // SourceRegistry에 명시적으로 selector가 설정되어 있다면 generic selector보다 항상 우선해서 사용
        if (
                configuredSelector != null
                        && !configuredSelector.isBlank()
        ) {

            Element configuredContent =
                    document.selectFirst(
                            configuredSelector
                    );

            // 명시적으로 지정한 selector가 사라졌는데 body 전체로 fallback하면
            // 다른 게시물이나 navigation이 근거 본문으로 저장될 수 있으므로 실패 처리
            if (configuredContent == null) {
                throw new SourceIngestionException(
                        "SOURCE_CONTENT_SELECTOR_NOT_FOUND",
                        "Configured content selector was not found"
                );
            }

            return configuredContent;
        }

        // 별도 selector가 없는 source는 기존 generic 후보를 순서대로 확인
        for (
                String selector
                : CONTENT_SELECTORS
        ) {

            Element candidate =
                    document.selectFirst(
                            selector
                    );

            if (
                    candidate != null
                            && candidate
                            .text()
                            .length()
                            >= MIN_NORMALIZED_LENGTH
            ) {
                return candidate;
            }
        }

        // generic selector에서도 적절한 영역을 찾지 못하면 body 전체를 사용
        Element body =
                document.body();

        if (body == null) {
            throw new SourceIngestionException(
                    "SOURCE_HTML_BODY_MISSING",
                    "Official HTML source has no body"
            );
        }

        return body;
    }

}