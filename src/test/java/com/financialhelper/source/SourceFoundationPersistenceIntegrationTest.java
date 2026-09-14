package com.financialhelper.source;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
class SourceFoundationPersistenceIntegrationTest {

    private static final OffsetDateTime BASE_TIME =
            OffsetDateTime.of(
                    2026,
                    9,
                    14,
                    0,
                    0,
                    0,
                    0,
                    ZoneOffset.UTC
            );

    @Autowired
    private SourceRegistryRepository sourceRegistryRepository;

    @Autowired
    private SourceDocumentRepository sourceDocumentRepository;

    @Autowired
    private SourceDocumentWriter sourceDocumentWriter;

    @Autowired
    private SourceChunkPersistenceService sourceChunkPersistenceService;

    @Autowired
    private SourceChunkRepository sourceChunkRepository;

    @Autowired
    private RetrievalGenerationPersistenceService
            retrievalGenerationPersistenceService;

    @Autowired
    private SourceChunkIndexingPersistenceService
            sourceChunkIndexingPersistenceService;

    @Autowired
    private SourceChunkIndexingRepository sourceChunkIndexingRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    @AfterEach
    void cleanFoundationRows() {
        jdbcTemplate.update("DELETE FROM source_chunk_indexing");
        jdbcTemplate.update("DELETE FROM source_chunk");
        jdbcTemplate.update("DELETE FROM retrieval_generation");
        jdbcTemplate.update("DELETE FROM source_chunk_configuration");
        sourceDocumentRepository.deleteAll();
    }

    @Test
    void chunkCreationIsIdempotentAndConfigurationIsPartOfIdentity() {
        SourceDocument document = createDocument("첫 번째 청크");

        SourceChunkData.Definition definition = chunkDefinition(
                "chunk-v1",
                "{\"window\":128}",
                0,
                "첫 번째 청크",
                0
        );

        SourceChunk first = sourceChunkPersistenceService.saveIfAbsent(
                document,
                definition
        );
        SourceChunk retry = sourceChunkPersistenceService.saveIfAbsent(
                document,
                new SourceChunkData.Definition(
                        "chunk-v1",
                        "{ \"window\" : 128 }",
                        0,
                        "첫 번째 청크",
                        null,
                        null,
                        null,
                        null,
                        0,
                        "첫 번째 청크".length(),
                        "{}"
                )
        );

        assertThat(retry.getId()).isEqualTo(first.getId());
        assertThat(sourceChunkRepository.count()).isOne();

        assertThatThrownBy(() ->
                sourceChunkPersistenceService.saveIfAbsent(
                        document,
                        chunkDefinition(
                                "chunk-v1",
                                "{\"window\":128}",
                                0,
                                "첫",
                                0
                        )
                )
        ).isInstanceOf(SourceChunkDefinitionConflictException.class);

        SourceChunk differentConfiguration =
                sourceChunkPersistenceService.saveIfAbsent(
                        document,
                        chunkDefinition(
                                "chunk-v2",
                                "{\"window\":256}",
                                0,
                                "첫 번째 청크",
                                0
                        )
                );

        assertThat(differentConfiguration.getId())
                .isNotEqualTo(first.getId());
        assertThat(sourceChunkRepository.count()).isEqualTo(2);
    }

    @Test
    void aNewDocumentVersionStartsWithPendingReview() {
        SourceDocument firstDocument = createDocument("버전 하나");
        SourceChunk firstChunk = sourceChunkPersistenceService.saveIfAbsent(
                firstDocument,
                chunkDefinition("chunk-v1", "{}", 0, "버전 하나", 0)
        );
        firstChunk.approve(BASE_TIME);
        sourceChunkRepository.save(firstChunk);

        SourceDocument secondDocument = createDocument("버전 둘");
        SourceChunk secondChunk = sourceChunkPersistenceService.saveIfAbsent(
                secondDocument,
                chunkDefinition("chunk-v1", "{}", 0, "버전 둘", 0)
        );

        assertThat(firstDocument.getDocumentVersion()).isOne();
        assertThat(secondDocument.getDocumentVersion()).isEqualTo(2);
        assertThat(firstChunk.getReviewStatus())
                .isEqualTo(SourceChunkReviewStatus.APPROVED);
        assertThat(secondChunk.getReviewStatus())
                .isEqualTo(SourceChunkReviewStatus.PENDING);
    }

