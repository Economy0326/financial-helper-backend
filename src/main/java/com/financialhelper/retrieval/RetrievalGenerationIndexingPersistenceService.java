package com.financialhelper.retrieval;

import com.financialhelper.source.RetrievalGeneration;
import com.financialhelper.source.RetrievalGenerationRepository;
import com.financialhelper.source.RetrievalGenerationStatus;
import com.financialhelper.source.SourceChunk;
import com.financialhelper.source.SourceChunkIndexing;
import com.financialhelper.source.SourceChunkIndexingPersistenceService;
import com.financialhelper.source.SourceChunkIndexingRepository;
import com.financialhelper.source.SourceChunkIndexingStatus;
import com.financialhelper.source.SourceChunkRepository;
import com.financialhelper.source.SourceChunkReviewStatus;
import com.financialhelper.source.SourceDocumentStatus;
import com.financialhelper.source.SourceHashing;
import com.financialhelper.source.DocumentClaimReviewerService;
import org.springframework.jdbc.core.JdbcTemplate;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 외부 KURE build 전후에 짧은 database transaction을 사용한다. 이 class는
 * HTTP를 호출하지 않고 상태를 snapshot, 검증 및 저장만 한다.
 */
@Service
public class RetrievalGenerationIndexingPersistenceService {

    private static final JsonMapper JSON_MAPPER = JsonMapper.builder().build();

    private final RetrievalGenerationRepository generationRepository;
    private final SourceChunkRepository chunkRepository;
    private final SourceChunkIndexingRepository indexingRepository;
    private final SourceChunkIndexingPersistenceService indexingPersistence;
    private final DocumentClaimReviewerService genericReviews;
    private final JdbcTemplate jdbc;

    public RetrievalGenerationIndexingPersistenceService(
            RetrievalGenerationRepository generationRepository,
            SourceChunkRepository chunkRepository,
            SourceChunkIndexingRepository indexingRepository,
            SourceChunkIndexingPersistenceService indexingPersistence,
            DocumentClaimReviewerService genericReviews,
            JdbcTemplate jdbc
    ) {
        this.generationRepository = generationRepository;
        this.chunkRepository = chunkRepository;
        this.indexingRepository = indexingRepository;
        this.indexingPersistence = indexingPersistence;
        this.genericReviews = genericReviews;
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public RetrievalGeneration inspect(UUID generationId) {
        return generationRepository.findById(generationId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "retrievalGeneration does not exist"
                ));
    }

    /**
     * generation을 lock하고 현재 승인된 active corpus를 snapshot한 뒤 누락 mapping을
     * 생성한다. 이후 membership을 동결하고 attempt를 점유한다.
     */
    @Transactional
    public GenerationBuildPlan start(UUID generationId) {
        RetrievalGeneration generation = generationRepository
                .findByIdForUpdate(generationId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "retrievalGeneration does not exist"
                ));

        if (generation.getStatus() != RetrievalGenerationStatus.PENDING
                && generation.getStatus() != RetrievalGenerationStatus.FAILED) {
            throw new IllegalStateException(
                    "Only pending or failed generations can start an indexing attempt"
            );
        }
        if (generation.getChunkConfigVersion() == null) {
            throw new IllegalStateException(
                    "generation chunk configuration is required"
            );
        }

