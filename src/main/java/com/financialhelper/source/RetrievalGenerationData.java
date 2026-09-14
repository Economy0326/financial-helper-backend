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
            String metadataJson
    ) {

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
