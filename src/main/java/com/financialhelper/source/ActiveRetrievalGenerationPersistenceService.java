package com.financialhelper.source;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.core.env.Environment;
import com.financialhelper.retrieval.SearchAvailabilityWindowGate;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

/** 단일 row의 atomic active-generation 전환을 수행한다. */
@Service
public class ActiveRetrievalGenerationPersistenceService {

    private final RetrievalGenerationRepository retrievalGenerationRepository;
    private final ActiveRetrievalGenerationRepository activeRepository;
    private final SourceChunkIndexingRepository indexing;
    private final DocumentClaimReviewerService claims;
    private final SearchAvailabilityWindowGate searchWindow;
    private final JdbcTemplate jdbc;
    private final Environment environment;

    public ActiveRetrievalGenerationPersistenceService(
            RetrievalGenerationRepository retrievalGenerationRepository,
            ActiveRetrievalGenerationRepository activeRepository,
            SourceChunkIndexingRepository indexing, DocumentClaimReviewerService claims,
            SearchAvailabilityWindowGate searchWindow, JdbcTemplate jdbc, Environment environment
    ) {
        this.retrievalGenerationRepository = retrievalGenerationRepository;
        this.activeRepository = activeRepository;
        this.indexing = indexing; this.claims = claims; this.searchWindow = searchWindow;
        this.jdbc = jdbc; this.environment = environment;
    }

    /** Isolated-test-only mixed activation. Production remains fail-closed until retrieval revalidation exists. */
    @Transactional
    public void activateIsolatedMixed(UUID generationId) {
        String run = System.getenv("FH_TEST_RUN_ID");
        String url = environment.getProperty("spring.datasource.url", "");
        if (!java.util.Set.of(environment.getActiveProfiles()).equals(java.util.Set.of("test"))
                || run == null || !run.matches("[0-9a-f]{32}")
                || !url.matches("jdbc:postgresql://127\\.0\\.0\\.1:(?!15432/)\\d+/fh_test_" + run))
            throw new IllegalStateException("Mixed activation is restricted to the isolated test database");
        activateReviewedMixedInternal(generationId);
    }

    /** Revalidate a built reviewer candidate immediately before switching the pointer. */
    @Transactional
    public void activateReviewedMixed(UUID generationId) {
        activateReviewedMixedInternal(generationId);
    }