        Set<String> sourceKeys = sourceKeys(generation.getMetadataJson());
        Set<UUID> exactChunkIds = chunkIds(generation.getMetadataJson());
        boolean exactGeneric = includesDocumentClaims(generation.getMetadataJson())
                && JSON_MAPPER.readTree(generation.getMetadataJson()).path("exactGenericMembership").asBoolean(false);
        Set<UUID> genericIds = new HashSet<>();
        Set<UUID> registeredGenericIds = new HashSet<>();
        if (includesDocumentClaims(generation.getMetadataJson())) {
            Set<UUID> documentIds = documentClaimDocumentIds(generation.getMetadataJson());
            for (UUID id : jdbc.queryForList("SELECT source_chunk_id FROM source_document_claim", UUID.class)) {
                registeredGenericIds.add(id);
            }
            for (UUID documentId : documentIds) {
                for (UUID id : jdbc.queryForList("SELECT source_chunk_id FROM source_document_claim WHERE source_document_id=?", UUID.class, documentId)) {
                    if ((!exactGeneric || exactChunkIds.contains(id)) && genericReviews.genericChunkMayBeSearched(id)) genericIds.add(id);
                }
            }
        }
        List<SourceChunk> approvedChunks = chunkRepository.findAllApprovedActive().stream()
                .filter(chunk -> genericIds.contains(chunk.getId()) ||
                        (!registeredGenericIds.contains(chunk.getId())
                        && generation.getChunkConfigVersion().equals(chunk.getChunkConfigVersion())
                        && (exactChunkIds.isEmpty() || exactChunkIds.contains(chunk.getId()))
                        && (sourceKeys.isEmpty() || sourceKeys.contains(chunk.getSourceDocument()
                        .getSourceRegistry().getSourceKey()))))
                .sorted(Comparator.comparing(chunk -> chunk.getId().toString()))
                .toList();
        if (approvedChunks.isEmpty()) {
            throw new IllegalStateException(
                    "At least one approved chunk in an active document is required"
            );
        }
        if (!exactChunkIds.isEmpty() && !approvedChunks.stream().map(SourceChunk::getId)
                .collect(Collectors.toSet()).containsAll(exactChunkIds)) {
            throw new IllegalStateException("reviewed chunk allowlist no longer matches approved corpus");
        }
        approvedChunks.forEach(chunk -> {
            if (!genericIds.contains(chunk.getId()) && !generation.getChunkConfigVersion().equals(chunk.getChunkConfigVersion())) {
                throw new IllegalStateException(
                        "approved chunk configuration does not match the generation"
                );
            }
        });

        // Serialize approval capture with document rights changes and claim decisions.
        for (UUID docId : approvedChunks.stream().map(c -> c.getSourceDocument().getId()).distinct().sorted().toList())
            jdbc.queryForObject("SELECT id FROM source_document WHERE id=? FOR UPDATE", UUID.class, docId);
        for (UUID chunkId : genericIds) {
            if (!approvedChunks.stream().anyMatch(c -> c.getId().equals(chunkId))) continue;
            if (!genericReviews.genericChunkMayBeSearched(chunkId)) throw new IllegalStateException("Document claim approval changed");
            int inserted = jdbc.update("""
                INSERT INTO retrieval_generation_claim_review(retrieval_generation_id,source_chunk_id,review_id,approval_audit_id)
                SELECT ?,c.source_chunk_id,r.id,r.current_claim_approval_id FROM source_document_claim c
                JOIN source_applicability_review r ON r.id=c.review_id
                WHERE c.source_chunk_id=? AND r.review_status='APPROVED' AND r.current_claim_approval_id IS NOT NULL
                ON CONFLICT (retrieval_generation_id,source_chunk_id) DO NOTHING
                """, generationId, chunkId);
            if (inserted == 0 && !genericReviews.generationReviewMatches(chunkId, generationId))
                throw new IllegalStateException("Generation claim approval changed");
        }

        String snapshot = corpusSnapshot(approvedChunks, searchContext(generation.getMetadataJson()));
        if (generation.getCorpusSnapshotSha256() != null
                && !generation.getCorpusSnapshotSha256().equals(snapshot)) {
            throw new IllegalStateException(
                    "approved corpus changed; create a new retrieval generation"
            );
        }

        List<SourceChunkIndexing> existing = indexingRepository
                .findAllForUpdateByGenerationId(generationId);
        Set<UUID> approvedIds = approvedChunks.stream()
                .map(SourceChunk::getId)
                .collect(Collectors.toSet());
        Set<UUID> existingIds = existing.stream()
                .map(indexing -> indexing.getSourceChunk().getId())
                .collect(Collectors.toSet());

        if (generation.getStatus() == RetrievalGenerationStatus.FAILED
                && !existingIds.equals(approvedIds)) {
            throw new IllegalStateException(
                    "failed generation membership no longer matches the approved corpus"
            );
        }

        for (SourceChunk chunk : approvedChunks) {
            indexingPersistence.getOrCreate(chunk, generation);
        }

        if (generation.getCorpusSnapshotSha256() == null) {
            generation.bindCorpusSnapshot(snapshot, now());
        }
        generation.markProcessing(now());
        generationRepository.saveAndFlush(generation);

