package com.financialhelper.retrieval;

import com.financialhelper.source.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.stream.Collectors;

/** Explicit post-review generation build. Preserves every currently READY chunk. */
@Service
public class OfficialInformationReadyService {
    private final SourceReviewerService authority;
    private final ActiveRetrievalGenerationPersistenceService active;
    private final RetrievalCorpusSnapshotService corpus;
    private final SourceChunkRepository chunks;
    private final SourceRegistryRepository registries;
    private final SourceDocumentRepository documents;
    private final RetrievalGenerationPersistenceService generations;
    private final RetrievalGenerationIndexingService indexing;
    private final JdbcTemplate jdbc;
    private final SearchAvailabilityWindowGate searchWindow;
    private final RetrievalGenerationIndexingPersistenceService indexingPersistence;

    public OfficialInformationReadyService(SourceReviewerService authority,
            ActiveRetrievalGenerationPersistenceService active,
            RetrievalCorpusSnapshotService corpus, SourceChunkRepository chunks,
            SourceRegistryRepository registries,
            SourceDocumentRepository documents,
            RetrievalGenerationPersistenceService generations,
            RetrievalGenerationIndexingService indexing, JdbcTemplate jdbc, SearchAvailabilityWindowGate searchWindow,
            RetrievalGenerationIndexingPersistenceService indexingPersistence) {
        this.authority = authority;
        this.active = active;
        this.corpus = corpus;
        this.chunks = chunks;
        this.registries = registries;
        this.documents = documents;
        this.generations = generations;
        this.indexing = indexing;
        this.jdbc = jdbc;
        this.searchWindow = searchWindow;
        this.indexingPersistence = indexingPersistence;
    }

    /** Prepare an inactive mixed candidate; no network build or pointer switch is performed. */
    @Transactional
    public UUID prepareMixedCandidate(String sourceKey, UUID documentId, List<UUID> genericDocumentIds) {
        authority.requireReviewer();
        if (!OfficialInformationClaimSelector.supports(sourceKey) || genericDocumentIds == null
                || genericDocumentIds.isEmpty() || genericDocumentIds.stream().anyMatch(java.util.Objects::isNull)
                || new HashSet<>(genericDocumentIds).size() != genericDocumentIds.size()) {
            throw new SourceReviewException(HttpStatus.BAD_REQUEST, "SOURCE_REVIEW_SCOPE_CHANGED");
        }
        SourceDocument document = documents.findWithSourceRegistryById(documentId).orElseThrow(() ->
                new SourceReviewException(HttpStatus.NOT_FOUND, "SOURCE_REVIEW_DOCUMENT_MISSING"));
        if (document.getStatus() != SourceDocumentStatus.ACTIVE || !sourceKey.equals(document.getSourceRegistry().getSourceKey()))
            throw new SourceReviewException(HttpStatus.CONFLICT, "SOURCE_REVIEW_VERSION_CHANGED");
        RetrievalGeneration current = active.current().orElseThrow(() ->
                new SourceReviewException(HttpStatus.CONFLICT, "SOURCE_REVIEW_READY_BASE_MISSING"));
        if (current.getStatus() != RetrievalGenerationStatus.READY || current.getChunkConfigVersion() == null)
            throw new SourceReviewException(HttpStatus.CONFLICT, "SOURCE_REVIEW_READY_BASE_MISSING");
        Long baseRevision = jdbc.queryForObject("SELECT activation_revision FROM active_retrieval_generation WHERE singleton_key=TRUE AND retrieval_generation_id=?", Long.class, current.getId());
        var ready = corpus.readyChunks(current.getId());
        if (ready.size() != current.getReadyChunkCount())
            throw new SourceReviewException(HttpStatus.CONFLICT, "SOURCE_REVIEW_READY_BASE_STALE");
        Set<UUID> ids = ready.stream().map(RetrievalCorpusSnapshotService.EligibleChunk::sourceChunkId).collect(Collectors.toCollection(HashSet::new));
        List<SourceChunk> requested = chunks.findAllBySourceDocument_IdOrderBySequenceAsc(documentId).stream()
                .filter(c -> c.getReviewStatus() == SourceChunkReviewStatus.APPROVED).toList();
        if (requested.isEmpty()) throw new SourceReviewException(HttpStatus.CONFLICT, "SOURCE_REVIEW_INCOMPLETE");
        for (SourceChunk chunk : requested) {
            validateApproved(sourceKey, document, chunk, current.getChunkConfigVersion());
            ids.add(chunk.getId());
        }
        String chunkIds = ids.stream().sorted().map(id -> "\"" + id + "\"").collect(Collectors.joining(","));
        // Retain previously active generic evidence when extending a mixed generation.
        // start() rechecks the current review and usage eligibility for every document.
        Set<UUID> mixedDocumentIds = new HashSet<>(genericDocumentIds);
        for (UUID chunkId : ids) {
            mixedDocumentIds.addAll(jdbc.queryForList(
                    "SELECT source_document_id FROM source_document_claim WHERE source_chunk_id=?",
                    UUID.class, chunkId));
        }
        String documentIds = mixedDocumentIds.stream().sorted().map(id -> "\"" + id + "\"").collect(Collectors.joining(","));
        String metadata = "{\"corpus\":\"REVIEWED-MIXED-CANDIDATE\",\"includeDocumentClaims\":true,\"baseGenerationId\":\"" + current.getId() + "\",\"baseActivationRevision\":\"" + baseRevision + "\",\"chunkIds\":[" + chunkIds
                + "],\"documentClaimDocumentIds\":[" + documentIds + "]}";
        RetrievalGeneration candidate = generations.getOrCreate(new RetrievalGenerationData.Definition(
                "mixed-candidate-" + UUID.randomUUID(), current.getRepresentationConfigVersion(), current.getModelIdentifier(),
                current.getModelRevision(), current.getTokenizerIdentifier(), current.getTokenizerRevision(),
                current.getEncodingConfigJson(), current.getIndexConfigJson(), metadata, current.getChunkConfigVersion(), null));
        var plan = indexingPersistence.start(candidate.getId());
        if (plan.expectedDocumentIds().size() <= ids.size())
            throw new SourceReviewException(HttpStatus.CONFLICT, "SOURCE_REVIEW_INCOMPLETE");
        return candidate.getId();
    }

