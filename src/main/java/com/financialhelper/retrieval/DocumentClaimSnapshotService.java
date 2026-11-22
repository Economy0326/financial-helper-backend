package com.financialhelper.retrieval;

import com.financialhelper.account.AccountAuthenticationException;
import com.financialhelper.account.AccountSessionService;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.financialhelper.common.error.ApiException;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Stores immutable references, never a copy of evidence text. */
@Service
public class DocumentClaimSnapshotService {
    private final JdbcTemplate jdbc;
    private final AccountSessionService accounts;
    private final RetrievalCorpusSnapshotService corpus;

    public DocumentClaimSnapshotService(JdbcTemplate jdbc, AccountSessionService accounts, RetrievalCorpusSnapshotService corpus) {
        this.jdbc = jdbc; this.accounts = accounts; this.corpus = corpus;
    }

    /** Anonymous results are reconstructed under the same current eligibility checks, without storing a snapshot. */
    @Transactional
    public Response preview(OfficialSearchResponse result, String question) {
        if (result.status() != RetrievalStatus.FOUND || result.candidates().isEmpty())
            return new Response(null, result.status().name(), List.of());
        UUID generation = lockActive();
        if (result.candidates().stream().anyMatch(c -> !generation.equals(c.retrievalGenerationId())))
            return new Response(null, "STALE", List.of());
        lockDocuments(result.candidates().stream().map(OfficialEvidenceCandidate::sourceDocumentId).distinct().sorted().toList());
        var ready = readyById(generation, result.candidates().stream().map(OfficialEvidenceCandidate::sourceChunkId).collect(java.util.stream.Collectors.toSet()));
        List<Evidence> evidence = new ArrayList<>();
        for (var candidate : result.candidates()) {
            var chunk = ready.get(candidate.sourceChunkId());
            if (chunk == null || !chunk.documentClaim() || !chunk.sourceDocumentId().equals(candidate.sourceDocumentId())
                    || chunk.documentVersion() != candidate.documentVersion() || !chunk.body().equals(candidate.body()))
                return new Response(null, "STALE", List.of());
            var rows = jdbc.query("""
                    SELECT c.candidate_id,d.normalized_content FROM source_document_claim c
                    JOIN source_document d ON d.id=c.source_document_id
                    WHERE c.source_chunk_id=? AND c.source_document_id=? AND c.document_version=d.document_version
                    AND c.raw_sha256=d.raw_sha256 AND c.content_sha256=d.content_sha256
                    """, (rs,n) -> new String[]{rs.getString(1), rs.getString(2)}, candidate.sourceChunkId(), candidate.sourceDocumentId());
            if (rows.size() != 1 || rows.getFirst()[1] == null) return new Response(null, "STALE", List.of());
            String id = rows.getFirst()[0], text = rows.getFirst()[1];
            var related = jdbc.query("""
                    SELECT role,start_offset,end_offset FROM source_document_claim_relation
                    WHERE candidate_id=? AND role IN ('CONDITION','EXCEPTION') ORDER BY start_offset,role
                    """, (rs,n) -> {
                int start=rs.getInt(2), end=rs.getInt(3);
                if (start < 0 || end <= start || end > text.length()) throw new IllegalStateException("relation unavailable");
                return new Related(rs.getString(1),start,end,text.substring(start,end));
            }, id);
            evidence.add(new Evidence(id, candidate.sourceDocumentId(), candidate.documentVersion(), chunk.body(), chunk.organizationName(),
                    chunk.title(), chunk.canonicalUrl(), chunk.locator(), chunk.publishedAt(), "NOT_VERIFIED", related));
        }
        return new Response(null, "READY_CANDIDATES", List.copyOf(evidence), missingPurposes(question, evidence));
    }

    /** Clarification excerpts use the same current-generation and publication checks as public evidence. */
    @Transactional
    public Response clarification(OfficialSearchResponse result, String question) {
        var found = new OfficialSearchResponse(result.searchId(), RetrievalStatus.FOUND, result.degraded(),
                result.missingFacts(), result.coverageGaps(), result.candidates());
        Response checked = preview(found, question);
        if (!"READY_CANDIDATES".equals(checked.status())) return new Response(null, checked.status(), List.of());
        var titleDocuments = checked.evidence().stream().filter(e -> e.title() != null).collect(java.util.stream.Collectors.groupingBy(Evidence::title,
                java.util.stream.Collectors.mapping(Evidence::documentId, java.util.stream.Collectors.toSet())));
        var options = checked.evidence().stream().filter(e -> e.excerpt() != null && !e.excerpt().isBlank())
                .filter(e -> !DocumentClaimRelevance.availablePurposes(e.excerpt()).isEmpty()
                        || !DocumentClaimRelevance.purposesCoveredByEvidence(question, e.excerpt()).isEmpty())
                .filter(e -> e.title() != null && titleDocuments.get(e.title()).size() == 1)
                .map(e -> new ClarificationOption(e.title(), e.title(), null, null))
                .distinct().limit(5).toList();
        return new Response(null, options.isEmpty() ? "NO_MATCH" : "NEEDS_CLARIFICATION", List.of(), List.of(), options);
    }

