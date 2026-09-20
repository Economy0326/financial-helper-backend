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
     * 정확히 일치하는 기존 chunk를 반환하거나 한 번만 생성한다. 같은
     * document/config/sequence의 body나 변경 불가능한 metadata가 달라지면
     * 충돌로 처리하며 덮어쓰지 않는다.
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

        // 이미 저장된 document row만 lock한다. 하나의 document version에 대한 모든
        // sequence/configuration write가 공통 DB lock을 사용하게 한다.
        // HTTP transaction을 늘리지 않으면서 직렬화 지점으로 사용한다.
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
