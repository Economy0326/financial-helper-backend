package com.financialhelper.retrieval;

import com.financialhelper.source.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/** Reviewer-selected document claims, with no network calls in the preparation transaction. */
@Service
public class DocumentClaimBatchReadyService {
    private final SourceReviewerService authority;
    private final DocumentClaimReviewerService claims;
    private final RetrievalCorpusSnapshotService corpus;
    private final RetrievalGenerationPersistenceService generations;
    private final RetrievalGenerationIndexingPersistenceService indexingPersistence;
    private final RetrievalGenerationIndexingService indexing;
    private final RetrievalGenerationRepository repository;
    private final ActiveRetrievalGenerationPersistenceService active;
    private final KureRuntimeProperties kure;
    private final SourceChunkingProperties chunking;
    private final JdbcTemplate jdbc;
    private final KureRuntimeHttpClient runtime;

    public DocumentClaimBatchReadyService(SourceReviewerService authority, DocumentClaimReviewerService claims,
            RetrievalCorpusSnapshotService corpus, RetrievalGenerationPersistenceService generations,
            RetrievalGenerationIndexingPersistenceService indexingPersistence, RetrievalGenerationIndexingService indexing,
            RetrievalGenerationRepository repository, ActiveRetrievalGenerationPersistenceService active,
            KureRuntimeProperties kure, SourceChunkingProperties chunking, JdbcTemplate jdbc, KureRuntimeHttpClient runtime) {
        this.authority = authority; this.claims = claims; this.corpus = corpus; this.generations = generations;
        this.indexingPersistence = indexingPersistence; this.indexing = indexing; this.repository = repository;
        this.active = active; this.kure = kure; this.chunking = chunking; this.jdbc = jdbc;
        this.runtime = runtime;
    }

    /** Read-only preflight; runtime IO occurs outside a database transaction. */
    public boolean unchanged(List<UUID> selected, java.util.Map<UUID, String> versions) {
        return unchangedGeneration(selected, versions) != null;
    }

    public UUID unchangedGeneration(List<UUID> selected, java.util.Map<UUID, String> versions) {
        authority.requireReviewer();
        Reuse first = reusable(selected, versions);
        if (first == null) return null;
        try {
            var state = runtime.readiness(first.id());
            var manifest = tools.jackson.databind.json.JsonMapper.builder().build().readTree(state.indexMetadataJson());
            if (!first.id().equals(state.generationId()) || !state.ready() || state.documentCount() != first.count()
                    || !first.hash().equals(manifest.path("corpusSnapshotSha256").asString())) return null;
        } catch (RuntimeException unavailable) { return null; }
        return first.equals(reusable(selected, versions)) ? first.id() : null;
    }

