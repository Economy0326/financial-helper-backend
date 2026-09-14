package com.financialhelper.retrieval;

import java.util.List;
import java.util.UUID;

public interface KureRuntimeClient {

    KureRuntimeMetadata metadata();

    KureRuntimeBuildResult build(KureRuntimeBuildRequest request);

    KureRuntimeReadiness readiness(UUID generationId);

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
}
