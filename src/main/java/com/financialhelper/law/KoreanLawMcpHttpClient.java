package com.financialhelper.law;

import org.springframework.stereotype.Component;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Direct JSON-RPC client for v4.9.1 Streamable HTTP.
 *
 * The pinned server is stateless and exposes text-only tool results. This
 * boundary deliberately does not depend on an MCP Java SDK or expose the
 * server's action-plan-like tools to the financial decision layer.
 */
@Component
public class KoreanLawMcpHttpClient implements KoreanLawMcpClient {

    private static final String PROTOCOL_VERSION = "2024-11-05";

    private final KoreanLawMcpProperties properties;
    private final JsonMapper jsonMapper;
    private final HttpClient httpClient;

    public KoreanLawMcpHttpClient(
            KoreanLawMcpProperties properties,
            JsonMapper jsonMapper
    ) {
        this.properties = properties;
        this.jsonMapper = jsonMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.connectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        requireHttpEndpoint(properties.endpoint());
    }

    @Override
    public ServerMetadata metadata() {
        String requestId = UUID.randomUUID().toString();
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("protocolVersion", PROTOCOL_VERSION);
        params.put("capabilities", Map.of());
        params.put("clientInfo", Map.of("name", "financial-helper-backend", "version", "1"));

        JsonNode response = request(jsonRpc(requestId, "initialize", params));
        JsonNode result = requireResult(response, "initialize");
        JsonNode serverInfo = result.path("serverInfo");
        String name = text(serverInfo, "name");
        String version = text(serverInfo, "version");
        String protocolVersion = text(result, "protocolVersion");
        return new ServerMetadata(name, version, protocolVersion);
    }

    @Override
    public ToolResult call(String toolName, Map<String, Object> arguments) {
        if (toolName == null || toolName.isBlank()) {
            throw new IllegalArgumentException("toolName is required");
        }
        if (arguments == null) {
            throw new IllegalArgumentException("arguments are required");
        }

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("name", toolName);
        params.put("arguments", arguments);
        JsonNode response = request(jsonRpc(
                UUID.randomUUID().toString(), "tools/call", params));
        JsonNode result = requireResult(response, toolName);
        if (result.path("isError").asBoolean(false)) {
            throw new KoreanLawMcpException(
                    "korean-law-mcp tool returned an error: " + toolName);
        }

        JsonNode content = result.path("content");
        if (!content.isArray() || content.isEmpty()) {
            throw new KoreanLawMcpException(
                    "korean-law-mcp returned empty content: " + toolName);
        }
        StringBuilder text = new StringBuilder();
        for (JsonNode item : content) {
            if (!"text".equals(item.path("type").asText())) {
                throw new KoreanLawMcpException(
                        "korean-law-mcp returned non-text content: " + toolName);
            }
            String value = item.path("text").asText("");
            if (!value.isBlank()) {
                if (text.length() > 0) text.append('\n');
                text.append(value);
            }
        }
        return new ToolResult(toolName, text.toString());
    }

    private JsonNode request(Map<String, Object> payload) {
        if (!properties.enabled()) {
            throw new KoreanLawMcpException("korean-law-mcp client is disabled");
        }

        final String body;
        try {
            body = jsonMapper.writeValueAsString(payload);
        } catch (JacksonException exception) {
            throw new KoreanLawMcpException(
                    "could not serialize korean-law-mcp request", exception);
        }

        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(properties.endpoint()))
                .timeout(properties.requestTimeout())
                .header("Accept", "application/json, text/event-stream")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        // v4.9.1 has no separate MCP_AUTH_TOKEN check. Its HTTP server accepts
        // law_oc/apikey/Bearer as the Law API OC and falls back to server LAW_OC.
        if (properties.lawOc() != null && !properties.lawOc().isBlank()) {
            builder.header("law_oc", properties.lawOc());
        }

        try {
            HttpResponse<String> response = httpClient.send(
                    builder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new KoreanLawMcpException(
                        "korean-law-mcp HTTP error " + response.statusCode());
            }
            return parseResponse(response.body());
        } catch (IOException exception) {
            throw new KoreanLawMcpException(
                    "korean-law-mcp request failed", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new KoreanLawMcpException(
                    "korean-law-mcp request interrupted", exception);
        }
    }

    private JsonNode parseResponse(String body) {
        if (body == null || body.isBlank()) {
            throw new KoreanLawMcpException("korean-law-mcp returned an empty response");
        }
        String json = body.trim();
        if (!json.startsWith("{") && !json.startsWith("[")) {
            StringBuilder data = new StringBuilder();
            for (String line : body.split("\\R")) {
                if (line.startsWith("data:")) {
                    String value = line.substring("data:".length()).trim();
                    if (!value.isEmpty() && !"[DONE]".equals(value)) data.append(value);
                }
            }
            json = data.toString();
        }
        if (json.isBlank()) {
            throw new KoreanLawMcpException("korean-law-mcp returned no JSON response");
        }
        try {
            return jsonMapper.readTree(json);
        } catch (JacksonException exception) {
            throw new KoreanLawMcpException(
                    "korean-law-mcp returned malformed JSON", exception);
        }
    }

    private JsonNode requireResult(JsonNode response, String operation) {
        JsonNode error = response.path("error");
        if (!error.isMissingNode() && !error.isNull()) {
            throw new KoreanLawMcpException(
                    "korean-law-mcp JSON-RPC error during " + operation);
        }
        JsonNode result = response.path("result");
        if (result.isMissingNode() || result.isNull()) {
            throw new KoreanLawMcpException(
                    "korean-law-mcp response has no result: " + operation);
        }
        return result;
    }

    private static Map<String, Object> jsonRpc(
            String id,
            String method,
            Map<String, Object> params
    ) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("jsonrpc", "2.0");
        payload.put("id", id);
        payload.put("method", method);
        payload.put("params", params);
        return payload;
    }

    private static String text(JsonNode parent, String field) {
        String value = parent.path(field).asText("");
        if (value.isBlank()) {
            throw new KoreanLawMcpException("korean-law-mcp metadata is missing " + field);
        }
        return value;
    }

    private static void requireHttpEndpoint(String endpoint) {
        URI uri = URI.create(endpoint);
        if (!"http".equalsIgnoreCase(uri.getScheme())
                && !"https".equalsIgnoreCase(uri.getScheme())) {
            throw new IllegalArgumentException("korean-law-mcp endpoint must use HTTP(S)");
        }
    }
}