    @Transactional
    public Response clarificationPurposes(OfficialSearchResponse result, String question, String title) {
        var found = new OfficialSearchResponse(result.searchId(), RetrievalStatus.FOUND, result.degraded(),
                result.missingFacts(), result.coverageGaps(), result.candidates());
        Response checked = preview(found, question);
        if (!"READY_CANDIDATES".equals(checked.status())) return new Response(null, checked.status(), List.of());
        var options = checked.evidence().stream().flatMap(e -> DocumentClaimRelevance.availablePurposes(e.excerpt()).stream())
                .distinct().map(purpose -> new ClarificationOption(purpose, title, purpose, null)).toList();
        return new Response(null, options.isEmpty() ? "NO_MATCH" : "NEEDS_CLARIFICATION", List.of(), List.of(), options);
    }

    @Transactional
    public List<String> topics() {
        UUID generation = lockActiveOrNull();
        if (generation == null) return List.of();
        var current = corpus.readyChunks(generation);
        lockDocuments(current.stream().filter(RetrievalCorpusSnapshotService.EligibleChunk::documentClaim)
                .map(RetrievalCorpusSnapshotService.EligibleChunk::sourceDocumentId).distinct().sorted().toList());
        return corpus.readyChunks(generation).stream().filter(RetrievalCorpusSnapshotService.EligibleChunk::documentClaim)
                .map(RetrievalCorpusSnapshotService.EligibleChunk::title)
                .filter(title -> title != null && !title.isBlank()).distinct().limit(4).toList();
    }

    /** Resolve a reviewer-selected claim through the same active-generation checks as public results. */
    @Transactional
    public List<Evidence> currentClaimEvidence(String claimId) {
        UUID generation = lockActiveOrNull();
        if (generation == null) return List.of();
        var ids = jdbc.query("SELECT source_chunk_id FROM source_document_claim WHERE candidate_id=?",
                (rs,n) -> rs.getObject(1, UUID.class), claimId);
        if (ids.size() != 1) return List.of();
        var chunks = corpus.readyChunks(generation, java.util.Set.of(ids.getFirst()));
        if (chunks.size() != 1 || !chunks.getFirst().documentClaim()) return List.of();
        var c = chunks.getFirst();
        var candidate = new OfficialEvidenceCandidate(UUID.randomUUID(), c.sourceChunkId(), c.sourceDocumentId(), generation,
                0, null, null, 0, c.organizationName(), c.title(), c.documentVersion(), c.parentSection(),
                c.representationMetadataJson(), c.articleReference(), c.pageReference(), c.locator(), c.canonicalUrl(),
                c.body(), c.publishedAt(), "NOT_VERIFIED");
        var response = preview(new OfficialSearchResponse(UUID.randomUUID(), RetrievalStatus.FOUND, false, List.of(), List.of(), List.of(candidate)), "");
        return "READY_CANDIDATES".equals(response.status()) ? response.evidence() : List.of();
    }

