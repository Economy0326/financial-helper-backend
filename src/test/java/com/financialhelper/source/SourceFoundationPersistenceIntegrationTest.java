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
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
class SourceFoundationPersistenceIntegrationTest {

    private final String fixtureToken = UUID.randomUUID().toString();
    private final Set<UUID> ownedDocumentIds = new HashSet<>();
    private final Set<UUID> ownedChunkIds = new HashSet<>();
    private final Set<UUID> ownedGenerationIds = new HashSet<>();
    private final Set<UUID> ownedIndexingIds = new HashSet<>();
    private final Set<String> ownedConfigVersions = new HashSet<>();
    private Set<String> configVersionsBeforeTest = Set.of();
    private UUID fixtureSourceRegistryId;
    private String fixtureSourceKey;

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
    private RetrievalGenerationRepository retrievalGenerationRepository;

    @Autowired
    private SourceChunkIndexingPersistenceService
            sourceChunkIndexingPersistenceService;

    @Autowired
    private SourceChunkIndexingRepository sourceChunkIndexingRepository;

    @Autowired
    private SourceChunkConfigurationRepository sourceChunkConfigurationRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void captureFixtureBaseline() {
        configVersionsBeforeTest = sourceChunkConfigurationRepository.findAll().stream()
                .map(SourceChunkConfiguration::getConfigVersion)
                .collect(Collectors.toUnmodifiableSet());
        fixtureSourceRegistryId = UUID.randomUUID();
        fixtureSourceKey = "test-fsc-" + fixtureToken.replace("-", "");
        jdbcTemplate.update(
                """
                INSERT INTO source_registry (
                    id, source_key, organization_name, official_domain,
                    canonical_url, acquisition_type, enabled, created_at,
                    updated_at, content_selector
                ) VALUES (?, ?, ?, ?, ?, ?, TRUE, CURRENT_TIMESTAMP,
                    CURRENT_TIMESTAMP, NULL)
                """,
                fixtureSourceRegistryId,
                fixtureSourceKey,
                "금융위원회",
                "fsc.go.kr",
                "https://www.fsc.go.kr/no010101/86271?fixture=" + fixtureToken,
                SourceAcquisitionType.HTML.name()
        );
    }

    @AfterEach
    void cleanOwnedRows() {
        // Do not clear production-like corpus rows.  Every test records only
        // rows it created and removes those rows in dependency order.
        if (!ownedIndexingIds.isEmpty()) {
            sourceChunkIndexingRepository.deleteAllByIdInBatch(ownedIndexingIds);
        }
        if (!ownedChunkIds.isEmpty()) {
            sourceChunkRepository.deleteAllByIdInBatch(ownedChunkIds);
        }
        if (!ownedGenerationIds.isEmpty()) {
            retrievalGenerationRepository.deleteAllByIdInBatch(ownedGenerationIds);
        }
        if (!ownedDocumentIds.isEmpty()) {
            sourceDocumentRepository.deleteAllByIdInBatch(ownedDocumentIds);
        }
        ownedConfigVersions.removeAll(configVersionsBeforeTest);
        if (!ownedConfigVersions.isEmpty()) {
            sourceChunkConfigurationRepository.deleteAllByIdInBatch(ownedConfigVersions);
        }
        if (fixtureSourceRegistryId != null) {
            sourceRegistryRepository.deleteById(fixtureSourceRegistryId);
        }
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
        rememberChunk(first, definition.chunkConfigVersion());
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
        rememberChunk(retry, "chunk-v1");

        assertThat(retry.getId()).isEqualTo(first.getId());
        assertThat(ownedChunkIds).hasSize(1);

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
        rememberChunk(differentConfiguration, "chunk-v2");

        assertThat(differentConfiguration.getId())
                .isNotEqualTo(first.getId());
        assertThat(ownedChunkIds).hasSize(2);
    }

