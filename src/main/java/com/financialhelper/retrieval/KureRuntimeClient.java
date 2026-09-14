package com.financialhelper.retrieval;

import java.util.List;
import java.util.Set;
import java.util.UUID;

public interface KureRuntimeClient {

    KureRuntimeMetadata metadata();

    KureRuntimeBuildResult build(KureRuntimeBuildRequest request);

    KureRuntimeReadiness readiness(UUID generationId);

    KureRuntimeQueryResult query(KureRuntimeQueryRequest request);

    record KureRuntimeMetadata(
            String runtimeVersion,
            boolean dependenciesAvailable,
            String modelIdentifier,
            String modelRevision,
            String tokenizerIdentifier,
            String tokenizerRevision,
            int tokenVectorDimension,
            int maxDocumentTokens,
            int queryLength,
            boolean queryExpansion,
            boolean instructionPrefixes,
            String indexBackend,
            String pylateVersion,
            String fastPlaidVersion
    ) {
    }

    record KureRuntimeBuildRequest(
            UUID generationId,
            String generationKey,
            String modelIdentifier,
            String modelRevision,
            String tokenizerIdentifier,
            String tokenizerRevision,
            String chunkConfigVersion,
            String corpusSnapshotSha256,
            String encodingConfigJson,
            String indexConfigJson,
            List<Document> documents
    ) {

        public KureRuntimeBuildRequest {
            documents = documents == null ? List.of() : List.copyOf(documents);
        }

        public record Document(
                UUID id,
                String text
        ) {
        }
    }

    record KureRuntimeBuildResult(
            UUID generationId,
            List<UUID> readyDocumentIds,
            String indexMetadataJson
    ) {

        public KureRuntimeBuildResult {
            readyDocumentIds = readyDocumentIds == null
                    ? List.of()
                    : List.copyOf(readyDocumentIds);
        }
    }

    record KureRuntimeReadiness(
            UUID generationId,
            boolean ready,
            int documentCount,
            String indexMetadataJson
    ) {
    }

    record KureRuntimeQueryRequest(
            UUID generationId,
            String query,
            int topK,
            Set<UUID> allowedDocumentIds
    ) {
        public KureRuntimeQueryRequest {
            if (generationId == null) {
                throw new IllegalArgumentException("generationId must not be null");
            }
            if (query == null || query.isBlank()) {
                throw new IllegalArgumentException("query must not be blank");
            }
            if (topK < 1 || topK > 100) {
                throw new IllegalArgumentException("topK must be between 1 and 100");
            }
            allowedDocumentIds = allowedDocumentIds == null
                    ? Set.of()
                    : Set.copyOf(allowedDocumentIds);
        }
    }

    record KureRuntimeQueryResult(
            UUID generationId,
            List<Hit> results
    ) {
        public KureRuntimeQueryResult {
            results = results == null ? List.of() : List.copyOf(results);
        }

        public record Hit(
                UUID sourceChunkId,
                int rank,
                double maxSimScore,
                UUID generationId
        ) {
        }
    }
}
