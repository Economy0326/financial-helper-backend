package com.financialhelper.source;

/**
 * Immutable identity/configuration input for a retrieval generation.  The
 * fields remain model-agnostic so a later runtime can use KURE or another
 * representation without changing the source domain.
 */
public final class RetrievalGenerationData {

    private RetrievalGenerationData() {
    }

    public record Definition(
            String generationKey,
            String representationConfigVersion,
            String modelIdentifier,
            String modelRevision,
            String tokenizerIdentifier,
            String tokenizerRevision,
            String encodingConfigJson,
            String indexConfigJson,
            String metadataJson,
            String chunkConfigVersion,
            String corpusSnapshotSha256
    ) {

        public Definition(
                String generationKey,
                String representationConfigVersion,
                String modelIdentifier,
                String modelRevision,
                String tokenizerIdentifier,
                String tokenizerRevision,
                String encodingConfigJson,
                String indexConfigJson,
                String metadataJson
        ) {
            this(
                    generationKey,
                    representationConfigVersion,
                    modelIdentifier,
                    modelRevision,
                    tokenizerIdentifier,
                    tokenizerRevision,
                    encodingConfigJson,
                    indexConfigJson,
                    metadataJson,
                    null,
                    null
            );
        }

        public Definition {
            generationKey =
                    requireText(generationKey, "generationKey");
            representationConfigVersion =
                    requireText(
                            representationConfigVersion,
                            "representationConfigVersion"
                    );
            modelIdentifier =
                    requireText(modelIdentifier, "modelIdentifier");
            modelRevision =
                    requireText(modelRevision, "modelRevision");
            tokenizerIdentifier =
                    requireText(tokenizerIdentifier, "tokenizerIdentifier");
            tokenizerRevision =
                    requireText(tokenizerRevision, "tokenizerRevision");
            encodingConfigJson =
                    JsonObjectSupport.requireObject(
                            encodingConfigJson,
                            "encodingConfigJson"
                    );
            indexConfigJson =
                    JsonObjectSupport.requireObject(
                            indexConfigJson,
                            "indexConfigJson"
                    );
            metadataJson =
                    JsonObjectSupport.requireObject(
                            metadataJson,
                            "metadataJson"
                    );
            chunkConfigVersion =
                    normalizeOptionalText(
                            chunkConfigVersion,
                            "chunkConfigVersion"
                    );
            corpusSnapshotSha256 =
                    normalizeOptionalHash(
                            corpusSnapshotSha256,
                            "corpusSnapshotSha256"
                    );
        }

        private static String normalizeOptionalText(
                String value,
                String fieldName
        ) {
            if (value == null || value.isBlank()) {
                return null;
            }

            String normalized = value.trim();
            if (normalized.length() > 100) {
                throw new IllegalArgumentException(
                        fieldName + " is too long"
                );
            }

            return normalized;
        }

        private static String normalizeOptionalHash(
                String value,
                String fieldName
        ) {
            if (value == null || value.isBlank()) {
                return null;
            }

            String normalized = value.trim().toLowerCase();
            if (!normalized.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException(
                        fieldName + " must be a SHA-256 hex value"
                );
            }

            return normalized;
        }

        private static String requireText(
                String value,
                String fieldName
        ) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException(
                        fieldName + " must not be blank"
                );
            }

            return value.trim();
        }
    }
}