    @Transactional
    public Response save(OfficialSearchResponse result, String question) {
        UUID account = accountId();
        if (result.status() != RetrievalStatus.FOUND || result.candidates().isEmpty())
            return new Response(null, result.status().name(), List.of());
        UUID generation = lockActive();
        var candidates = result.candidates();
        if (candidates.stream().anyMatch(c -> !generation.equals(c.retrievalGenerationId())))
            throw new IllegalStateException("generation changed before snapshot capture");
        lockDocuments(candidates.stream().map(OfficialEvidenceCandidate::sourceDocumentId).distinct().sorted().toList());
        var ready = readyById(generation, candidates.stream().map(OfficialEvidenceCandidate::sourceChunkId).collect(java.util.stream.Collectors.toSet()));
        UUID snapshotId = UUID.randomUUID();
        jdbc.update("INSERT INTO document_claim_snapshot(id,account_id,retrieval_generation_id,question) VALUES (?,?,?,?)", snapshotId, account, generation, questionDigest(snapshotId, question));
        int position = 0;
        for (var candidate : candidates) {
            var chunk = ready.get(candidate.sourceChunkId());
            if (chunk == null || !chunk.documentClaim() || !chunk.sourceDocumentId().equals(candidate.sourceDocumentId())
                    || chunk.documentVersion() != candidate.documentVersion() || !chunk.body().equals(candidate.body()))
                throw new IllegalStateException("claim changed before snapshot capture");
            var rows = jdbc.query("""
                    SELECT candidate_id,review_id,source_document_id,document_version,raw_sha256,content_sha256
                    FROM source_document_claim WHERE source_chunk_id=?
                    """, (rs,n) -> new Identity(rs.getString(1), rs.getObject(2, UUID.class), rs.getObject(3, UUID.class),
                    rs.getInt(4), rs.getString(5), rs.getString(6)), candidate.sourceChunkId());
            if (rows.size() != 1) throw new IllegalStateException("claim identity unavailable");
            var identity = rows.getFirst();
            if (!identity.documentId().equals(chunk.sourceDocumentId()) || identity.version() != chunk.documentVersion())
                throw new IllegalStateException("claim version changed");
            jdbc.update("""
                    INSERT INTO document_claim_snapshot_item(snapshot_id,candidate_id,review_id,source_chunk_id,source_document_id,
                        document_version,raw_sha256,content_sha256,position) VALUES (?,?,?,?,?,?,?,?,?)
                    """, snapshotId, identity.candidateId(), identity.reviewId(), candidate.sourceChunkId(), identity.documentId(),
                    identity.version(), identity.rawHash(), identity.contentHash(), position++);
        }
        var saved = read(snapshotId, account, generation);
        return new Response(saved.snapshotId(), saved.status(), saved.evidence(), missingPurposes(question, saved.evidence()), saved.options());
    }

    @Transactional
    public Response get(UUID snapshotId) {
        UUID account = accountId();
        var generations = jdbc.query("SELECT retrieval_generation_id FROM document_claim_snapshot WHERE id=? AND account_id=?",
                (rs,n) -> rs.getObject(1, UUID.class), snapshotId, account);
        if (generations.size() != 1) throw new SnapshotNotFoundException();
        UUID active = lockActiveOrNull();
        if (!generations.getFirst().equals(active)) return stale(snapshotId);
        return read(snapshotId, account, active);
    }

    @Transactional
    public Report createReport(UUID snapshotId, String question) {
        UUID account = accountId();
        Response evidence = get(snapshotId);
        if (!"READY_CANDIDATES".equals(evidence.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "EVIDENCE_SNAPSHOT_STALE", "근거를 다시 확인해야 합니다.") {};
        }
        String originalQuestion = jdbc.queryForObject("SELECT question FROM document_claim_snapshot WHERE id=? AND account_id=?", String.class, snapshotId, account);
        if (!matchesQuestion(snapshotId, originalQuestion, question)) {
            throw new ApiException(HttpStatus.CONFLICT, "SNAPSHOT_QUESTION_MISMATCH", "검색 질문을 다시 확인해야 합니다.") {};
        }
        if (originalQuestion.startsWith("sha256:")) {
            jdbc.update("UPDATE document_claim_snapshot SET question=? WHERE id=? AND account_id=?", question, snapshotId, account);
        }
        UUID reportId = UUID.randomUUID();
        jdbc.update("INSERT INTO document_claim_report(id,account_id,snapshot_id) VALUES (?,?,?)", reportId, account, snapshotId);
        return report(reportId);
    }

    @Transactional
    public Report report(UUID reportId) {
        UUID account = accountId();
        var rows = jdbc.query("SELECT r.snapshot_id,s.question,r.created_at FROM document_claim_report r JOIN document_claim_snapshot s ON s.id=r.snapshot_id WHERE r.id=? AND r.account_id=? AND s.account_id=r.account_id",
                (rs,n) -> new ReportRow(rs.getObject(1,UUID.class), rs.getString(2), rs.getObject(3,OffsetDateTime.class)), reportId, account);
        if (rows.size() != 1) throw new SnapshotNotFoundException();
        var row = rows.getFirst();
        var generations = jdbc.query("SELECT retrieval_generation_id FROM document_claim_snapshot WHERE id=? AND account_id=?",
                (rs,n) -> rs.getObject(1, UUID.class), row.snapshotId(), account);
        UUID active = lockActiveOrNull();
        Response snapshot = generations.size() == 1 && generations.getFirst().equals(active)
                ? read(row.snapshotId(), account, active, true) : stale(row.snapshotId());
        if ("STALE".equals(snapshot.status()))
            return new Report(reportId, row.snapshotId(), "STALE", row.question(), row.createdAt(), null, List.of());
        String excerpts = snapshot.evidence().stream().map(Evidence::excerpt).collect(java.util.stream.Collectors.joining("\n"));
        return new Report(reportId, row.snapshotId(), "PARTIAL".equals(snapshot.status()) ? "PARTIAL" : "READY", row.question(), row.createdAt(),
                "공식 문서에 적힌 내용입니다. 현재 적용 여부는 확인되지 않았습니다.\n" + excerpts, snapshot.evidence());
    }

