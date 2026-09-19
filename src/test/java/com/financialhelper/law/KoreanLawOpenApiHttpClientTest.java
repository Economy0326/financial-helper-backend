package com.financialhelper.law;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KoreanLawOpenApiHttpClientTest {

    private HttpServer server;
    private KoreanLawOpenApiHttpClient client;
    private volatile String lastServiceQuery;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/DRF/lawSearch.do", this::search);
        server.createContext("/DRF/lawService.do", this::text);
        server.start();
        client = new KoreanLawOpenApiHttpClient(properties(true, "test-oc"), JsonMapper.builder().build());
    }

    @AfterEach
    void tearDown() {
        if (server != null) server.stop(0);
    }

    @Test
    void parses_search_and_article_with_nested_paragraphs() {
        var versions = client.searchLaw("여신전문금융업법");
        assertThat(versions).hasSize(1);
        assertThat(versions.getFirst().mst()).isEqualTo("277267");
        assertThat(versions.getFirst().derivedUrl()).doesNotContain("OC=");

        var document = client.getLawText(versions.getFirst(), "제16조");
        assertThat(document.articleLocator()).isEqualTo("제16조");
        assertThat(document.articleText()).contains("제16조(신용카드회원등에 대한 책임)");
        assertThat(document.articleText()).contains("① 통지를 받은 때부터");
        assertThat(document.articleText()).contains("1. 분실");
        assertThat(lastServiceQuery).contains("target=eflaw");
        assertThat(lastServiceQuery).contains("MST=277267");
        assertThat(lastServiceQuery).contains("efYd=20251001");
        assertThat(lastServiceQuery).contains("JO=001600");
    }

    @Test
    void null_article_locator_returns_full_text_marker() {
        var version = client.searchLaw("여신전문금융업법").getFirst();

        var document = client.getLawText(version, null);

        assertThat(document.articleLocator()).isEqualTo("FULL_TEXT");
        assertThat(document.articleText()).contains("제16조(신용카드회원등에 대한 책임)");
        assertThat(lastServiceQuery).doesNotContain("JO=");
    }

    @Test
    void malformed_json_fails_closed() {
        server.removeContext("/DRF/lawSearch.do");
        server.createContext("/DRF/lawSearch.do", exchange -> respond(exchange, "{"));

        assertThatThrownBy(() -> client.searchLaw("여신전문금융업법"))
                .isInstanceOf(KoreanLawOpenApiException.class)
                .hasMessageContaining("malformed JSON");
    }

    @Test
    void missing_law_oc_fails_before_request() throws IOException {
        server.stop(0);
        client = new KoreanLawOpenApiHttpClient(properties(true, ""), JsonMapper.builder().build());

        assertThatThrownBy(() -> client.searchLaw("여신전문금융업법"))
                .isInstanceOf(KoreanLawOpenApiException.class)
                .hasMessageContaining("LAW_OC");
    }

    @Test
    void http_error_fails_closed() {
        server.removeContext("/DRF/lawSearch.do");
        server.createContext("/DRF/lawSearch.do", exchange -> {
            exchange.sendResponseHeaders(503, -1);
            exchange.close();
        });

        assertThatThrownBy(() -> client.searchLaw("여신전문금융업법"))
                .isInstanceOf(KoreanLawOpenApiException.class)
                .hasMessageContaining("HTTP error 503");
    }

    @Test
    void requested_article_missing_fails_closed() {
        var versions = client.searchLaw("여신전문금융업법");

        assertThatThrownBy(() -> client.getLawText(versions.getFirst(), "제17조"))
                .isInstanceOf(KoreanLawOpenApiException.class)
                .hasMessageContaining("requested article");
    }

    @Test
    void parses_branch_article_with_numeric_fields_and_normalizes_leading_zeroes() {
        server.removeContext("/DRF/lawService.do");
        server.createContext("/DRF/lawService.do", exchange -> {
            lastServiceQuery = exchange.getRequestURI().getRawQuery();
            respond(exchange, """
                    {"법령":{"기본정보":{"법령명_한글":"여신전문금융업법","법령ID":"000536","공포일자":"20251001","시행일자":"20251001"},"조문":{"조문단위":{"조문번호":6,"조문가지번호":9,"조문내용":"제6조의9(분기 조문)","항":{"항번호":"1","항내용":"분기 본문"}}}}}
                    """);
        });

        var version = client.searchLaw("여신전문금융업법").getFirst();
        var document = client.getLawText(version, "제06조의09");

        assertThat(document.articleLocator()).isEqualTo("제6조의9");
        assertThat(document.articleText()).contains("제6조의9(분기 조문)", "분기 본문");
        assertThat(lastServiceQuery).contains("JO=000609");
    }

    private KoreanLawOpenApiProperties properties(boolean enabled, String oc) {
        return new KoreanLawOpenApiProperties(
                enabled,
                "http://127.0.0.1:" + server.getAddress().getPort() + "/DRF",
                Duration.ofSeconds(1),
                Duration.ofSeconds(2),
                oc,
                "law-open-api",
                "unversioned");
    }

    private void search(HttpExchange exchange) throws IOException {
        respond(exchange, """
                {"LawSearch":{"law":[{"현행연혁코드":"현행","법령일련번호":"277267","법령명한글":"여신전문금융업법","법령ID":"000536","공포일자":"20251001","시행일자":"20251001","법령상세링크":"/DRF/lawService.do?OC=do-not-store&target=law&MST=277267&type=HTML"}],"totalCnt":"1"}}
                """);
    }

    private void text(HttpExchange exchange) throws IOException {
        lastServiceQuery = exchange.getRequestURI().getRawQuery();
        respond(exchange, """
                {"법령":{"법령키":"0005362025100121065","기본정보":{"법령명_한글":"여신전문금융업법","법령ID":"000536","공포일자":"20251001","시행일자":"20251001"},"조문":{"조문단위":{"조문번호":"16","조문가지번호":"0","조문내용":"제16조(신용카드회원등에 대한 책임)","항":[{"항번호":"①","항내용":"① 통지를 받은 때부터","호":[{"호번호":"1","호내용":"1. 분실"}]}]}}}}
                """);
    }

    private void respond(HttpExchange exchange, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        exchange.sendResponseHeaders(200, bytes.length);
        try (var output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }
}