    private void activateReviewedMixedInternal(UUID generationId) {
        lockActivation();
        RetrievalGeneration generation = retrievalGenerationRepository.findByIdForUpdate(generationId)
                .orElseThrow(() -> new IllegalArgumentException("generation missing"));
        if (generation.getStatus() != RetrievalGenerationStatus.READY
                || !com.financialhelper.retrieval.RetrievalGenerationIndexingPersistenceService.includesDocumentClaims(generation.getMetadataJson()))
            throw new IllegalStateException("Mixed candidate is not built");
        var json = tools.jackson.databind.json.JsonMapper.builder().build().readTree(generation.getMetadataJson());
        var baseNode = json.get("baseGenerationId");
        var revisionNode = json.get("baseActivationRevision");
        if (baseNode == null || !baseNode.isString() || revisionNode == null || !revisionNode.isString())
            throw new IllegalStateException("Candidate has no base generation revision");
        UUID base = baseNode.asString().isEmpty() ? null : UUID.fromString(baseNode.asString());
        long expectedRevision = Long.parseLong(revisionNode.asString());
        var pointer = activeRepository.findSingletonForUpdate();
        var revisions = jdbc.queryForList("SELECT activation_revision FROM active_retrieval_generation WHERE singleton_key=TRUE", Long.class);
        if ((base == null && (pointer.isPresent() || expectedRevision != 0))
                || (base != null && (pointer.isEmpty() || !base.equals(pointer.get().getRetrievalGeneration().getId())
                    || revisions.size() != 1 || expectedRevision != revisions.getFirst())))
            throw new IllegalStateException("Active generation changed");
        Long built = jdbc.queryForObject("SELECT count(*) FROM mixed_generation_build_attempt WHERE generation_id=? AND status='BUILT'", Long.class, generationId);
        if (built == null || built != 1) throw new IllegalStateException("Build attempt is not complete");
        var documentIds = jdbc.queryForList("""
                SELECT DISTINCT c.source_document_id FROM source_chunk_indexing i JOIN source_chunk c ON c.id=i.source_chunk_id
                WHERE i.retrieval_generation_id=? ORDER BY c.source_document_id
                """, UUID.class, generationId);
        for (UUID id : documentIds) jdbc.queryForObject("SELECT id FROM source_document WHERE id=? FOR UPDATE", UUID.class, id);
        for (UUID id : documentIds) jdbc.queryForList("SELECT id FROM source_applicability_review WHERE source_document_id=? ORDER BY id FOR UPDATE", UUID.class, id);
        var mappings = indexing.findAllForUpdateByGenerationId(generationId);
        if (mappings.isEmpty() || mappings.size() != generation.getReadyChunkCount()
                || mappings.stream().anyMatch(m -> m.getIndexingStatus() != SourceChunkIndexingStatus.READY
                    || !m.getExternalDocumentId().equals(m.getSourceChunk().getId())))
            throw new IllegalStateException("Built membership changed");
        var chunks = mappings.stream().map(SourceChunkIndexing::getSourceChunk)
                .sorted(java.util.Comparator.comparing(c -> c.getId().toString())).toList();
        String corpusHash = com.financialhelper.retrieval.RetrievalGenerationIndexingPersistenceService.corpusSnapshot(chunks,
                com.financialhelper.retrieval.RetrievalGenerationIndexingPersistenceService.searchContext(generation.getMetadataJson()));
        if (!corpusHash.equals(generation.getCorpusSnapshotSha256()))
            throw new IllegalStateException("Built membership changed");
        for (SourceChunk chunk : chunks) {
            SourceDocument doc = chunk.getSourceDocument();
            if (chunk.getReviewStatus() != SourceChunkReviewStatus.APPROVED || doc.getStatus() != SourceDocumentStatus.ACTIVE
                    || !SourceHashing.sha256(chunk.getBody()).equals(chunk.getBodySha256())
                    || !SourceHashing.sha256(doc.getOriginalContent()).equals(doc.getRawSha256())
                    || !SourceHashing.sha256(doc.getNormalizedContent()).equals(doc.getContentSha256()))
                throw new IllegalStateException("Document or approval changed");
            Long generic = jdbc.queryForObject("SELECT count(*) FROM source_document_claim WHERE source_chunk_id=?", Long.class, chunk.getId());
            if (generic != null && generic > 0) {
                if (!claims.genericChunkMayBeDisplayed(chunk.getId()) || !claims.generationReviewMatches(chunk.getId(), generationId)) throw new IllegalStateException("Document claim approval changed");
            } else {
                requireLegacyApproval(chunk, doc);
            }
        }
        activeRepository.upsertSingleton(generationId, OffsetDateTime.now(ZoneOffset.UTC));
    }