    public void buildMixedCandidate(String sourceKey, UUID generationId) {
        authority.requireReviewer();
        if (!OfficialInformationClaimSelector.supports(sourceKey))
            throw new SourceReviewException(HttpStatus.NOT_FOUND, "SOURCE_REVIEW_NOT_FOUND");
        indexing.buildPreparedMixed(generationId);
    }

    public void activateMixedCandidate(String sourceKey, UUID generationId) {
        authority.requireReviewer();
        if (!OfficialInformationClaimSelector.supports(sourceKey))
            throw new SourceReviewException(HttpStatus.NOT_FOUND, "SOURCE_REVIEW_NOT_FOUND");
        active.activateIsolatedMixed(generationId);
    }

    public void cancelMixedCandidate(String sourceKey, UUID generationId) {
        authority.requireReviewer();
        if (!OfficialInformationClaimSelector.supports(sourceKey))
            throw new SourceReviewException(HttpStatus.NOT_FOUND, "SOURCE_REVIEW_NOT_FOUND");
        indexingPersistence.cancelMixedAttempt(generationId);
    }

    public UUID build(String sourceKey, UUID documentId) {
        authority.requireReviewer();
        if (!OfficialInformationClaimSelector.supports(sourceKey)) {
            throw new SourceReviewException(HttpStatus.NOT_FOUND, "SOURCE_REVIEW_NOT_FOUND");
        }
        SourceDocument requested = documents.findWithSourceRegistryById(documentId).orElseThrow(() ->
                new SourceReviewException(HttpStatus.NOT_FOUND, "SOURCE_REVIEW_DOCUMENT_MISSING"));
        if (requested.getStatus() != SourceDocumentStatus.ACTIVE
                || !sourceKey.equals(requested.getSourceRegistry().getSourceKey())) {
            throw new SourceReviewException(HttpStatus.CONFLICT, "SOURCE_REVIEW_VERSION_CHANGED");
        }
        RetrievalGeneration current = active.current().orElseThrow(() ->
                new SourceReviewException(HttpStatus.CONFLICT, "SOURCE_REVIEW_READY_BASE_MISSING"));
        if (current.getStatus() != RetrievalGenerationStatus.READY
                || current.getChunkConfigVersion() == null) {
            throw new SourceReviewException(HttpStatus.CONFLICT, "SOURCE_REVIEW_READY_BASE_MISSING");
        }
        var currentReady = corpus.readyChunks(current.getId());
        if (currentReady.size() != current.getReadyChunkCount()) {
            throw new SourceReviewException(HttpStatus.CONFLICT, "SOURCE_REVIEW_READY_BASE_STALE");
        }
        Set<UUID> ids = currentReady.stream().map(RetrievalCorpusSnapshotService.EligibleChunk::sourceChunkId)
                .collect(Collectors.toCollection(HashSet::new));
        List<SourceChunk> approved = new ArrayList<>();
        for (UUID id : ids) approved.add(chunks.findWithDocumentById(id).orElseThrow());
        // Only exact, human-approved claim chunks are added; a registration or PENDING row is insufficient.
        for (String key : OfficialInformationClaimSelector.sourceKeys()) {
            SourceDocument document = registries.findBySourceKeyAndEnabledTrue(key)
                    .flatMap(registry -> documents.findBySourceRegistry_IdAndStatus(
                            registry.getId(), SourceDocumentStatus.ACTIVE)).orElse(null);
            if (document == null) continue;
            for (SourceChunk chunk : chunks.findAllBySourceDocument_IdOrderBySequenceAsc(document.getId())) {
                if (chunk.getReviewStatus() != SourceChunkReviewStatus.APPROVED) continue;
                validateApproved(key, document, chunk, current.getChunkConfigVersion());
                if (ids.add(chunk.getId())) approved.add(chunk);
            }
        }
        if (approved.stream().noneMatch(chunk -> documentId.equals(chunk.getSourceDocument().getId())
                && OfficialInformationClaimSelector.label(sourceKey).equals(chunk.getParentSection()))) {
            throw new SourceReviewException(HttpStatus.CONFLICT, "SOURCE_REVIEW_INCOMPLETE");
        }
        approved.sort(Comparator.comparing(chunk -> chunk.getId().toString()));
        String snapshot = SourceHashing.sha256(approved.stream().map(chunk -> String.join("|",
                chunk.getId().toString(), chunk.getBodySha256(),
                chunk.getSourceDocument().getId().toString(),
                chunk.getSourceDocument().getContentSha256())).collect(Collectors.joining("\n")));
        String sourceKeys = approved.stream().map(chunk -> chunk.getSourceDocument().getSourceRegistry().getSourceKey())
                .distinct().sorted().map(key -> "\"" + key + "\"").collect(Collectors.joining(","));
        String chunkIds = approved.stream().map(chunk -> "\"" + chunk.getId() + "\"")
                .collect(Collectors.joining(","));
        String metadata = "{\"corpus\":\"REVIEWED-INFORMATION\",\"sourceKeys\":[" + sourceKeys
                + "],\"chunkIds\":[" + chunkIds + "]}";
        RetrievalGeneration next = generations.getOrCreate(new RetrievalGenerationData.Definition(
                "information-reviewed-" + snapshot.substring(0, 16),
                current.getRepresentationConfigVersion(), current.getModelIdentifier(),
                current.getModelRevision(), current.getTokenizerIdentifier(),
                current.getTokenizerRevision(), current.getEncodingConfigJson(),
                current.getIndexConfigJson(), metadata, current.getChunkConfigVersion(), snapshot));
        if (next.getId().equals(current.getId())) return current.getId();
        if (next.getStatus() == RetrievalGenerationStatus.READY) {
            active.activate(next.getId());
        } else {
            indexing.buildAndActivate(next.getId());
        }
        return next.getId();
    }