        List<SourceChunkIndexing> mappings = indexingRepository
                .findAllForUpdateByGenerationId(generationId);
        if (mappings.size() != approvedChunks.size()) {
            throw new IllegalStateException(
                    "generation mappings do not cover the approved corpus"
            );
        }
        for (SourceChunkIndexing mapping : mappings) {
            SourceChunk chunk = mapping.getSourceChunk();
            if (chunk.getReviewStatus() != SourceChunkReviewStatus.APPROVED
                    || chunk.getSourceDocument().getStatus()
                    != SourceDocumentStatus.ACTIVE) {
                throw new IllegalStateException(
                        "chunk approval changed while starting the generation"
                );
            }
            mapping.beginAttempt(now());
        }
        indexingRepository.saveAll(mappings);

        List<KureRuntimeClient.KureRuntimeBuildRequest.Document> documents =
                mappings.stream()
                        .map(mapping -> new KureRuntimeClient.KureRuntimeBuildRequest.Document(
                                mapping.getExternalDocumentId(),
                                indexingText(generation, mapping.getSourceChunk())
                        ))
                        .toList();

        KureRuntimeClient.KureRuntimeBuildRequest request =
                new KureRuntimeClient.KureRuntimeBuildRequest(
                        generation.getId(),
                        generation.getGenerationKey(),
                        generation.getModelIdentifier(),
                        generation.getModelRevision(),
                        generation.getTokenizerIdentifier(),
                        generation.getTokenizerRevision(),
                        generation.getChunkConfigVersion(),
                        generation.getCorpusSnapshotSha256(),
                        generation.getEncodingConfigJson(),
                        generation.getIndexConfigJson(),
                        documents
                );
        return new GenerationBuildPlan(
                generation.getId(),
                approvedIds,
                request
        );
    }

    /** Claim a previously prepared mixed input once, then release the DB transaction before KURE. */
    @Transactional
    public GenerationBuildPlan claimPreparedMixed(UUID generationId) {
        RetrievalGeneration generation = generationRepository.findByIdForUpdate(generationId)
                .orElseThrow(() -> new IllegalArgumentException("retrievalGeneration does not exist"));
        if (!includesDocumentClaims(generation.getMetadataJson())
                || generation.getStatus() != RetrievalGenerationStatus.PROCESSING)
            throw new IllegalStateException("Only a prepared mixed candidate can be resumed");
        List<SourceChunkIndexing> mappings = indexingRepository.findAllForUpdateByGenerationId(generationId);
        if (mappings.isEmpty() || mappings.stream().anyMatch(m -> m.getIndexingStatus() != SourceChunkIndexingStatus.PROCESSING))
            throw new IllegalStateException("Prepared generation membership is invalid");
        List<SourceChunk> chunks = mappings.stream().map(SourceChunkIndexing::getSourceChunk).toList();
        if (!corpusSnapshot(chunks, searchContext(generation.getMetadataJson())).equals(generation.getCorpusSnapshotSha256()))
            throw new IllegalStateException("Prepared generation corpus changed");
        for (SourceChunk chunk : chunks) {
            if (chunk.getReviewStatus() != SourceChunkReviewStatus.APPROVED
                    || chunk.getSourceDocument().getStatus() != SourceDocumentStatus.ACTIVE)
                throw new IllegalStateException("Prepared generation approval changed");
            Long generic = jdbc.queryForObject("SELECT count(*) FROM source_document_claim WHERE source_chunk_id=?", Long.class, chunk.getId());
            if (generic != null && generic > 0 && (!genericReviews.genericChunkMayBeSearched(chunk.getId())
                    || !genericReviews.generationReviewMatches(chunk.getId(), generationId)))
                throw new IllegalStateException("Prepared document claim is no longer eligible");
        }
        if (jdbc.update("INSERT INTO mixed_generation_build_attempt(generation_id,attempt_id,status) VALUES (?,?,'RUNNING') ON CONFLICT (generation_id) DO NOTHING", generationId, UUID.randomUUID()) != 1)
            throw new IllegalStateException("Mixed candidate build already claimed");
        Set<UUID> ids = mappings.stream().map(SourceChunkIndexing::getExternalDocumentId).collect(Collectors.toSet());
        if (ids.size() != mappings.size()) throw new IllegalStateException("Duplicate prepared membership");
        var documents = mappings.stream().map(m -> new KureRuntimeClient.KureRuntimeBuildRequest.Document(
                m.getExternalDocumentId(), indexingText(generation, m.getSourceChunk()))).toList();
        return new GenerationBuildPlan(generationId, ids, new KureRuntimeClient.KureRuntimeBuildRequest(
                generationId, generation.getGenerationKey(), generation.getModelIdentifier(), generation.getModelRevision(),
                generation.getTokenizerIdentifier(), generation.getTokenizerRevision(), generation.getChunkConfigVersion(),
                generation.getCorpusSnapshotSha256(), generation.getEncodingConfigJson(), generation.getIndexConfigJson(), documents));
    }

    @Transactional
    public RetrievalGeneration complete(
            GenerationBuildPlan plan,
            KureRuntimeClient.KureRuntimeBuildResult result
    ) {
        if (plan == null || result == null
                || !plan.generationId().equals(result.generationId())) {
            throw new IllegalArgumentException(
                    "KURE build result does not match the generation"
            );
        }

        RetrievalGeneration generation = generationRepository
                .findByIdForUpdate(plan.generationId())
                .orElseThrow(() -> new IllegalArgumentException(
                        "retrievalGeneration does not exist"
                ));
        if (generation.getStatus() != RetrievalGenerationStatus.PROCESSING) {
            throw new IllegalStateException(
                    "only a processing generation can complete"
            );
        }

        Set<UUID> readyIds = new HashSet<>(result.readyDocumentIds());
        if (result.readyDocumentIds().size() != plan.expectedDocumentIds().size()
                || readyIds.size() != result.readyDocumentIds().size()
                || !readyIds.equals(plan.expectedDocumentIds())) {
            throw new IllegalStateException(
                    "KURE runtime did not return exactly the expected chunk IDs"
            );
        }

        boolean mixed = includesDocumentClaims(generation.getMetadataJson());
        if (mixed) {
            Long owner = jdbc.queryForObject("SELECT count(*) FROM mixed_generation_build_attempt WHERE generation_id=? AND status='RUNNING'", Long.class, generation.getId());
            if (owner == null || owner != 1) throw new IllegalStateException("Mixed build ownership is no longer valid");
            List<UUID> documentIds = jdbc.queryForList("""
                    SELECT DISTINCT c.source_document_id FROM source_chunk_indexing i
                    JOIN source_chunk c ON c.id=i.source_chunk_id
                    WHERE i.retrieval_generation_id=? ORDER BY c.source_document_id
                    """, UUID.class, generation.getId());
            for (UUID documentId : documentIds)
                jdbc.queryForObject("SELECT id FROM source_document WHERE id=? FOR UPDATE", UUID.class, documentId);
        }

        List<SourceChunkIndexing> mappings = indexingRepository
                .findAllForUpdateByGenerationId(plan.generationId());
        if (mappings.size() != plan.expectedDocumentIds().size()) {
            throw new IllegalStateException("generation membership changed during build");
        }
        if (mixed) {
            List<SourceChunk> sourceChunks = mappings.stream().map(SourceChunkIndexing::getSourceChunk).toList();
            if (!corpusSnapshot(sourceChunks, searchContext(generation.getMetadataJson())).equals(generation.getCorpusSnapshotSha256()))
                throw new IllegalStateException("Prepared generation corpus changed during build");
            for (SourceChunk chunk : sourceChunks) {
                var document = chunk.getSourceDocument();
                if (chunk.getReviewStatus() != SourceChunkReviewStatus.APPROVED
                        || document.getStatus() != SourceDocumentStatus.ACTIVE
                        || !SourceHashing.sha256(chunk.getBody()).equals(chunk.getBodySha256())
                        || !SourceHashing.sha256(document.getOriginalContent()).equals(document.getRawSha256())
                        || !SourceHashing.sha256(document.getNormalizedContent()).equals(document.getContentSha256()))
                    throw new IllegalStateException("Prepared generation document changed during build");
                Long generic = jdbc.queryForObject("SELECT count(*) FROM source_document_claim WHERE source_chunk_id=?", Long.class, chunk.getId());
                if (generic != null && generic > 0 && (!genericReviews.genericChunkMayBeSearched(chunk.getId())
                        || !genericReviews.generationReviewMatches(chunk.getId(), generation.getId())))
                    throw new IllegalStateException("Prepared document claim is no longer eligible");
            }
        }
        String metadata = result.indexMetadataJson() == null
                ? "{}"
                : result.indexMetadataJson();
        for (SourceChunkIndexing mapping : mappings) {
            if (!plan.expectedDocumentIds().contains(mapping.getExternalDocumentId())
                    || mapping.getIndexingStatus() != SourceChunkIndexingStatus.PROCESSING) {
                throw new IllegalStateException(
                        "chunk mapping is not in the expected processing state"
                );
            }
            mapping.markReady(now(), metadata);
        }
        indexingRepository.saveAll(mappings);
        generation.markReady(mappings.size(), now());
        if (mixed && jdbc.update("UPDATE mixed_generation_build_attempt SET status='BUILT',updated_at=CURRENT_TIMESTAMP WHERE generation_id=? AND status='RUNNING'", generation.getId()) != 1)
            throw new IllegalStateException("Mixed build ownership is no longer valid");
        return generationRepository.save(generation);
    }

    /** Explicit cancellation fences a late builder; retries require a new candidate. */
    @Transactional
    public void cancelMixedAttempt(UUID generationId) {
        RetrievalGeneration generation = generationRepository.findByIdForUpdate(generationId)
                .orElseThrow(() -> new IllegalArgumentException("generation missing"));
        if (!includesDocumentClaims(generation.getMetadataJson()) || generation.getStatus() != RetrievalGenerationStatus.PROCESSING)
            throw new IllegalStateException("Mixed build is not processing");
        int changed = jdbc.update("UPDATE mixed_generation_build_attempt SET status='FAILED',updated_at=CURRENT_TIMESTAMP WHERE generation_id=? AND status='RUNNING'", generationId);
        if (changed == 0) {
            changed = jdbc.update("INSERT INTO mixed_generation_build_attempt(generation_id,attempt_id,status) VALUES (?,?,'FAILED') ON CONFLICT (generation_id) DO NOTHING", generationId, UUID.randomUUID());
        }
        if (changed != 1) throw new IllegalStateException("Mixed build is not running");
        failIfProcessing(generationId, "Mixed build cancelled");
    }

    @Transactional
    public void failIfProcessing(UUID generationId, String reason) {
        RetrievalGeneration generation = generationRepository
                .findByIdForUpdate(generationId)
                .orElse(null);
        if (generation == null
                || generation.getStatus() != RetrievalGenerationStatus.PROCESSING) {
            return;
        }

        String safeReason = reason == null || reason.isBlank()
                ? "KURE indexing failed"
                : reason;
        List<SourceChunkIndexing> mappings = indexingRepository
                .findAllForUpdateByGenerationId(generationId);
        for (SourceChunkIndexing mapping : mappings) {
            if (mapping.getIndexingStatus() != SourceChunkIndexingStatus.FAILED) {
                mapping.markFailedAfterAttempt(safeReason, now());
            }
        }
        indexingRepository.saveAll(mappings);
        generation.markFailed(safeReason, now());
        generationRepository.save(generation);
    }

    public static boolean searchContext(String metadata) {
        return metadata != null && JSON_MAPPER.readTree(metadata).path("searchContextV1").asBoolean(false);
    }

    private String indexingText(RetrievalGeneration generation, SourceChunk chunk) {
        String body = chunk.getBody();
        if (!searchContext(generation.getMetadataJson())) return body;
        return java.util.stream.Stream.of(chunk.getSourceDocument().getTitle(), chunk.getParentSection(), body)
                .filter(value -> value != null && !value.isBlank()).collect(Collectors.joining("\n"));
    }

    public static String corpusSnapshot(List<SourceChunk> chunks, boolean withContext) {
        String material = chunks.stream()
                .sorted(Comparator.comparing(chunk -> chunk.getId().toString()))
                .map(chunk -> String.join("|",
                        chunk.getId().toString(),
                        chunk.getBodySha256(),
                        chunk.getSourceDocument().getId().toString(),
                        chunk.getSourceDocument().getContentSha256()
                        ) + (withContext ? "|" + SourceHashing.sha256(java.util.Objects.toString(chunk.getSourceDocument().getTitle(), ""))
                                + "|" + SourceHashing.sha256(java.util.Objects.toString(chunk.getParentSection(), "")) : ""))
                .collect(Collectors.joining("\n"));
        return SourceHashing.sha256(material);
    }

    private static Set<String> sourceKeys(String metadataJson) {
        if (metadataJson == null || metadataJson.isBlank()) {
            return Set.of();
        }
        try {
            JsonNode root = JSON_MAPPER.readTree(metadataJson);
            JsonNode values = root == null ? null : root.get("sourceKeys");
            if (values == null) {
                return Set.of();
            }
            if (!values.isArray() || values.isEmpty()) {
                throw new IllegalStateException(
                        "retrieval generation sourceKeys must be a non-empty array");
            }
            Set<String> result = new HashSet<>();
            for (JsonNode value : values) {
                if (value == null || !value.isString() || value.asString().isBlank()) {
                    throw new IllegalStateException(
                            "retrieval generation sourceKeys must contain nonblank strings");
                }
                result.add(value.asString());
            }
            return Set.copyOf(result);
        } catch (JacksonException exception) {
            throw new IllegalStateException(
                    "retrieval generation metadata is invalid", exception);
        }
    }

    public static boolean includesDocumentClaims(String metadataJson) {
        try {
            JsonNode root = JSON_MAPPER.readTree(metadataJson == null ? "{}" : metadataJson);
            JsonNode value = root == null ? null : root.get("includeDocumentClaims");
            if (value == null) return false;
            if (!value.isBoolean()) throw new IllegalStateException("includeDocumentClaims must be boolean");
            return value.asBoolean();
        } catch (JacksonException exception) {
            throw new IllegalStateException("retrieval generation metadata is invalid", exception);
        }
    }

    public static Set<UUID> documentClaimDocumentIds(String metadataJson) {
        try {
            JsonNode values = JSON_MAPPER.readTree(metadataJson).get("documentClaimDocumentIds");
            if (values == null || !values.isArray() || values.isEmpty())
                throw new IllegalStateException("documentClaimDocumentIds must be a non-empty array");
            Set<UUID> ids = new HashSet<>();
            for (JsonNode value : values) {
                if (!value.isString() || !ids.add(UUID.fromString(value.asString())))
                    throw new IllegalStateException("documentClaimDocumentIds must contain unique UUIDs");
            }
            return Set.copyOf(ids);
        } catch (JacksonException | IllegalArgumentException exception) {
            throw new IllegalStateException("documentClaimDocumentIds are invalid", exception);
        }
    }

    public static Set<UUID> chunkIds(String metadataJson) {
        try {
            JsonNode root = JSON_MAPPER.readTree(metadataJson);
            JsonNode values = root == null ? null : root.get("chunkIds");
            if (values == null) return Set.of();
            if (!values.isArray() || values.isEmpty()) {
                throw new IllegalStateException("retrieval generation chunkIds must be a non-empty array");
            }
            Set<UUID> ids = new HashSet<>();
            for (JsonNode value : values) {
                if (value == null || !value.isString()) {
                    throw new IllegalStateException("retrieval generation chunkIds must contain UUID strings");
                }
                if (!ids.add(UUID.fromString(value.asString()))) {
                    throw new IllegalStateException("retrieval generation chunkIds must be unique");
                }
            }
            return Set.copyOf(ids);
        } catch (JacksonException | IllegalArgumentException exception) {
            throw new IllegalStateException("retrieval generation chunkIds are invalid", exception);
        }
    }

    private static OffsetDateTime now() {
        return OffsetDateTime.now(ZoneOffset.UTC);
    }

    public record GenerationBuildPlan(
            UUID generationId,
            Set<UUID> expectedDocumentIds,
            KureRuntimeClient.KureRuntimeBuildRequest request
    ) {
        public GenerationBuildPlan {
            if (generationId == null || request == null) {
                throw new IllegalArgumentException("generationId and request are required");
            }
            expectedDocumentIds = expectedDocumentIds == null
                    ? Set.of()
                    : Set.copyOf(expectedDocumentIds);
        }
    }
}