    private Response read(UUID snapshotId, UUID account, UUID generation) {
        return read(snapshotId, account, generation, false);
    }

    private Response read(UUID snapshotId, UUID account, UUID generation, boolean partial) {
        var items = jdbc.query("""
                SELECT i.candidate_id,i.review_id,i.source_chunk_id,i.source_document_id,i.document_version,i.raw_sha256,i.content_sha256
                FROM document_claim_snapshot_item i JOIN document_claim_snapshot s ON s.id=i.snapshot_id
                WHERE s.id=? AND s.account_id=? ORDER BY i.position
                """, (rs,n) -> new Stored(rs.getString(1),rs.getObject(2,UUID.class),rs.getObject(3,UUID.class),
                rs.getObject(4,UUID.class),rs.getInt(5),rs.getString(6),rs.getString(7)), snapshotId, account);
        if (items.isEmpty()) return stale(snapshotId);
        lockDocuments(items.stream().map(Stored::documentId).distinct().sorted().toList());
        var ready = readyById(generation, items.stream().map(Stored::chunkId).collect(java.util.stream.Collectors.toSet()));
        List<Evidence> result = new ArrayList<>();
        for (var item : items) {
            var chunk = ready.get(item.chunkId());
            if (chunk == null || !chunk.documentClaim() || !item.documentId().equals(chunk.sourceDocumentId())
                    || item.version() != chunk.documentVersion()) {
                if (partial) continue;
                return stale(snapshotId);
            }
            Long matches = jdbc.queryForObject("""
                    SELECT count(*) FROM source_document_claim c JOIN source_document d ON d.id=c.source_document_id
                    WHERE c.candidate_id=? AND c.review_id=? AND c.source_chunk_id=? AND c.source_document_id=?
                      AND c.document_version=? AND c.raw_sha256=? AND c.content_sha256=?
                      AND d.document_version=c.document_version AND d.raw_sha256=c.raw_sha256 AND d.content_sha256=c.content_sha256
                    """, Long.class, item.candidateId(),item.reviewId(),item.chunkId(),item.documentId(),item.version(),item.rawHash(),item.contentHash());
            if (matches == null || matches != 1) {
                if (partial) continue;
                return stale(snapshotId);
            }
            String text = jdbc.queryForObject("SELECT normalized_content FROM source_document WHERE id=?", String.class, item.documentId());
            List<Related> related;
            try { related = jdbc.query("""
                    SELECT role,start_offset,end_offset FROM source_document_claim_relation
                    WHERE candidate_id=? AND role IN ('CONDITION','EXCEPTION') ORDER BY start_offset,role
                    """, (rs,n) -> {
                int start=rs.getInt(2), end=rs.getInt(3);
                if (text == null || start < 0 || end <= start || end > text.length()) throw new IllegalStateException("relation unavailable");
                return new Related(rs.getString(1),start,end,text.substring(start,end));
            }, item.candidateId()); } catch (IllegalStateException invalidRelation) {
                if (partial) continue;
                return stale(snapshotId);
            }
            result.add(new Evidence(item.candidateId(), item.documentId(), item.version(), chunk.body(), chunk.organizationName(),
                    chunk.title(), chunk.canonicalUrl(), chunk.locator(), chunk.publishedAt(), "NOT_VERIFIED", related));
        }
        if (result.isEmpty()) return stale(snapshotId);
        String question = jdbc.queryForObject("SELECT question FROM document_claim_snapshot WHERE id=? AND account_id=?", String.class, snapshotId, account);
        if (question != null && question.startsWith("sha256:")) question = null;
        return new Response(snapshotId, result.size() == items.size() ? "READY_CANDIDATES" : "PARTIAL", List.copyOf(result), missingPurposes(question, result));
    }

    private static List<String> missingPurposes(String question, List<Evidence> evidence) {
        var covered = evidence.stream().flatMap(item -> DocumentClaimRelevance.purposesCoveredByEvidence(question, item.excerpt()).stream())
                .collect(java.util.stream.Collectors.toSet());
        return DocumentClaimRelevance.requestedPurposes(question).stream().filter(p -> !covered.contains(p)).toList();
    }