    private Reuse reusable(List<UUID> selected, java.util.Map<UUID, String> versions) {
        if (selected == null || selected.isEmpty() || selected.size() > 100 || selected.stream().anyMatch(java.util.Objects::isNull)
                || new HashSet<>(selected).size() != selected.size()
                || versions != null && (!selected.containsAll(versions.keySet()) || versions.values().stream().anyMatch(v -> !"LEGACY".equals(v) && !"LATEST".equals(v)))) return null;
        var pointers = jdbc.query("SELECT retrieval_generation_id,activation_revision FROM active_retrieval_generation WHERE singleton_key=TRUE",
                (rs, n) -> new Base(rs.getObject(1, UUID.class), rs.getLong(2)));
        if (pointers.size() != 1) return null;
        Base pointer = pointers.getFirst();
        var current = repository.findById(pointer.id()).orElse(null);
        if (current == null || current.getStatus() != RetrievalGenerationStatus.READY || current.getReadyAt() == null
                || !"kure-v2-plaid-v1".equals(current.getRepresentationConfigVersion())
                || !kure.modelIdentifier().equals(current.getModelIdentifier()) || !kure.modelRevision().equals(current.getModelRevision())
                || !kure.tokenizerIdentifier().equals(current.getTokenizerIdentifier()) || !kure.tokenizerRevision().equals(current.getTokenizerRevision())
                || !chunking.configVersion().equals(current.getChunkConfigVersion())
                || !"{\"queryLength\":64,\"documentMaxTokens\":8192,\"doQueryExpansion\":true,\"queryPrefix\":\"\",\"documentPrefix\":\"\"}".equals(current.getEncodingConfigJson())
                || !"{\"backend\":\"PLAID\",\"nbits\":4,\"seed\":42,\"useFast\":true,\"useTriton\":false}".equals(current.getIndexConfigJson())
                || !RetrievalGenerationIndexingPersistenceService.searchContext(current.getMetadataJson()) || !corpus.matchesStoredCorpus(current)) return null;
        var metadata = tools.jackson.databind.json.JsonMapper.builder().build().readTree(current.getMetadataJson());
        if (!"REVIEWED-DOCUMENT-BATCH".equals(metadata.path("corpus").asString()) || !metadata.path("exactGenericMembership").asBoolean(false)) return null;
        var ready = corpus.readyChunks(current.getId());
        if (ready.size() != current.getReadyChunkCount() || ready.stream().anyMatch(c -> !c.documentClaim())) return null;
        for (var chunk : ready) {
            Boolean changed = jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM source_document_usage_review WHERE source_document_id=? AND decided_at>?)", Boolean.class, chunk.sourceDocumentId(), current.getReadyAt());
            if (Boolean.TRUE.equals(changed)) return null;
        }
        Set<UUID> activeIds = ready.stream().map(RetrievalCorpusSnapshotService.EligibleChunk::sourceChunkId).collect(Collectors.toSet());
        Set<UUID> desired = ready.stream().filter(c -> !selected.contains(c.sourceDocumentId())).map(RetrievalCorpusSnapshotService.EligibleChunk::sourceChunkId).collect(Collectors.toSet());
        for (UUID document : selected) {
            var available = jdbc.queryForList("SELECT DISTINCT extractor_version FROM source_document_claim WHERE source_document_id=?", String.class, document);
            boolean legacy = available.stream().anyMatch(v -> v.equals("generic-claim-v1") || v.equals("generic-claim-v2"));
            boolean latest = available.contains("generic-claim-v3");
            String version = versions == null ? null : versions.get(document);
            if (version == null && legacy && latest) return null;
            if (version == null) version = latest ? "LATEST" : "LEGACY";
            var candidates = jdbc.queryForList("SELECT source_chunk_id FROM source_document_claim WHERE source_document_id=? AND extractor_version "
                    + ("LATEST".equals(version) ? "= 'generic-claim-v3'" : "IN ('generic-claim-v1','generic-claim-v2')"), UUID.class, document);
            int before = desired.size();
            for (UUID id : candidates) if (claims.genericChunkMayBeDisplayed(id)) desired.add(id);
            if (desired.size() == before) return null;
        }
        if (!activeIds.equals(desired)) return null;
        return new Reuse(current.getId(), pointer.revision(), current.getCorpusSnapshotSha256(), ready.size());
    }

    private record Reuse(UUID id, long revision, String hash, int count) {}

    @Transactional
    public UUID prepare(List<UUID> selected) {
        return prepare(selected, null);
    }

    @Transactional
    public UUID prepare(List<UUID> selected, java.util.Map<UUID, String> versions) {
        authority.requireReviewer();
        if (selected == null || selected.isEmpty() || selected.size() > 100 || selected.stream().anyMatch(java.util.Objects::isNull)
                || new HashSet<>(selected).size() != selected.size())
            throw new SourceReviewException(org.springframework.http.HttpStatus.BAD_REQUEST, "READY_DOCUMENT_SELECTION_INVALID");
        if (versions != null && (!selected.containsAll(versions.keySet()) || versions.values().stream().anyMatch(v -> !"LEGACY".equals(v) && !"LATEST".equals(v))))
            throw new SourceReviewException(org.springframework.http.HttpStatus.BAD_REQUEST, "READY_DOCUMENT_SELECTION_INVALID");
        var pointers = jdbc.query("SELECT retrieval_generation_id,activation_revision FROM active_retrieval_generation WHERE singleton_key=TRUE",
                (rs, n) -> new Base(rs.getObject(1, UUID.class), rs.getLong(2)));
        Base base = pointers.isEmpty() ? new Base(null, 0) : pointers.getFirst();
        RetrievalGeneration current = base.id() == null ? null : repository.findById(base.id()).orElseThrow();
        if (current != null && current.getStatus() != RetrievalGenerationStatus.READY)
            throw new IllegalStateException("Active generation is not ready");
        Set<UUID> ids = new HashSet<>();
        Set<UUID> documentIds = new HashSet<>(selected);
        if (current != null) for (var chunk : corpus.readyChunks(current.getId())) {
            if (!chunk.documentClaim() || !selected.contains(chunk.sourceDocumentId())) ids.add(chunk.sourceChunkId());
            if (chunk.documentClaim()) documentIds.add(chunk.sourceDocumentId());
        }
        for (UUID document : selected) {
            var available = jdbc.queryForList("SELECT DISTINCT extractor_version FROM source_document_claim WHERE source_document_id=?", String.class, document);
            boolean legacy = available.stream().anyMatch(v -> v.equals("generic-claim-v1") || v.equals("generic-claim-v2"));
            boolean latest = available.contains("generic-claim-v3");
            String version = versions == null ? null : versions.get(document);
            if (version == null && legacy && latest) {
                boolean hasEligible = jdbc.queryForList("SELECT source_chunk_id FROM source_document_claim WHERE source_document_id=?", UUID.class, document)
                        .stream().anyMatch(claims::genericChunkMayBeDisplayed);
                if (hasEligible) throw new SourceReviewException(org.springframework.http.HttpStatus.CONFLICT, "READY_CLAIM_VERSION_REQUIRED");
            }
            if (version == null) version = latest ? "LATEST" : "LEGACY";
            var candidateIds = jdbc.queryForList("SELECT source_chunk_id FROM source_document_claim WHERE source_document_id=? AND extractor_version "
                    + ("LATEST".equals(version) ? "= 'generic-claim-v3'" : "IN ('generic-claim-v1','generic-claim-v2')"), UUID.class, document);
            boolean found = false;
            for (UUID chunk : candidateIds) if (claims.genericChunkMayBeDisplayed(chunk)) {
                ids.add(chunk); found = true;
            }
            if (!found) {
                Boolean included = current != null && Boolean.TRUE.equals(jdbc.queryForObject(
                        "SELECT EXISTS (SELECT 1 FROM source_chunk_indexing i JOIN source_document_claim c ON c.source_chunk_id=i.source_chunk_id WHERE i.retrieval_generation_id=? AND i.indexing_status='READY' AND c.source_document_id=?)",
                        Boolean.class, current.getId(), document));
                if (!included) throw new SourceReviewException(org.springframework.http.HttpStatus.CONFLICT, "READY_NO_ELIGIBLE_CLAIMS_OR_RIGHTS");
            }
        }
        if (ids.isEmpty()) throw new SourceReviewException(org.springframework.http.HttpStatus.CONFLICT, "READY_EMPTY_CORPUS_UNSUPPORTED");
        String chunkIds = ids.stream().sorted().map(id -> "\"" + id + "\"").collect(Collectors.joining(","));
        String docs = documentIds.stream().sorted().map(id -> "\"" + id + "\"").collect(Collectors.joining(","));
        String metadata = "{\"corpus\":\"REVIEWED-DOCUMENT-BATCH\",\"includeDocumentClaims\":true,\"exactGenericMembership\":true,\"searchContextV1\":true,"
                + "\"baseGenerationId\":\"" + (base.id() == null ? "" : base.id()) + "\",\"baseActivationRevision\":\"" + base.revision()
                + "\",\"chunkIds\":[" + chunkIds + "],\"documentClaimDocumentIds\":[" + docs + "]}";
        var generation = generations.getOrCreate(new RetrievalGenerationData.Definition("document-batch-" + UUID.randomUUID(),
                current == null ? "kure-v2-plaid-v1" : current.getRepresentationConfigVersion(),
                current == null ? kure.modelIdentifier() : current.getModelIdentifier(), current == null ? kure.modelRevision() : current.getModelRevision(),
                current == null ? kure.tokenizerIdentifier() : current.getTokenizerIdentifier(), current == null ? kure.tokenizerRevision() : current.getTokenizerRevision(),
                current == null ? "{\"queryLength\":64,\"documentMaxTokens\":8192,\"doQueryExpansion\":true,\"queryPrefix\":\"\",\"documentPrefix\":\"\"}" : current.getEncodingConfigJson(),
                current == null ? "{\"backend\":\"PLAID\",\"nbits\":4,\"seed\":42,\"useFast\":true,\"useTriton\":false}" : current.getIndexConfigJson(),
                metadata, current == null ? chunking.configVersion() : current.getChunkConfigVersion(), null));
        indexingPersistence.start(generation.getId());
        return generation.getId();
    }

    public void build(UUID id) {
        authority.requireReviewer(); requireBatch(id);
        if (active.currentId().filter(id::equals).isPresent()) { requireUnchangedActive(id); return; }
        try { indexing.buildPreparedMixed(id); }
        catch (KureRuntimeException unavailable) { throw new SourceReviewException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE, "READY_INDEX_BUILD_FAILED"); }
    }
    public void activate(UUID id) {
        authority.requireReviewer(); requireBatch(id);
        if (active.currentId().filter(id::equals).isPresent()) { requireUnchangedActive(id); return; }
        try { active.activateReviewedMixed(id); }
        catch (IllegalStateException changed) {
            throw new SourceReviewException(org.springframework.http.HttpStatus.CONFLICT,
                    "Active generation changed".equals(changed.getMessage()) ? "READY_ACTIVE_GENERATION_CHANGED" : "READY_EVIDENCE_CHANGED");
        }
    }

    private void requireUnchangedActive(UUID id) {
        var ready = corpus.readyChunks(id);
        var versions = new java.util.HashMap<UUID, String>();
        for (var chunk : ready) {
            var values = jdbc.queryForList("SELECT extractor_version FROM source_document_claim WHERE source_chunk_id=?", String.class, chunk.sourceChunkId());
            if (values.size() != 1) throw new SourceReviewException(org.springframework.http.HttpStatus.CONFLICT, "READY_EVIDENCE_CHANGED");
            String version = "generic-claim-v3".equals(values.getFirst()) ? "LATEST" : "LEGACY";
            String previous = versions.putIfAbsent(chunk.sourceDocumentId(), version);
            if (previous != null && !previous.equals(version)) throw new SourceReviewException(org.springframework.http.HttpStatus.CONFLICT, "READY_EVIDENCE_CHANGED");
        }
        if (versions.isEmpty() || !id.equals(unchangedGeneration(List.copyOf(versions.keySet()), versions)))
            throw new SourceReviewException(org.springframework.http.HttpStatus.CONFLICT, "READY_EVIDENCE_CHANGED");
    }
    private void requireBatch(UUID id) {
        var generation = repository.findById(id).orElseThrow();
        var metadata = tools.jackson.databind.json.JsonMapper.builder().build().readTree(generation.getMetadataJson());
        if (!"REVIEWED-DOCUMENT-BATCH".equals(metadata.path("corpus").asString())
                || !metadata.path("exactGenericMembership").asBoolean(false))
            throw new IllegalStateException("Not a reviewer document batch");
    }
    private record Base(UUID id, long revision) {}
}