    private void validateApproved(String key, SourceDocument document, SourceChunk chunk,
            String configVersion) {
        String role = OfficialInformationClaimSelector.role(key, chunk.getSequence());
        if (!OfficialInformationClaimSelector.officialUrl(key).equals(document.getCanonicalUrl())
                || !SourceHashing.sha256(document.getOriginalContent()).equals(document.getRawSha256())
                || !configVersion.equals(chunk.getChunkConfigVersion())
                || OfficialInformationClaimSelector.select(document, configVersion,
                        chunk.getChunkConfiguration().getConfigJson()).stream().noneMatch(chunk::hasSameDefinition)) {
            throw new SourceReviewException(HttpStatus.CONFLICT, "SOURCE_REVIEW_SCOPE_CHANGED");
        }
        Long count = jdbc.queryForObject("""
                SELECT count(*) FROM source_applicability_review r
                JOIN source_applicability_chunk c ON c.review_id = r.id
                WHERE r.source_document_id = ? AND r.reviewed_raw_sha256 = ?
                  AND r.review_target_kind = 'TOPIC' AND r.financial_topic = ?
                  AND r.scope_kind = 'COMMON'
                  AND r.review_status = 'APPROVED' AND r.revoked_at IS NULL
                  AND r.conditions_reviewed AND r.exceptions_reviewed
                  AND c.source_chunk_id = ? AND c.evidence_role = ?
                  AND EXISTS (SELECT 1 FROM source_review_decision_audit a
                      WHERE a.review_id = r.id AND a.source_document_id = r.source_document_id
                        AND a.decision = 'APPROVED'
                        AND a.reviewed_raw_sha256 = r.reviewed_raw_sha256
                        AND a.reviewer_account_id::text = r.reviewed_by)
                """, Long.class, document.getId(), document.getRawSha256(),
                OfficialInformationClaimSelector.topic(key).name(), chunk.getId(), role);
        if (count == null || count != 1) {
            throw new SourceReviewException(HttpStatus.CONFLICT, "SOURCE_REVIEW_INCOMPLETE");
        }
        List<UUID> reviewIds = jdbc.queryForList("""
                SELECT r.id FROM source_applicability_review r
                JOIN source_applicability_chunk c ON c.review_id=r.id
                WHERE r.source_document_id=? AND r.reviewed_raw_sha256=?
                  AND r.review_status='APPROVED' AND r.revoked_at IS NULL
                  AND c.source_chunk_id=? AND c.evidence_role=?
                """, UUID.class, document.getId(), document.getRawSha256(), chunk.getId(), role);
        if (reviewIds.size() != 1 || !searchWindow.allows(reviewIds.getFirst())) {
            throw new SourceReviewException(HttpStatus.CONFLICT, "SOURCE_REVIEW_TEMPORAL_HOLD");
        }
        List<Boolean> temporal = jdbc.query("""
                SELECT temporal_policy, applicability_start_date, applicability_end_date,
                       temporal_verified_on, temporal_valid_until
                FROM source_applicability_review r
                JOIN source_applicability_chunk c ON c.review_id = r.id
                WHERE r.source_document_id = ? AND r.reviewed_raw_sha256 = ?
                  AND r.review_status = 'APPROVED' AND r.revoked_at IS NULL
                  AND c.source_chunk_id = ? AND c.evidence_role = ?
                """, (rs, ignored) -> {
                    TemporalEvidencePolicy policy = TemporalEvidencePolicy.valueOf(rs.getString(1));
                    LocalDate start = rs.getObject(2, LocalDate.class);
                    LocalDate end = rs.getObject(3, LocalDate.class);
                    LocalDate checked = rs.getObject(4, LocalDate.class);
                    LocalDate until = rs.getObject(5, LocalDate.class);
                    LocalDate today = LocalDate.now(ZoneId.of("Asia/Seoul"));
                    return policy.usable(today, false, start, end, checked, until)
                            || (start != null && policy.usable(start, true, start, end, checked, until));
                }, document.getId(), document.getRawSha256(), chunk.getId(), role);
        if (temporal.size() != 1 || !temporal.getFirst()) {
            throw new SourceReviewException(HttpStatus.CONFLICT, "SOURCE_REVIEW_TEMPORAL_HOLD");
        }
        if ("fsc-silsong24-guide".equals(key) && "SUPPORT".equals(role)) {
            List<SourceChunk> linked = chunks.findAllBySourceDocument_IdOrderBySequenceAsc(document.getId());
            SourceChunk condition = linked.stream().filter(value -> value.getSequence() == 1)
                    .findFirst().orElseThrow(() -> new SourceReviewException(HttpStatus.CONFLICT,
                            "SOURCE_REVIEW_INCOMPLETE"));
            if (condition.getReviewStatus() != SourceChunkReviewStatus.APPROVED) {
                throw new SourceReviewException(HttpStatus.CONFLICT, "SOURCE_REVIEW_INCOMPLETE");
            }
            Long linkedCount = jdbc.queryForObject("""
                    SELECT count(*) FROM source_applicability_review r
                    JOIN source_applicability_chunk s ON s.review_id = r.id
                    JOIN source_applicability_chunk c ON c.review_id = r.id
                    WHERE r.source_document_id = ? AND r.reviewed_raw_sha256 = ?
                      AND r.review_status = 'APPROVED' AND r.revoked_at IS NULL
                      AND s.source_chunk_id = ? AND s.evidence_role = 'SUPPORT'
                      AND c.source_chunk_id = ? AND c.evidence_role = 'CONDITION'
                    """, Long.class, document.getId(), document.getRawSha256(),
                    chunk.getId(), condition.getId());
            if (linkedCount == null || linkedCount != 1) {
                throw new SourceReviewException(HttpStatus.CONFLICT, "SOURCE_REVIEW_INCOMPLETE");
            }
        }
    }
}
