package com.financialhelper.retrieval;

import com.financialhelper.source.RetrievalGeneration;

import org.springframework.stereotype.Component;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.Objects;

/** 저장된 generation과 runtime이 다르면 fail-closed로 처리한다. */
@Component
public class KureRuntimeCompatibility {

    private final JsonMapper jsonMapper;

    public KureRuntimeCompatibility(JsonMapper jsonMapper) {
        this.jsonMapper = jsonMapper;
    }

    public void requireCompatible(
            RetrievalGeneration generation,
            KureRuntimeClient.KureRuntimeMetadata runtime
    ) {
        if (generation == null || runtime == null) {
            throw new IllegalArgumentException("generation and runtime are required");
        }
        if (!runtime.dependenciesAvailable()) {
            throw new KureRuntimeException("KURE runtime dependencies are unavailable");
        }
        requireEqual("model identifier", generation.getModelIdentifier(), runtime.modelIdentifier());
        requireEqual("model revision", generation.getModelRevision(), runtime.modelRevision());
        requireEqual("tokenizer identifier", generation.getTokenizerIdentifier(), runtime.tokenizerIdentifier());
        requireEqual("tokenizer revision", generation.getTokenizerRevision(), runtime.tokenizerRevision());
        requireEqual("index backend", "PLAID", runtime.indexBackend());

        if (runtime.tokenVectorDimension() != 128
                || runtime.maxDocumentTokens() != 8192
                || runtime.queryLength() != 64
                || !runtime.queryExpansion()
                || runtime.instructionPrefixes()) {
            throw new KureRuntimeException(
                    "KURE runtime encoding contract does not match the pinned KURE-v2 contract"
            );
        }

        if (generation.getChunkConfigVersion() == null) {
            throw new KureRuntimeException(
                    "generation chunk configuration is required"
            );
        }

        try {
            JsonNode encoding = jsonMapper.readTree(generation.getEncodingConfigJson());
            JsonNode index = jsonMapper.readTree(generation.getIndexConfigJson());
            if (encoding.path("queryLength").asInt(-1) != 64
                    || encoding.path("documentMaxTokens").asInt(-1) != 8192
                    || !encoding.path("doQueryExpansion").asBoolean(false)
                    || !encoding.path("queryPrefix").asText("x").isEmpty()
                    || !encoding.path("documentPrefix").asText("x").isEmpty()
                    || !"PLAID".equals(index.path("backend").asText())
                    || index.path("nbits").asInt(-1) != 4
                    || index.path("seed").asInt(-1) != 42
                    || !index.path("useFast").asBoolean(false)
                    || index.path("useTriton").asBoolean(true)) {
                throw new KureRuntimeException(
                        "generation encoding/index config does not match KURE runtime"
                );
            }
        } catch (JacksonException exception) {
            throw new KureRuntimeException(
                    "generation encoding/index config is invalid",
                    exception
            );
        }
    }

    private static void requireEqual(String field, String expected, String actual) {
        if (!Objects.equals(expected, actual)) {
            throw new KureRuntimeException(
                    "KURE runtime " + field + " mismatch"
            );
        }
    }
}