    @Test
    void generationAndMappingAreIdempotentAndReadyRequiresCurrentApproval() {
        SourceDocument document = createDocument("검토된 원문");
        SourceChunk chunk = sourceChunkPersistenceService.saveIfAbsent(
                document,
                chunkDefinition("chunk-v1", "{}", 0, "검토된 원문", 0)
        );
        chunk.approve(BASE_TIME);
        sourceChunkRepository.save(chunk);

        RetrievalGenerationData.Definition generationDefinition =
                generationDefinition("generation-v1");
        RetrievalGeneration generation =
                retrievalGenerationPersistenceService.getOrCreate(
                        generationDefinition
                );
        RetrievalGeneration retryGeneration =
                retrievalGenerationPersistenceService.getOrCreate(
                        generationDefinition
                );
        assertThat(retryGeneration.getId()).isEqualTo(generation.getId());

        SourceChunkIndexing mapping =
                sourceChunkIndexingPersistenceService.getOrCreate(
                        chunk,
                        generation
                );
        SourceChunkIndexing retryMapping =
                sourceChunkIndexingPersistenceService.getOrCreate(
                        chunk,
                        generation
                );
        assertThat(retryMapping.getId()).isEqualTo(mapping.getId());
        assertThat(mapping.getExternalDocumentId()).isEqualTo(chunk.getId());
        assertThat(sourceChunkIndexingRepository.count()).isOne();

        generation = retrievalGenerationPersistenceService.markProcessing(
                generation
        );
        mapping = sourceChunkIndexingPersistenceService.markProcessing(mapping);
        mapping = sourceChunkIndexingPersistenceService.markReady(
                mapping,
                "{\"artifact\":\"test-index\"}"
        );
        RetrievalGeneration ready =
                retrievalGenerationPersistenceService.markReady(generation);

        assertThat(mapping.getIndexingStatus())
                .isEqualTo(SourceChunkIndexingStatus.READY);
        assertThat(ready.getStatus()).isEqualTo(RetrievalGenerationStatus.READY);
        assertThat(ready.getReadyChunkCount()).isOne();
    }

