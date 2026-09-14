package com.financialhelper.retrieval;

import com.financialhelper.source.SourceChunkTokenizer;

import org.springframework.stereotype.Component;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** HTTP boundary to the in-repository Python KURE runtime. */
@Component
public class KureRuntimeHttpClient
        implements KureRuntimeClient, SourceChunkTokenizer {

    private final KureRuntimeProperties properties;
    private final JsonMapper jsonMapper;
    private final HttpClient httpClient;

    public KureRuntimeHttpClient(
            KureRuntimeProperties properties,
            JsonMapper jsonMapper
    ) {
        this.properties = properties;
        this.jsonMapper = jsonMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.connectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    @Override
    public KureRuntimeMetadata metadata() {
        return read("GET", "/v1/runtime", null, KureRuntimeMetadata.class);
    }

    @Override
    public KureRuntimeBuildResult build(
            KureRuntimeBuildRequest request
    ) {
        try {
            String response = request("POST", "/v1/generations/"
                    + request.generationId() + "/build", request);
            BuildResponse decoded = jsonMapper.readValue(response, BuildResponse.class);
            return new KureRuntimeBuildResult(
                    UUID.fromString(decoded.generationId()),
                    decoded.readyDocumentIds().stream()
                            .map(UUID::fromString)
                            .toList(),
                    jsonMapper.writeValueAsString(decoded.indexMetadata())
            );
        } catch (JacksonException | IllegalArgumentException exception) {
            throw new KureRuntimeException(
                    "Invalid KURE runtime build response",
                    exception
            );
        }
    }

    @Override
    public KureRuntimeReadiness readiness(UUID generationId) {
        if (generationId == null) {
            throw new IllegalArgumentException("generationId must not be null");
        }
        return read(
                "GET",
                "/v1/generations/" + generationId + "/readiness",
                null,
                KureRuntimeReadiness.class
        );
    }

    @Override
    public KureRuntimeQueryResult query(KureRuntimeQueryRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("request must not be null");
        }
        try {
            Map<String, Object> payload = Map.of(
                    "generationId", request.generationId().toString(),
                    "query", request.query().trim(),
                    "topK", request.topK(),
                    "subset", request.allowedDocumentIds().stream()
                            .map(UUID::toString)
                            .sorted()
                            .toList()
            );
            String response = request("POST", "/v1/generations/"
                    + request.generationId() + "/query", payload);
            QueryResponse decoded = jsonMapper.readValue(response, QueryResponse.class);
            UUID generationId = UUID.fromString(decoded.generationId());
            List<KureRuntimeQueryResult.Hit> hits = decoded.results().stream()
                    .map(hit -> new KureRuntimeQueryResult.Hit(
                            UUID.fromString(hit.sourceChunkId()),
                            hit.rank(),
                            hit.maxSimScore(),
                            UUID.fromString(hit.generationId())
                    ))
                    .toList();
            return new KureRuntimeQueryResult(generationId, hits);
        } catch (JacksonException | IllegalArgumentException exception) {
            throw new KureRuntimeException(
                    "Invalid KURE runtime query response",
                    exception
            );
        }
    }

    @Override
    public int countDocumentTokens(String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("text must not be blank");
        }
        TokenizeResponse response = read(
                "POST",
                "/v1/tokenize",
                Map.of("text", text),
                TokenizeResponse.class
        );
        return response.documentTokenCount();
    }

    @Override
    public String identifier() {
        return properties.tokenizerIdentifier();
    }

    @Override
    public String revision() {
        return properties.tokenizerRevision();
    }

    private <T> T read(
            String method,
            String path,
            Object body,
            Class<T> responseType
    ) {
        try {
            String response = request(method, path, body);
            return jsonMapper.readValue(response, responseType);
        } catch (JacksonException exception) {
            throw new KureRuntimeException(
                    "Invalid KURE runtime response",
                    exception
            );
        }
    }

    private String request(
            String method,
            String path,
            Object body
    ) {
        if (!properties.enabled()) {
            throw new KureRuntimeException("KURE runtime is disabled");
        }

        try {
            String payload = body == null
                    ? ""
                    : jsonMapper.writeValueAsString(body);
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(properties.endpoint().replaceAll("/$", "") + path))
                    .timeout(properties.requestTimeout())
                    .header("Accept", "application/json");

            if (body != null) {
                builder.header("Content-Type", "application/json")
                        .method(method, HttpRequest.BodyPublishers.ofString(payload));
            } else {
                builder.method(method, HttpRequest.BodyPublishers.noBody());
            }

            HttpResponse<String> response = httpClient.send(
                    builder.build(),
                    HttpResponse.BodyHandlers.ofString()
            );

            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new KureRuntimeException(
                        "KURE runtime returned HTTP " + response.statusCode()
                );
            }
            return response.body();
        } catch (IOException exception) {
            throw new KureRuntimeException(
                    "KURE runtime request failed",
                    exception
            );
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new KureRuntimeException(
                    "KURE runtime request interrupted",
                    exception
            );
        } catch (JacksonException exception) {
            throw new KureRuntimeException(
                    "Could not serialize KURE runtime request",
                    exception
            );
        }
    }

    private record TokenizeResponse(int documentTokenCount) {
    }

    private record BuildResponse(
            String generationId,
            List<String> readyDocumentIds,
            Map<String, Object> indexMetadata
    ) {
    }

    private record QueryResponse(
            String generationId,
            List<QueryHit> results
    ) {
        private QueryResponse {
            results = results == null ? List.of() : results;
        }
    }

    private record QueryHit(
            String sourceChunkId,
            int rank,
            double maxSimScore,
            String generationId
    ) {
    }
}
