package com.financialhelper.source;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence boundary for already prepared SourceChunk values.
 * Chunking/tokenization is deliberately outside this service.
 */
@Service
public class SourceChunkPersistenceService {

    private final SourceDocumentRepository sourceDocumentRepository;

    private final SourceChunkConfigurationRepository
            sourceChunkConfigurationRepository;

    private final SourceChunkRepository sourceChunkRepository;

    public SourceChunkPersistenceService(
            SourceDocumentRepository sourceDocumentRepository,
            SourceChunkConfigurationRepository sourceChunkConfigurationRepository,
            SourceChunkRepository sourceChunkRepository
    ) {
        this.sourceDocumentRepository =
                sourceDocumentRepository;

        this.sourceChunkConfigurationRepository =
                sourceChunkConfigurationRepository;

        this.sourceChunkRepository =
                sourceChunkRepository;
    }

    /**
     * Return an exact existing chunk or create it once.  A changed body or
     * changed immutable metadata for the same document/config/sequence is a
     * conflict and is never overwritten.
     */
    @Transactional
    public SourceChunk saveIfAbsent(
            SourceDocument sourceDocument,
            SourceChunkData.Definition definition
    ) {
        if (sourceDocument == null || sourceDocument.getId() == null) {
            throw new IllegalArgumentException(
                    "sourceDocument must be a persisted document"
            );
        }

        validateDefinitionForComparison(definition);

        SourceChunkConfiguration configuration =
                getOrCreateConfiguration(
                        definition.chunkConfigVersion(),
                        definition.chunkConfigJson()
                );

        // Lock only the already persisted document row.  This gives all
        // sequence/configuration writes for one document version a common DB
        // serialization point without extending any HTTP transaction.
        SourceDocument lockedDocument =
                sourceDocumentRepository
                        .findForUpdateById(sourceDocument.getId())
                        .orElseThrow(() ->
                                new IllegalArgumentException(
                                        "sourceDocument does not exist"
                                )
                        );

        Optional<SourceChunk> existing =
                sourceChunkRepository
                        .findBySourceDocument_IdAndChunkConfiguration_ConfigVersionAndSequence(
                                lockedDocument.getId(),
                                configuration.getConfigVersion(),
                                definition.sequence()
                        );

        if (existing.isPresent()) {
            SourceChunk chunk =
                    existing.get();

            if (!chunk.hasSameDefinition(definition)) {
                throw new SourceChunkDefinitionConflictException();
            }

            return chunk;
        }

        SourceChunk chunk =
                new SourceChunk(
                        lockedDocument,
                        configuration,
                        definition,
                        OffsetDateTime.now(ZoneOffset.UTC)
                );

        return sourceChunkRepository.save(chunk);
    }

    private SourceChunkConfiguration getOrCreateConfiguration(
            String configVersion,
            String configJson
    ) {
        String normalizedVersion =
                requireConfigVersion(configVersion);

        String normalizedJson =
                JsonObjectSupport.requireObject(
                        configJson,
                        "chunkConfigJson"
                );

        Optional<SourceChunkConfiguration> existing =
                sourceChunkConfigurationRepository
                        .findById(normalizedVersion);

        if (existing.isEmpty()) {
            sourceChunkConfigurationRepository
                    .insertIfAbsent(
                            normalizedVersion,
                            normalizedJson,
                            SourceHashing.sha256(normalizedJson)
                    );

            existing =
                    sourceChunkConfigurationRepository
                            .findById(normalizedVersion);
        }

        SourceChunkConfiguration configuration =
                existing.orElseThrow(() ->
                        new IllegalStateException(
                                "Chunk configuration could not be created"
                        )
                );

        if (!configuration.hasSameDefinition(normalizedJson)) {
            throw new SourceChunkDefinitionConflictException();
        }

        return configuration;
    }

    private static void validateDefinitionForComparison(
            SourceChunkData.Definition definition
    ) {
        if (definition == null) {
            throw new IllegalArgumentException(
                    "definition must not be null"
            );
        }

        if (definition.body() == null || definition.body().isBlank()) {
            throw new IllegalArgumentException(
                    "body must not be blank"
            );
        }

        if (definition.sequence() < 0) {
            throw new IllegalArgumentException(
                    "sequence must be non-negative"
            );
        }
    }

    private static String requireConfigVersion(
            String configVersion
    ) {
        if (configVersion == null || configVersion.isBlank()) {
            throw new IllegalArgumentException(
                    "chunkConfigVersion must not be blank"
            );
        }

        String normalized =
                configVersion.trim();

        if (normalized.length() > 100) {
            throw new IllegalArgumentException(
                    "chunkConfigVersion is too long"
            );
        }

        return normalized;
    }
}