    @Test
    void readyRevalidatesApprovalAndProcessingFreezesMembership() {
        SourceDocument document = createDocument("가나다");
        SourceChunk firstChunk = sourceChunkPersistenceService.saveIfAbsent(
                document,
                chunkDefinition("chunk-v1", "{}", 0, "가", 0)
        );
        SourceChunk secondChunk = sourceChunkPersistenceService.saveIfAbsent(
                document,
                chunkDefinition("chunk-v1", "{}", 1, "나", 1)
        );
        firstChunk.approve(BASE_TIME);
        secondChunk.approve(BASE_TIME);
        sourceChunkRepository.save(firstChunk);
        sourceChunkRepository.save(secondChunk);

        RetrievalGeneration generation =
                retrievalGenerationPersistenceService.getOrCreate(
                        generationDefinition("generation-freeze")
                );
        SourceChunkIndexing firstMapping =
                sourceChunkIndexingPersistenceService.getOrCreate(
                        firstChunk,
                        generation
                );
        generation = retrievalGenerationPersistenceService.markProcessing(
                generation
        );
        RetrievalGeneration processingGeneration = generation;

        assertThatThrownBy(() ->
                sourceChunkIndexingPersistenceService.getOrCreate(
                        secondChunk,
                        processingGeneration
                )
        ).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("membership is frozen");

        // A retry for an already registered chunk remains idempotent after
        // processing has started.
        assertThat(sourceChunkIndexingPersistenceService.getOrCreate(
                firstChunk,
                processingGeneration
        ).getId()).isEqualTo(firstMapping.getId());

        firstMapping = sourceChunkIndexingPersistenceService.markProcessing(
                firstMapping
        );
        SourceChunk currentChunk = sourceChunkRepository
                .findById(firstChunk.getId())
                .orElseThrow();
        currentChunk.revokeApproval(BASE_TIME.plusMinutes(1));
        sourceChunkRepository.save(currentChunk);

        SourceChunkIndexing mappingBeforeReady = firstMapping;
        assertThatThrownBy(() ->
                sourceChunkIndexingPersistenceService.markReady(
                        mappingBeforeReady,
                        "{\"artifact\":\"should-not-save\"}"
                )
        ).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must be approved");

        SourceChunkIndexing unchanged = sourceChunkIndexingRepository
                .findById(firstMapping.getId())
                .orElseThrow();
        assertThat(unchanged.getIndexingStatus())
                .isEqualTo(SourceChunkIndexingStatus.PROCESSING);
        assertThat(unchanged.getIndexMetadataJson()).isEqualTo("{}");

        assertThatThrownBy(() ->
                retrievalGenerationPersistenceService.markReady(
                        processingGeneration
                )
        ).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("requires at least one READY");
    }

    @Test
    void generationDefinitionCannotBeOverwrittenBySameKey() {
        retrievalGenerationPersistenceService.getOrCreate(
                generationDefinition("immutable-generation")
        );

        RetrievalGenerationData.Definition changed =
                new RetrievalGenerationData.Definition(
                        "immutable-generation",
                        "representation-v1",
                        "different-model",
                        "revision-1",
                        "tokenizer",
                        "revision-1",
                        "{}",
                        "{}",
                        "{}"
                );

        assertThatThrownBy(() ->
                retrievalGenerationPersistenceService.getOrCreate(changed)
        ).isInstanceOf(RetrievalGenerationDefinitionConflictException.class);
    }

    private SourceDocument createDocument(String normalizedContent) {
        SourceRegistry source = sourceRegistryRepository
                .findBySourceKeyAndEnabledTrue("fsc-2026-alert")
                .orElseThrow();
        OffsetDateTime retrievedAt = BASE_TIME.plusNanos(
                sourceDocumentRepository.count()
        );
        byte[] raw = normalizedContent.getBytes(StandardCharsets.UTF_8);
        SourceIngestionData.Parsed parsed = new SourceIngestionData.Parsed(
                source.getCanonicalUrl(),
                "test source",
                null,
                retrievedAt,
                "text/html;charset=UTF-8",
                null,
                null,
                SourceHashing.sha256(raw),
                SourceHashing.sha256(normalizedContent),
                raw,
                normalizedContent
        );

        sourceDocumentWriter.saveIfCurrent(
                SourceIngestionData.Snapshot.from(source),
                parsed
        );

        return sourceDocumentRepository
                .findBySourceRegistry_IdAndStatus(
                        source.getId(),
                        SourceDocumentStatus.ACTIVE
                )
                .orElseThrow();
    }

    private SourceChunkData.Definition chunkDefinition(
            String configVersion,
            String configJson,
            int sequence,
            String body,
            int startOffset
    ) {
        return new SourceChunkData.Definition(
                configVersion,
                configJson,
                sequence,
                body,
                null,
                null,
                null,
                null,
                startOffset,
                startOffset + body.length(),
                "{}"
        );
    }

    private RetrievalGenerationData.Definition generationDefinition(
            String generationKey
    ) {
        return new RetrievalGenerationData.Definition(
                generationKey,
                "representation-v1",
                "model",
                "revision-1",
                "tokenizer",
                "revision-1",
                "{}",
                "{}",
                "{}"
        );
    }
}