    static String questionDigest(UUID snapshotId, String question) {
        return "sha256:" + com.financialhelper.source.SourceHashing.sha256(snapshotId + ":" + question);
    }

    static boolean matchesQuestion(UUID snapshotId, String stored, String question) {
        if (stored == null || question == null) return false;
        return stored.startsWith("sha256:") ? stored.equals(questionDigest(snapshotId, question)) : stored.equals(question);
    }

    @Transactional(readOnly = true)
    public List<ReportSummary> reports() {
        return jdbc.query("""
                SELECT r.id,s.question,r.created_at FROM document_claim_report r
                JOIN document_claim_snapshot s ON s.id=r.snapshot_id AND s.account_id=r.account_id
                WHERE r.account_id=? ORDER BY r.created_at DESC,r.id DESC
                """, (rs,n) -> new ReportSummary(rs.getObject(1, UUID.class), rs.getString(2),
                rs.getObject(3, OffsetDateTime.class)), accountId());
    }

    @Transactional
    public void deleteReport(UUID reportId) {
        if (jdbc.update("DELETE FROM document_claim_report WHERE id=? AND account_id=?", reportId, accountId()) != 1)
            throw new SnapshotNotFoundException();
    }

    private Map<UUID, RetrievalCorpusSnapshotService.EligibleChunk> readyById(UUID generation, java.util.Set<UUID> chunkIds) {
        Map<UUID, RetrievalCorpusSnapshotService.EligibleChunk> result = new HashMap<>();
        corpus.readyChunks(generation, chunkIds).forEach(chunk -> result.put(chunk.sourceChunkId(), chunk));
        return result;
    }
    private void lockDocuments(List<UUID> ids) {
        for (UUID id : ids) jdbc.queryForObject("SELECT id FROM source_document WHERE id=? FOR UPDATE", UUID.class, id);
    }
    private UUID lockActive() {
        UUID id = lockActiveOrNull();
        if (id == null) throw new IllegalStateException("active generation unavailable");
        return id;
    }
    private UUID lockActiveOrNull() {
        var ids = jdbc.query("""
                SELECT a.retrieval_generation_id FROM active_retrieval_generation a
                JOIN retrieval_generation g ON g.id=a.retrieval_generation_id
                WHERE a.singleton_key=TRUE AND g.status='READY' FOR SHARE OF a,g
                """,
                (rs,n) -> rs.getObject(1,UUID.class));
        return ids.size() == 1 ? ids.getFirst() : null;
    }
    private UUID accountId() { return accounts.currentAccountId().orElseThrow(AccountAuthenticationException::required); }
    private static Response stale(UUID id) { return new Response(id, "STALE", List.of()); }
    private static final class SnapshotNotFoundException extends ApiException {
        SnapshotNotFoundException() { super(HttpStatus.NOT_FOUND, "EVIDENCE_SNAPSHOT_NOT_FOUND", "근거를 찾을 수 없습니다."); }
    }
    private record Identity(String candidateId, UUID reviewId, UUID documentId, int version, String rawHash, String contentHash) {}
    private record Stored(String candidateId, UUID reviewId, UUID chunkId, UUID documentId, int version, String rawHash, String contentHash) {}
    private record ReportRow(UUID snapshotId, String question, OffsetDateTime createdAt) {}
    public record Related(String role, int start, int end, String text) {}
    public record Evidence(String claimId, UUID documentId, int documentVersion, String excerpt, String organizationName, String title, String officialUrl,
                           String locator, LocalDate publishedAt, String currentApplicability, List<Related> related) {}
    public record ClarificationOption(String label, String title, String purpose, String excerpt) {
        public ClarificationOption(String title, String excerpt) { this(title, title, null, excerpt); }
    }
    public record Response(UUID snapshotId, String status, List<Evidence> evidence, List<String> missingPurposes, List<ClarificationOption> options) {
        public Response(UUID snapshotId, String status, List<Evidence> evidence, List<String> missingPurposes) { this(snapshotId, status, evidence, missingPurposes, List.of()); }
        public Response(UUID snapshotId, String status, List<Evidence> evidence) { this(snapshotId, status, evidence, List.of()); }
    }
    public record Report(UUID reportId, UUID snapshotId, String status, String question, OffsetDateTime createdAt,
                         String explanation, List<Evidence> evidence) {}
    public record ReportSummary(UUID reportId, String question, OffsetDateTime createdAt) {}
}
