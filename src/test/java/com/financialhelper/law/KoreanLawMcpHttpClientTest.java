package com.financialhelper.law;

import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KoreanLawMcpHttpClientTest {

    private HttpServer server;
    private AtomicReference<String> requestBody;
    private AtomicReference<String> lawOc;

    @BeforeEach
    void setUp() throws IOException {
        requestBody = new AtomicReference<>();
        lawOc = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/mcp", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            lawOc.set(exchange.getRequestHeaders().getFirst("law_oc"));
            String body = requestBody.get().contains("initialize")
                    ? "{\"jsonrpc\":\"2.0\",\"id\":\"x\",\"result\":{\"protocolVersion\":\"2024-11-05\",\"serverInfo\":{\"name\":\"korean-law\",\"version\":\"4.9.1\"}}}"
                    : "{\"jsonrpc\":\"2.0\",\"id\":\"x\",\"result\":{\"content\":[{\"type\":\"text\",\"text\":\"법령명: 여신전문금융업법\"}]}}";
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void uses_json_rpc_and_server_side_law_oc_header() {
        KoreanLawMcpHttpClient client = new KoreanLawMcpHttpClient(
                properties(), JsonMapper.builder().build());

        assertThat(client.metadata()).isEqualTo(
                new KoreanLawMcpClient.ServerMetadata("korean-law", "4.9.1", "2024-11-05"));
        assertThat(client.call("get_law_text", Map.of("mst", "123")).text())
                .contains("여신전문금융업법");
        assertThat(lawOc.get()).isEqualTo("test-oc");
        assertThat(requestBody.get()).contains("tools/call", "get_law_text");
    }

    @Test
    void rejects_malformed_or_empty_json_rpc_response() throws IOException {
        server.removeContext("/mcp");
        server.createContext("/mcp", exchange -> {
            byte[] bytes = "not-json".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        KoreanLawMcpHttpClient client = new KoreanLawMcpHttpClient(
                properties(), JsonMapper.builder().build());

        assertThatThrownBy(() -> client.metadata())
                .isInstanceOf(KoreanLawMcpException.class)
                .hasMessageContaining("no JSON response");
    }

    @Test
    void converts_http_failure_and_timeout_to_fail_closed_errors() throws IOException {
        server.removeContext("/mcp");
        server.createContext("/mcp", exchange -> {
            try {
                Thread.sleep(500);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
            exchange.close();
        });
        KoreanLawMcpProperties shortTimeout = new KoreanLawMcpProperties(
                true,
                "http://127.0.0.1:" + server.getAddress().getPort() + "/mcp",
                Duration.ofSeconds(1),
                Duration.ofMillis(50),
                "test-oc",
                "4.9.1",
                "https://github.com/chrisryugj/korean-law-mcp",
                "v4.9.1",
                "860cfcbce9c01c664766ec1badca8d4468b87488");

        KoreanLawMcpHttpClient client = new KoreanLawMcpHttpClient(
                shortTimeout, JsonMapper.builder().build());
        assertThatThrownBy(client::metadata)
                .isInstanceOf(KoreanLawMcpException.class)
                .hasMessageContaining("request failed");
    }

    private KoreanLawMcpProperties properties() {
        return new KoreanLawMcpProperties(
                true,
                "http://127.0.0.1:" + server.getAddress().getPort() + "/mcp",
                Duration.ofSeconds(1),
                Duration.ofSeconds(2),
                "test-oc",
                "4.9.1",
                "https://github.com/chrisryugj/korean-law-mcp",
                "v4.9.1",
                "860cfcbce9c01c664766ec1badca8d4468b87488");
    }
}