    @Test
    void aNewDocumentVersionStartsWithPendingReview() {
        SourceDocument firstDocument = createDocument("버전 하나");
        SourceChunk firstChunk = sourceChunkPersistenceService.saveIfAbsent(
                firstDocument,
                chunkDefinition("chunk-v1", "{}", 0, "버전 하나", 0)
        );
        rememberChunk(firstChunk, "chunk-v1");
        firstChunk.approve(BASE_TIME);
        sourceChunkRepository.save(firstChunk);

        SourceDocument secondDocument = createDocument("버전 둘");
        SourceChunk secondChunk = sourceChunkPersistenceService.saveIfAbsent(
                secondDocument,
                chunkDefinition("chunk-v1", "{}", 0, "버전 둘", 0)
        );
        rememberChunk(secondChunk, "chunk-v1");

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
        rememberChunk(chunk, "chunk-v1");
        chunk.approve(BASE_TIME);
        sourceChunkRepository.save(chunk);

        RetrievalGenerationData.Definition generationDefinition =
                generationDefinition(fixtureKey("generation-v1"));
        RetrievalGeneration generation =
                retrievalGenerationPersistenceService.getOrCreate(
                        generationDefinition
                );
        rememberGeneration(generation);
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
        rememberIndexing(mapping);
        SourceChunkIndexing retryMapping =
                sourceChunkIndexingPersistenceService.getOrCreate(
                        chunk,
                        generation
                );
        assertThat(retryMapping.getId()).isEqualTo(mapping.getId());
        assertThat(mapping.getExternalDocumentId()).isEqualTo(chunk.getId());
        assertThat(ownedIndexingIds).hasSize(1);

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
        rememberChunk(firstChunk, "chunk-v1");
        SourceChunk secondChunk = sourceChunkPersistenceService.saveIfAbsent(
                document,
                chunkDefinition("chunk-v1", "{}", 1, "나", 1)
        );
        rememberChunk(secondChunk, "chunk-v1");
        firstChunk.approve(BASE_TIME);
        secondChunk.approve(BASE_TIME);
        sourceChunkRepository.save(firstChunk);
        sourceChunkRepository.save(secondChunk);

        RetrievalGeneration generation =
                retrievalGenerationPersistenceService.getOrCreate(
                        generationDefinition(fixtureKey("generation-freeze"))
                );
        rememberGeneration(generation);
        SourceChunkIndexing firstMapping =
                sourceChunkIndexingPersistenceService.getOrCreate(
                        firstChunk,
                        generation
                );
        rememberIndexing(firstMapping);
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
        RetrievalGeneration generation = retrievalGenerationPersistenceService.getOrCreate(
                generationDefinition(fixtureKey("immutable-generation"))
        );
        rememberGeneration(generation);

        RetrievalGenerationData.Definition changed =
                new RetrievalGenerationData.Definition(
                        fixtureKey("immutable-generation"),
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
                .findBySourceKeyAndEnabledTrue(fixtureSourceKey)
                .orElseThrow();
        OffsetDateTime retrievedAt = BASE_TIME.plusNanos(
                sourceDocumentRepository.count()
        );
        String fixtureContent = normalizedContent + "\nfixture=" + fixtureToken;
        Set<UUID> before = documentIds(source.getId());
        byte[] raw = fixtureContent.getBytes(StandardCharsets.UTF_8);
        SourceIngestionData.Parsed parsed = new SourceIngestionData.Parsed(
                source.getCanonicalUrl(),
                "test source " + fixtureToken,
                null,
                retrievedAt,
                "text/html;charset=UTF-8",
                null,
                null,
                SourceHashing.sha256(raw),
                SourceHashing.sha256(fixtureContent),
                raw,
                fixtureContent
        );

        sourceDocumentWriter.saveIfCurrent(
                SourceIngestionData.Snapshot.from(source),
                parsed
        );

        SourceDocument document = sourceDocumentRepository
                .findBySourceRegistry_IdAndStatus(
                        source.getId(),
                        SourceDocumentStatus.ACTIVE
                )
                .orElseThrow();
        Set<UUID> created = documentIds(source.getId());
        created.removeAll(before);
        ownedDocumentIds.addAll(created);
        return document;
    }

    private Set<UUID> documentIds(UUID sourceRegistryId) {
        return new HashSet<>(jdbcTemplate.queryForList(
                "SELECT id FROM source_document WHERE source_registry_id = ?",
                UUID.class,
                sourceRegistryId
        ));
    }

    private void rememberChunk(SourceChunk chunk, String configVersion) {
        ownedChunkIds.add(chunk.getId());
        if (!configVersionsBeforeTest.contains(configVersion)) {
            ownedConfigVersions.add(configVersion);
        }
    }

    private void rememberGeneration(RetrievalGeneration generation) {
        ownedGenerationIds.add(generation.getId());
    }

    private void rememberIndexing(SourceChunkIndexing indexing) {
        ownedIndexingIds.add(indexing.getId());
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

    private String fixtureKey(String suffix) {
        return "test-" + fixtureToken + "-" + suffix;
    }
}