    private void requireLegacyApproval(SourceChunk chunk, SourceDocument document) {
        String sourceKey = document.getSourceRegistry().getSourceKey();
        boolean information = OfficialInformationClaimSelector.supports(sourceKey);
        String expectedTopic = information ? OfficialInformationClaimSelector.topic(sourceKey).name() : null;
        String expectedRole = information ? OfficialInformationClaimSelector.role(sourceKey, chunk.getSequence()) : null;
        if (information && (!OfficialInformationClaimSelector.officialUrl(sourceKey).equals(document.getCanonicalUrl())
                || OfficialInformationClaimSelector.select(document, chunk.getChunkConfigVersion(),
                    chunk.getChunkConfiguration().getConfigJson()).stream().noneMatch(chunk::hasSameDefinition)))
            throw new IllegalStateException("Legacy source definition changed");
        var reviews = jdbc.query("""
                SELECT r.id,r.temporal_policy,r.applicability_start_date,r.applicability_end_date,r.temporal_verified_on,r.temporal_valid_until
                FROM source_applicability_review r JOIN source_applicability_chunk c ON c.review_id=r.id
                WHERE c.source_chunk_id=? AND r.source_document_id=? AND r.reviewed_raw_sha256=?
                  AND r.review_status='APPROVED' AND r.revoked_at IS NULL AND r.conditions_reviewed AND r.exceptions_reviewed
                  AND (? = FALSE OR (r.review_target_kind='TOPIC' AND r.financial_topic=? AND r.scope_kind='COMMON' AND c.evidence_role=?))
                  AND (NOT r.condition_required OR EXISTS (SELECT 1 FROM source_applicability_chunk x WHERE x.review_id=r.id AND x.evidence_role='CONDITION'))
                  AND (NOT r.exception_required OR EXISTS (SELECT 1 FROM source_applicability_chunk x WHERE x.review_id=r.id AND x.evidence_role='EXCEPTION'))
                  AND NOT EXISTS (SELECT 1 FROM source_applicability_chunk x JOIN source_chunk linked ON linked.id=x.source_chunk_id
                      WHERE x.review_id=r.id AND x.evidence_role IN ('CONDITION','EXCEPTION') AND linked.review_status <> 'APPROVED')
                  AND EXISTS (SELECT 1 FROM source_review_decision_audit a WHERE a.review_id=r.id AND a.source_document_id=r.source_document_id
                      AND a.reviewed_raw_sha256=r.reviewed_raw_sha256 AND a.decision='APPROVED' AND a.reviewer_account_id::text=r.reviewed_by)
                """, (rs,n) -> {
                    var policy = TemporalEvidencePolicy.valueOf(rs.getString(2));
                    var start = rs.getObject(3, java.time.LocalDate.class);
                    var end = rs.getObject(4, java.time.LocalDate.class);
                    var verified = rs.getObject(5, java.time.LocalDate.class);
                    var until = rs.getObject(6, java.time.LocalDate.class);
                    var today = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Seoul"));
                    boolean usable = policy.usable(today, false, start, end, verified, until)
                            || (start != null && policy.usable(start, true, start, end, verified, until));
                    return usable && searchWindow.allows(rs.getObject(1, UUID.class));
                }, chunk.getId(), document.getId(), document.getRawSha256(), information, expectedTopic, expectedRole);
        if (reviews.size() != 1 || !reviews.getFirst()) throw new IllegalStateException("Legacy evidence is no longer eligible");
    }

    @Transactional
    public ActiveRetrievalGeneration activate(UUID generationId) {
        lockActivation();
        if (generationId == null) {
            throw new IllegalArgumentException("generationId must not be null");
        }

        RetrievalGeneration generation = retrievalGenerationRepository
                .findByIdForUpdate(generationId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "retrievalGeneration does not exist"
                ));

        if (generation.getStatus() != RetrievalGenerationStatus.READY) {
            throw new IllegalStateException(
                    "Only a READY retrieval generation can become active"
            );
        }

        if (com.financialhelper.retrieval.RetrievalGenerationIndexingPersistenceService
                .includesDocumentClaims(generation.getMetadataJson())) {
            throw new IllegalStateException("Document claim generation activation is not yet supported");
        }

        OffsetDateTime switchedAt = OffsetDateTime.now(ZoneOffset.UTC);
        activeRepository.upsertSingleton(generationId, switchedAt);
        return activeRepository.findSingleton()
                .orElseThrow(() -> new IllegalStateException(
                        "active retrieval generation could not be persisted"
                ));
    }

    private void lockActivation() {
        jdbc.query("SELECT pg_advisory_xact_lock(734823917)", rs -> {});
    }

    @Transactional(readOnly = true)
    public Optional<RetrievalGeneration> current() {
        return activeRepository.findSingleton()
                .map(ActiveRetrievalGeneration::getRetrievalGeneration);
    }

    /** Scalar query avoids returning a stale pointer from the JPA persistence context. */
    @Transactional(readOnly = true)
    public Optional<UUID> currentId() {
        return activeRepository.findCurrentGenerationId();
    }
}
