package com.financialhelper.source;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import java.util.UUID;
import java.net.URI;
import java.util.Set;
import java.util.TreeSet;

/** Reviewer-granted document claim operations; never changes READY or performs network calls. */
@Service
public class DocumentClaimReviewerService {
    private static final String VERSION = "generic-claim-v1";
    private static final String ADDITIONAL_VERSION = "generic-claim-v2";
    private static final String STRUCTURED_VERSION = "generic-claim-v3";
    private final SourceDocumentRepository documents;
    private final SourceChunkPersistenceService chunkPersistence;
    private final SourceChunkRepository chunks;
    private final JdbcTemplate jdbc;
    private final GenericClaimEvidenceBundle extractor = new GenericClaimEvidenceBundle();
    private static final Set<String> USAGE_SCOPES = Set.of("STORE", "EXTRACT", "REVIEW", "SEARCH", "EXCERPT", "EXTERNAL_AI");

    public DocumentClaimReviewerService(SourceDocumentRepository documents,
            SourceChunkPersistenceService chunkPersistence, SourceChunkRepository chunks, JdbcTemplate jdbc) {
        this.documents = documents; this.chunkPersistence = chunkPersistence; this.chunks = chunks; this.jdbc = jdbc;
    }

    /** Reviewer-only inventory. Generation membership does not assert current search eligibility. */
    @Transactional(readOnly = true)
    public List<DocumentSummary> documents() {
        SourceReviewerService.requireReviewer(jdbc);
        return jdbc.query("""
            SELECT d.id,d.organization_name,d.official_domain,d.title,d.canonical_url,d.published_at,d.document_version,
                   d.raw_sha256,d.original_content,d.content_sha256,d.normalized_content,
                   CASE WHEN (SELECT count(*) FROM source_document_usage_review u
                    WHERE u.source_document_id=d.id AND u.document_version=d.document_version AND u.raw_sha256=d.raw_sha256
                      AND NOT EXISTS (SELECT 1 FROM source_document_usage_review n WHERE n.previous_review_id=u.id)) > 1 THEN 'HOLD'
                   ELSE (SELECT u.decision FROM source_document_usage_review u
                    WHERE u.source_document_id=d.id AND u.document_version=d.document_version AND u.raw_sha256=d.raw_sha256
                      AND NOT EXISTS (SELECT 1 FROM source_document_usage_review n WHERE n.previous_review_id=u.id)
                    ORDER BY u.decided_at DESC LIMIT 1) END,
                   (SELECT count(*) FROM source_document_claim c JOIN source_applicability_review r ON r.id=c.review_id
                    WHERE c.source_document_id=d.id AND r.review_status='APPROVED'
                      AND (c.extractor_version='generic-claim-v3' OR NOT EXISTS (SELECT 1 FROM source_document_claim newer WHERE newer.source_document_id=d.id AND newer.extractor_version='generic-claim-v3'))),
                   (SELECT count(*) FROM source_document_claim c JOIN source_applicability_review r ON r.id=c.review_id
                    WHERE c.source_document_id=d.id AND r.review_status='PENDING'
                      AND (c.extractor_version='generic-claim-v3' OR NOT EXISTS (SELECT 1 FROM source_document_claim newer WHERE newer.source_document_id=d.id AND newer.extractor_version='generic-claim-v3'))),
                   EXISTS (SELECT 1 FROM source_document_claim c JOIN source_chunk_indexing i ON i.source_chunk_id=c.source_chunk_id
                      JOIN active_retrieval_generation a ON a.retrieval_generation_id=i.retrieval_generation_id
                      WHERE c.source_document_id=d.id AND i.indexing_status='READY'),
                   EXISTS (SELECT 1 FROM source_text_registration t WHERE t.source_document_id=d.id)
            FROM source_document d WHERE d.status='ACTIVE' ORDER BY d.title,d.id
            """, (rs,n) -> {
            byte[] original = rs.getBytes(9); String content = rs.getString(11);
            boolean hashMatches = original != null && content != null && rs.getString(8).equals(SourceHashing.sha256(original))
                    && rs.getString(10).equals(SourceHashing.sha256(content));
            return new DocumentSummary(rs.getObject(1,UUID.class),rs.getString(2),rs.getString(3),rs.getString(4),rs.getString(5),
                    rs.getObject(6,java.time.LocalDate.class),rs.getInt(7),rs.getString(8),hashMatches,
                    rs.getString(12),rs.getLong(13),rs.getLong(14),rs.getBoolean(15),rs.getBoolean(16));
        });
    }

    @Transactional(readOnly = true)
    public List<ClaimAudit> audit(UUID documentId, String expectedRaw) {
        SourceReviewerService.requireReviewer(jdbc);
        SourceDocument doc = documents.findWithSourceRegistryById(documentId).orElseThrow(() -> failure("SOURCE_REVIEW_DOCUMENT_MISSING"));
        checkDocument(doc, expectedRaw);
        return jdbc.query("""
            SELECT c.candidate_id,a.reviewer_account_id,a.decision,a.decision_reason,a.decided_at
            FROM source_review_decision_audit a JOIN source_document_claim c ON c.review_id=a.review_id
            WHERE a.source_document_id=? AND a.reviewed_raw_sha256=? ORDER BY a.decided_at DESC,a.id DESC
            """, (rs,n) -> new ClaimAudit(rs.getString(1),rs.getObject(2,UUID.class),rs.getString(3),rs.getString(4),
                rs.getObject(5,java.time.OffsetDateTime.class)), documentId, expectedRaw);
    }

    @Transactional(readOnly = true)
    public List<GenerationSummary> generations(UUID documentId, String expectedRaw) {
        SourceReviewerService.requireReviewer(jdbc);
        SourceDocument doc = documents.findWithSourceRegistryById(documentId).orElseThrow(() -> failure("SOURCE_REVIEW_DOCUMENT_MISSING"));
        checkDocument(doc, expectedRaw);
        return jdbc.query("""
            SELECT DISTINCT g.id,g.status,(a.retrieval_generation_id IS NOT NULL),b.status
            FROM source_document_claim c JOIN source_chunk_indexing i ON i.source_chunk_id=c.source_chunk_id
            JOIN retrieval_generation g ON g.id=i.retrieval_generation_id
            LEFT JOIN active_retrieval_generation a ON a.retrieval_generation_id=g.id
            LEFT JOIN mixed_generation_build_attempt b ON b.generation_id=g.id
            WHERE c.source_document_id=? ORDER BY g.id
            """, (rs,n) -> new GenerationSummary(rs.getObject(1,UUID.class),rs.getString(2),rs.getBoolean(3),rs.getString(4)),documentId);
    }

    /** Append a human rights decision; document lock serializes it with claim decisions. */
    @Transactional
    public UsageReview decideUsage(UUID documentId, String expectedRaw, int expectedVersion, String basisUrl,
            List<String> scopes, boolean privacyReviewed, boolean thirdPartyReviewed, String decision, String reason,
            UUID expectedPreviousReviewId) {
        UUID reviewer = SourceReviewerService.requireReviewer(jdbc);
        SourceDocument doc = locked(documentId, expectedRaw);
        if (doc.getDocumentVersion() != expectedVersion || decision == null
                || !Set.of("HOLD", "ALLOWED", "REJECTED").contains(decision)
                || (reason != null && reason.length() > 1000) || scopes == null
                || scopes.stream().anyMatch(scope -> scope == null || !USAGE_SCOPES.contains(scope)))
            throw failure("SOURCE_USAGE_DECISION_INVALID");
        try {
            URI uri = URI.create(basisUrl);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null
                    || uri.getFragment() != null || uri.getPort() != -1 && uri.getPort() != 443)
                throw new IllegalArgumentException();
        } catch (RuntimeException invalid) { throw failure("SOURCE_USAGE_BASIS_INVALID"); }
        var normalizedScopes = new TreeSet<>(scopes);
        if (decision.equals("ALLOWED") && (!privacyReviewed || !thirdPartyReviewed
                || !normalizedScopes.containsAll(Set.of("STORE", "EXTRACT", "REVIEW")))) throw failure("SOURCE_USAGE_SCOPE_INVALID");
        String scopeText = String.join(",", normalizedScopes);
        List<UsageReview> previous = usageHistory(doc);
        String effectiveReason = reason == null || reason.isBlank()
                ? "이용조건 결정 " + switch (decision) { case "ALLOWED" -> "허용"; case "HOLD" -> "보류"; default -> "불가"; }
                    + ", 범위 " + (previous.isEmpty() ? "없음" : scopeNames(previous.getFirst().allowedScope())) + " → " + scopeNames(scopeText)
                : reason;
        if (!previous.isEmpty()) {
            UsageReview last = previous.getFirst();
            var priorScopes = new java.util.HashSet<>(java.util.Arrays.asList(last.allowedScope().split(",")));
            if (priorScopes.remove("STORE_EXTRACT_REVIEW")) priorScopes.addAll(Set.of("STORE", "EXTRACT", "REVIEW"));
            if (last.decision().equals(decision) && last.basisUrl().equals(basisUrl)
                    && last.allowedScope().equals(scopeText) && last.privacyReviewed() == privacyReviewed
                    && last.thirdPartyReviewed() == thirdPartyReviewed && last.reason().equals(effectiveReason)
                    && java.util.Objects.equals(last.previousReviewId(), expectedPreviousReviewId)) return last;
            if (!java.util.Objects.equals(last.id(), expectedPreviousReviewId)) throw failure("SOURCE_USAGE_STALE_DECISION");
            boolean valid = switch (last.decision()) {
                case "HOLD" -> decision.equals("ALLOWED") || decision.equals("REJECTED");
                case "ALLOWED" -> decision.equals("HOLD") || decision.equals("REJECTED")
                        || decision.equals("ALLOWED") && last.basisUrl().equals(basisUrl)
                           && normalizedScopes.containsAll(priorScopes);
                case "REJECTED" -> decision.equals("HOLD");
                default -> false;
            };
            if (!valid) throw failure("SOURCE_USAGE_TRANSITION_INVALID");
        } else if (expectedPreviousReviewId != null) {
            throw failure("SOURCE_USAGE_STALE_DECISION");
        }
        UUID id = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO source_document_usage_review(id,source_document_id,document_version,raw_sha256,basis_url,
                allowed_scope,privacy_reviewed,third_party_reviewed,decision,reviewer_account_id,reason,previous_review_id)
            VALUES (?,?,?,?,?,?,?,?,?,?,?,?)
            """, id, documentId, expectedVersion, expectedRaw, basisUrl, scopeText, privacyReviewed,
                thirdPartyReviewed, decision, reviewer, effectiveReason, expectedPreviousReviewId);
        return usageHistory(doc).stream().filter(item -> item.id().equals(id)).findFirst().orElseThrow();
    }

    @Transactional(readOnly = true)
    public List<UsageReview> usageReviews(UUID documentId, String expectedRaw) {
        SourceReviewerService.requireReviewer(jdbc);
        SourceDocument doc = documents.findWithSourceRegistryById(documentId).orElseThrow(() -> failure("SOURCE_REVIEW_DOCUMENT_MISSING"));
        checkDocument(doc, expectedRaw);
        return usageHistory(doc);
    }

    private List<UsageReview> usageHistory(SourceDocument doc) {
        Long leaves = jdbc.queryForObject("""
            SELECT count(*) FROM source_document_usage_review u
            WHERE u.source_document_id=? AND u.document_version=? AND u.raw_sha256=?
              AND NOT EXISTS (SELECT 1 FROM source_document_usage_review n WHERE n.previous_review_id=u.id)
            """, Long.class, doc.getId(), doc.getDocumentVersion(), doc.getRawSha256());
        if (leaves == null || leaves > 1) throw failure("SOURCE_USAGE_HISTORY_AMBIGUOUS");
        return jdbc.query("""
            SELECT u.id,u.basis_url,u.allowed_scope,u.privacy_reviewed,u.third_party_reviewed,u.decision,u.reviewer_account_id,u.decided_at,u.reason,u.previous_review_id
            FROM source_document_usage_review u WHERE u.source_document_id=? AND u.document_version=? AND u.raw_sha256=?
            ORDER BY EXISTS (SELECT 1 FROM source_document_usage_review n WHERE n.previous_review_id=u.id), u.decided_at DESC,u.id DESC
            """, (rs,n) -> new UsageReview((UUID)rs.getObject(1),doc.getId(),doc.getDocumentVersion(),doc.getRawSha256(),doc.getCanonicalUrl(),rs.getString(2),rs.getString(3),
                rs.getBoolean(4),rs.getBoolean(5),rs.getString(6),(UUID)rs.getObject(7),
                rs.getObject(8,java.time.OffsetDateTime.class),rs.getString(9),(UUID)rs.getObject(10)),
                doc.getId(),doc.getDocumentVersion(),doc.getRawSha256());
    }

    @Transactional
    public List<Claim> prepare(UUID documentId, String expectedRaw) {
        SourceReviewerService.requireReviewer(jdbc);
        SourceDocument document = locked(documentId, expectedRaw);
        requireUsage(document);
        List<String> existingVersions = jdbc.queryForList("SELECT DISTINCT extractor_version FROM source_document_claim WHERE source_document_id=?", String.class, documentId);
        var bundles = new java.util.ArrayList<GenericClaimEvidenceBundle.Bundle>();
        if (existingVersions.isEmpty() || existingVersions.contains(STRUCTURED_VERSION)) {
            bundles.addAll(extractor.prepare(document, STRUCTURED_VERSION));
        } else {
            bundles.addAll(extractor.prepare(document, VERSION));
            bundles.addAll(extractor.prepare(document, ADDITIONAL_VERSION));
        }
        var prepared = savePrepared(document, expectedRaw, bundles);
        if (existingVersions.isEmpty() && document.getDocumentVersion() > 1) {
            comparePreviousVersion(document, prepared);
            return claims(documentId, expectedRaw);
        }
        return prepared;
    }

    private void comparePreviousVersion(SourceDocument current, List<Claim> candidates) {
        var versions = documents.findAllBySourceRegistry_IdOrderByDocumentVersionAsc(current.getSourceRegistry().getId());
        var previous = versions.stream().filter(d -> d.getDocumentVersion() == current.getDocumentVersion() - 1).findFirst().orElse(null);
        if (previous == null) return;
        var oldClaims = readClaims(previous);
        var oldUsage = usageHistory(previous);
        var newUsage = usageHistory(current);
        boolean sameTitle = java.util.Objects.equals(previous.getTitle(), current.getTitle());
        boolean rightsMatch = !oldUsage.isEmpty() && !newUsage.isEmpty()
                && sameTitle
                && previous.getOriginalContent() != null && previous.getNormalizedContent() != null
                && previous.getRawSha256().equals(SourceHashing.sha256(previous.getOriginalContent()))
                && previous.getContentSha256().equals(SourceHashing.sha256(previous.getNormalizedContent()))
                && "ALLOWED".equals(oldUsage.getFirst().decision()) && "ALLOWED".equals(newUsage.getFirst().decision())
                && oldUsage.getFirst().privacyReviewed() && oldUsage.getFirst().thirdPartyReviewed()
                && newUsage.getFirst().privacyReviewed() && newUsage.getFirst().thirdPartyReviewed()
                && oldUsage.getFirst().basisUrl().equals(newUsage.getFirst().basisUrl())
                && java.util.Objects.equals(oldUsage.getFirst().reason(), newUsage.getFirst().reason())
                && oldUsage.getFirst().allowedScope().equals(newUsage.getFirst().allowedScope());
        for (var old : oldClaims) {
            var matches = candidates.stream().filter(c -> ClaimVersionMatcher.same(old, c)
                    && ClaimVersionMatcher.sameSection(previous.getNormalizedContent(), old.start(), current.getNormalizedContent(), c.start())).toList();
            var exact = sameTitle && ClaimVersionMatcher.hasUnambiguousSection(previous.getTitle(), previous.getNormalizedContent(), old.start())
                    && matches.size() == 1 && oldClaims.stream().filter(c -> ClaimVersionMatcher.same(c, matches.getFirst())
                    && ClaimVersionMatcher.sameSection(previous.getNormalizedContent(), c.start(), current.getNormalizedContent(), matches.getFirst().start())).count() == 1
                    ? matches.getFirst() : null;
            String outcome = exact == null ? "NOT_IN_LATEST_EXTRACTION" : "REVIEW_REQUIRED";
            UUID priorAudit = null, inheritedAudit = null;
            if (exact != null && rightsMatch && !old.hold() && !exact.hold()
                    && ("APPROVED".equals(old.status()) || "REJECTED".equals(old.status()))) {
                var audits = jdbc.query("""
                    SELECT a.id FROM source_review_decision_audit a JOIN source_applicability_review r ON r.id=a.review_id
                    WHERE r.id=? AND a.decision=r.review_status AND a.reviewed_raw_sha256=?
                      AND a.reviewer_account_id::text=r.reviewed_by
                      AND r.revoked_at IS NULL
                      AND EXISTS (SELECT 1 FROM source_chunk s WHERE s.id=? AND s.review_status=r.review_status)
                      AND (r.review_status<>'APPROVED' OR (r.conditions_reviewed AND r.exceptions_reviewed))
                      AND (r.review_status<>'APPROVED' OR r.current_claim_approval_id=a.id)
                      AND NOT EXISTS (SELECT 1 FROM source_review_decision_audit later WHERE later.review_id=a.review_id AND later.id<>a.id AND later.decided_at>=a.decided_at)
                    """, (rs,n) -> rs.getObject(1, UUID.class), old.reviewId(), previous.getRawSha256(), old.chunkId());
                if (audits.size() == 1) {
                    boolean verified;
                    try { verified = !validateBundle(previous, old, true).hold() && !validateBundle(current, exact).hold(); }
                    catch (RuntimeException invalid) { verified = false; }
                    if (verified) {
                        priorAudit = audits.getFirst();
                        decide(current.getId(), exact.candidateId(), current.getRawSha256(),
                                "APPROVED".equals(old.status()) ? SourceReviewerService.Decision.APPROVE : SourceReviewerService.Decision.REJECT,
                                "INHERITED_FROM_PREVIOUS_VERSION");
                        inheritedAudit = jdbc.queryForObject("SELECT id FROM source_review_decision_audit WHERE review_id=? ORDER BY decided_at DESC,id DESC LIMIT 1", UUID.class, exact.reviewId());
                        outcome = "APPROVED".equals(old.status()) ? "INHERITED_APPROVED" : "INHERITED_REJECTED";
                    }
                }
            }
            jdbc.update("INSERT INTO source_claim_version_comparison(current_document_id,previous_candidate_id,current_candidate_id,outcome,previous_audit_id,inherited_audit_id) VALUES (?,?,?,?,?,?) ON CONFLICT DO NOTHING",
                    current.getId(), old.candidateId(), exact == null ? null : exact.candidateId(), outcome, priorAudit, inheritedAudit);
        }
    }

    @Transactional(readOnly = true)
    public VersionComparison versionComparison(UUID documentId, String expectedRaw) {
        SourceReviewerService.requireReviewer(jdbc);
        SourceDocument current = documents.findWithSourceRegistryById(documentId).orElseThrow(() -> failure("SOURCE_REVIEW_DOCUMENT_MISSING"));
        checkDocument(current, expectedRaw);
        requireUsage(current);
        var rows = jdbc.query("SELECT previous_candidate_id,outcome FROM source_claim_version_comparison WHERE current_document_id=? ORDER BY previous_candidate_id",
                (rs,n) -> new String[]{rs.getString(1),rs.getString(2)}, documentId);
        int approved = (int) rows.stream().filter(r -> "INHERITED_APPROVED".equals(r[1])).count();
        int rejected = (int) rows.stream().filter(r -> "INHERITED_REJECTED".equals(r[1])).count();
        var excluded = rows.stream().filter(r -> "NOT_IN_LATEST_EXTRACTION".equals(r[1])).map(r -> r[0]).collect(java.util.stream.Collectors.toSet());
        var previous = documents.findAllBySourceRegistry_IdOrderByDocumentVersionAsc(current.getSourceRegistry().getId()).stream()
                .filter(d -> d.getDocumentVersion() == current.getDocumentVersion() - 1).findFirst().orElse(null);
        if (previous == null || excluded.isEmpty()) return new VersionComparison(approved, rejected, excluded.size(), previous == null && !excluded.isEmpty(), List.of());
        if (previous.getOriginalContent() == null || previous.getNormalizedContent() == null
                || !previous.getRawSha256().equals(SourceHashing.sha256(previous.getOriginalContent()))
                || !previous.getContentSha256().equals(SourceHashing.sha256(previous.getNormalizedContent())))
            return new VersionComparison(approved, rejected, excluded.size(), true, List.of());
        var usage = usageHistory(previous);
        if (usage.isEmpty() || !"ALLOWED".equals(usage.getFirst().decision())
                || !(List.of(usage.getFirst().allowedScope().split(",")).contains("REVIEW") || List.of(usage.getFirst().allowedScope().split(",")).contains("STORE_EXTRACT_REVIEW")))
            return new VersionComparison(approved, rejected, excluded.size(), true, List.of());
        List<ExcludedClaim> items;
        try {
            items = readClaims(previous).stream().filter(c -> excluded.contains(c.candidateId()))
                    .map(c -> new ExcludedClaim(c.body(), c.status(), "최신 판본에서 동일한 후보가 확인되지 않았습니다. 원문에서 삭제됐다는 뜻은 아닙니다."))
                    .toList();
        } catch (RuntimeException invalid) {
            return new VersionComparison(approved, rejected, excluded.size(), true, List.of());
        }
        return new VersionComparison(approved, rejected, excluded.size(), false, items);
    }

    public record ExcludedClaim(String body, String status, String reason) {}
    public record VersionComparison(int inheritedApproved, int inheritedRejected, int excludedCount, boolean excludedUnavailable, List<ExcludedClaim> excluded) {}

    /** Explicitly re-extract without altering existing candidates or decisions. */
    @Transactional
    public List<Claim> prepareLatest(UUID documentId, String expectedRaw) {
        SourceReviewerService.requireReviewer(jdbc);
        SourceDocument document = locked(documentId, expectedRaw);
        requireUsage(document);
        var bundles = extractor.prepare(document, STRUCTURED_VERSION);
        if (bundles.isEmpty()) throw failure("SOURCE_REVIEW_NO_CANDIDATES");
        return savePrepared(document, expectedRaw, bundles);
    }

    private List<Claim> savePrepared(SourceDocument document, String expectedRaw, List<GenericClaimEvidenceBundle.Bundle> bundles) {
        UUID documentId = document.getId();
        int sequence = 0;
        for (var bundle : bundles) {
            int currentSequence = sequence++;
            Long count = jdbc.queryForObject("SELECT count(*) FROM source_document_claim WHERE candidate_id=?", Long.class, bundle.candidateId());
            if (count != null && count != 0) continue;
            if (ADDITIONAL_VERSION.equals(bundle.extractorVersion())) {
                Long duplicate = jdbc.queryForObject("""
                        SELECT count(*) FROM source_document_claim c JOIN source_chunk s ON s.id=c.source_chunk_id
                        WHERE c.source_document_id=? AND s.source_start_offset=? AND s.source_end_offset=?
                        """, Long.class, documentId, bundle.start(), bundle.end());
                if (duplicate != null && duplicate > 0) continue;
            }
            var definition = new SourceChunkData.Definition(bundle.extractorVersion(), "{}", currentSequence, bundle.body(), null, null,
                    null, "normalized-content:utf16:" + bundle.start() + "-" + bundle.end(), bundle.start(), bundle.end(), "{}");
            SourceChunk chunk = chunkPersistence.saveIfAbsent(document, definition);
            // The following JDBC inserts reference this JPA-managed chunk within the same transaction.
            chunks.flush();
            UUID reviewId = UUID.randomUUID();
            boolean condition = bundle.related().stream().anyMatch(r -> r.relation() == GenericClaimCandidateExtractor.Relation.CONDITION);
            boolean exception = bundle.related().stream().anyMatch(r -> r.relation() == GenericClaimCandidateExtractor.Relation.EXCEPTION);
            jdbc.update("""
                INSERT INTO source_applicability_review(id,source_document_id,reviewed_raw_sha256,scope_kind,scenario,
                    review_target_kind,financial_topic,review_status,condition_required,exception_required,temporal_policy)
                VALUES (?,?,?,'COMMON',NULL,'DOCUMENT_CLAIM',NULL,'PENDING',?,?,'HOLD')
                """, reviewId, documentId, expectedRaw, condition, exception);
            jdbc.update("INSERT INTO source_applicability_chunk(review_id,source_document_id,source_chunk_id,evidence_role) VALUES (?,?,?,'SUPPORT')", reviewId, documentId, chunk.getId());
            if (condition) role(reviewId, documentId, chunk.getId(), "CONDITION");
            if (exception) role(reviewId, documentId, chunk.getId(), "EXCEPTION");
            jdbc.update("""
                INSERT INTO source_document_claim(candidate_id,source_document_id,document_version,raw_sha256,content_sha256,
                    extractor_version,review_id,source_chunk_id,context_start,context_end,hold,hold_reason)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?)
                """, bundle.candidateId(), documentId, document.getDocumentVersion(), expectedRaw,
                    document.getContentSha256(), bundle.extractorVersion(), reviewId, chunk.getId(), bundle.context().start(),
                    bundle.context().end(), bundle.hold(), String.join("; ", bundle.warnings()));
            for (var relation : bundle.related()) jdbc.update("""
                INSERT INTO source_document_claim_relation(candidate_id,role,start_offset,end_offset,ambiguous)
                VALUES (?,?,?,?,?) ON CONFLICT DO NOTHING
                """, bundle.candidateId(), relation.relation().name(), relation.span().start(), relation.span().end(), relation.ambiguous());
        }
        return claims(documentId, expectedRaw);
    }

    @Transactional(readOnly = true)
    public List<Claim> claims(UUID documentId, String expectedRaw) {
        SourceReviewerService.requireReviewer(jdbc);
        SourceDocument doc = documents.findWithSourceRegistryById(documentId).orElseThrow(() -> failure("SOURCE_REVIEW_DOCUMENT_MISSING"));
        checkDocument(doc, expectedRaw); requireUsage(doc);
        return readClaims(doc);
    }

    private List<Claim> readClaims(SourceDocument doc) {
        UUID documentId = doc.getId();
        String text = doc.getNormalizedContent();
        var relationsByCandidate = new java.util.HashMap<String, List<Relation>>();
        jdbc.query("""
            SELECT r.candidate_id,r.role,r.start_offset,r.end_offset,r.ambiguous
            FROM source_document_claim_relation r JOIN source_document_claim c ON c.candidate_id=r.candidate_id
            WHERE c.source_document_id=? ORDER BY r.candidate_id,r.start_offset,r.role
            """, rs -> {
                int start = rs.getInt(3), end = rs.getInt(4);
                if (start < 0 || end > text.length() || start >= end) throw failure("SOURCE_REVIEW_SCOPE_CHANGED");
                relationsByCandidate.computeIfAbsent(rs.getString(1), ignored -> new java.util.ArrayList<>())
                        .add(new Relation(rs.getString(2), start, end, text.substring(start, end), rs.getBoolean(5)));
            }, documentId);
        return jdbc.query("""
            SELECT c.candidate_id,c.review_id,c.source_chunk_id,s.body,s.source_start_offset,s.source_end_offset,
                   c.context_start,c.context_end,c.hold,c.hold_reason,r.review_status,c.document_version,c.content_sha256,c.extractor_version
            FROM source_document_claim c JOIN source_chunk s ON s.id=c.source_chunk_id
            JOIN source_applicability_review r ON r.id=c.review_id WHERE c.source_document_id=? ORDER BY s.sequence
            """, (rs, i) -> {
                if (rs.getInt(12) != doc.getDocumentVersion() || !doc.getContentSha256().equals(rs.getString(13)))
                    throw failure("SOURCE_REVIEW_VERSION_CHANGED");
                int start = rs.getInt(5), end = rs.getInt(6), contextStart = rs.getInt(7), contextEnd = rs.getInt(8);
                if (contextStart < 0 || contextEnd > text.length() || start < contextStart || end > contextEnd
                        || !text.substring(start, end).equals(rs.getString(4))) throw failure("SOURCE_REVIEW_SCOPE_CHANGED");
                String id = rs.getString(1);
                List<Relation> relations = List.copyOf(relationsByCandidate.getOrDefault(id, List.of()));
                if (relations.stream().anyMatch(rel -> rel.start() < contextStart || rel.end() > contextEnd))
                    throw failure("SOURCE_REVIEW_SCOPE_CHANGED");
                return new Claim(id, doc.getId(), doc.getDocumentVersion(), doc.getRawSha256(), doc.getContentSha256(),
                        rs.getString(14), doc.getPublishedAt(), "NOT_VERIFIED", (UUID)rs.getObject(2), (UUID)rs.getObject(3), rs.getString(4), start, end,
                        text.substring(contextStart, contextEnd), contextStart, relations, rs.getBoolean(9), rs.getString(10), rs.getString(11));
            }, documentId);
    }

    @Transactional
    public void decide(UUID documentId, String candidateId, String expectedRaw, SourceReviewerService.Decision decision) {
        decide(documentId, candidateId, expectedRaw, decision, null);
    }

    @Transactional
    public void decide(UUID documentId, String candidateId, String expectedRaw, SourceReviewerService.Decision decision, String reason) {
        UUID reviewer = SourceReviewerService.requireReviewer(jdbc);
        if (decision == null || (reason != null && reason.length() > 1000)) throw failure("SOURCE_REVIEW_DECISION_INVALID");
        SourceDocument doc = locked(documentId, expectedRaw); requireUsage(doc);
        Claim candidate = claims(documentId, expectedRaw).stream().filter(c -> c.candidateId().equals(candidateId))
                .findFirst().orElseThrow(() -> failure("SOURCE_REVIEW_NOT_FOUND"));
        var original = validateBundle(doc, candidate);
        var status = jdbc.queryForObject("SELECT review_status FROM source_applicability_review WHERE id=? FOR UPDATE", String.class, candidate.reviewId());
        String target = decision == SourceReviewerService.Decision.APPROVE ? "APPROVED" : "REJECTED";
        if (target.equals(status)) return;
        if (!"PENDING".equals(status)) throw failure("SOURCE_REVIEW_ALREADY_DECIDED");
        if (decision == SourceReviewerService.Decision.APPROVE && (candidate.hold() || original.hold()))
            throw failure("SOURCE_REVIEW_SCOPE_HOLD");
        jdbc.update("UPDATE source_applicability_review SET review_status=?,reviewed_by=?,reviewed_at=clock_timestamp(),conditions_reviewed=?,exceptions_reviewed=? WHERE id=?",
                target, reviewer.toString(), decision == SourceReviewerService.Decision.APPROVE,
                decision == SourceReviewerService.Decision.APPROVE, candidate.reviewId());
        jdbc.update("UPDATE source_chunk SET review_status=?,reviewed_at=CURRENT_TIMESTAMP,updated_at=CURRENT_TIMESTAMP WHERE id=?", target, candidate.chunkId());
        UUID decisionId = UUID.randomUUID();
        jdbc.update("INSERT INTO source_review_decision_audit(id,review_id,source_document_id,reviewer_account_id,decision,decision_reason,reviewed_raw_sha256) VALUES (?,?,?,?,?,?,?)",
                decisionId, candidate.reviewId(), documentId, reviewer, target,
                reason == null || reason.isBlank() ? "DOCUMENT_CLAIM_REVIEW" : reason.strip(), expectedRaw);
        jdbc.update("UPDATE source_applicability_review SET current_claim_approval_id=? WHERE id=?",
                decision == SourceReviewerService.Decision.APPROVE ? decisionId : null, candidate.reviewId());
    }

    /** Reopen a rejected candidate without making it eligible for search. */
    @Transactional
    public void reconsiderRejected(UUID documentId, String candidateId, String expectedRaw) {
        UUID reviewer = SourceReviewerService.requireReviewer(jdbc);
        locked(documentId, expectedRaw);
        var rows = jdbc.query("SELECT review_id,source_chunk_id FROM source_document_claim WHERE source_document_id=? AND candidate_id=?",
                (rs, n) -> new UUID[]{rs.getObject(1, UUID.class), rs.getObject(2, UUID.class)}, documentId, candidateId);
        if (rows.size() != 1) throw failure("SOURCE_REVIEW_NOT_FOUND");
        UUID reviewId = rows.getFirst()[0], chunkId = rows.getFirst()[1];
        String status = jdbc.queryForObject("SELECT review_status FROM source_applicability_review WHERE id=? FOR UPDATE", String.class, reviewId);
        if (!"REJECTED".equals(status)) throw failure("SOURCE_REVIEW_ALREADY_DECIDED");
        jdbc.update("UPDATE source_applicability_review SET review_status='PENDING',reviewed_by=NULL,reviewed_at=NULL,conditions_reviewed=false,exceptions_reviewed=false,current_claim_approval_id=NULL WHERE id=?", reviewId);
        jdbc.update("UPDATE source_chunk SET review_status='PENDING',reviewed_at=NULL,updated_at=CURRENT_TIMESTAMP WHERE id=?", chunkId);
        jdbc.update("INSERT INTO source_review_decision_audit(id,review_id,source_document_id,reviewer_account_id,decision,decision_reason,reviewed_raw_sha256) VALUES (?,?,?,?,?,?,?)",
                UUID.randomUUID(), reviewId, documentId, reviewer, "REVOKED", "REJECTION_RECONSIDERED", expectedRaw);
    }

    @Transactional
    public void withdrawApproval(UUID documentId, String candidateId, String expectedRaw, String reason) {
        UUID reviewer = SourceReviewerService.requireReviewer(jdbc);
        if (reason != null && reason.length() > 1000) throw failure("SOURCE_REVIEW_DECISION_INVALID");
        locked(documentId, expectedRaw);
        var rows = jdbc.query("SELECT review_id,source_chunk_id FROM source_document_claim WHERE source_document_id=? AND candidate_id=?",
                (rs, n) -> new UUID[]{rs.getObject(1, UUID.class), rs.getObject(2, UUID.class)}, documentId, candidateId);
        if (rows.size() != 1) throw failure("SOURCE_REVIEW_NOT_FOUND");
        UUID reviewId = rows.getFirst()[0], chunkId = rows.getFirst()[1];
        String status = jdbc.queryForObject("SELECT review_status FROM source_applicability_review WHERE id=? FOR UPDATE", String.class, reviewId);
        if (!"APPROVED".equals(status)) throw failure("SOURCE_REVIEW_ALREADY_DECIDED");
        jdbc.update("UPDATE source_applicability_review SET review_status='PENDING',reviewed_by=NULL,reviewed_at=NULL,conditions_reviewed=false,exceptions_reviewed=false,current_claim_approval_id=NULL WHERE id=?", reviewId);
        jdbc.update("UPDATE source_chunk SET review_status='PENDING',reviewed_at=NULL,updated_at=CURRENT_TIMESTAMP WHERE id=?", chunkId);
        String marker = "APPROVAL_WITHDRAWN";
        if (reason != null && !reason.isBlank()) marker += ": " + reason.strip();
        if (marker.length() > 1000) throw failure("SOURCE_REVIEW_DECISION_INVALID");
        jdbc.update("INSERT INTO source_review_decision_audit(id,review_id,source_document_id,reviewer_account_id,decision,decision_reason,reviewed_raw_sha256) VALUES (?,?,?,?,?,?,?)",
                UUID.randomUUID(), reviewId, documentId, reviewer, "REVOKED", marker, expectedRaw);
    }

    /** Display requires the exact approval decision frozen into this generation. */
    @Transactional(readOnly = true)
    public boolean genericChunkMayBeDisplayedForGeneration(UUID chunkId, UUID generationId) {
        if (!genericChunkMayBeDisplayed(chunkId)) return false;
        return generationReviewMatches(chunkId, generationId);
    }

    @Transactional(readOnly = true)
    public boolean generationReviewMatches(UUID chunkId, UUID generationId) {
        Boolean eligible = jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM retrieval_generation_claim_review b
                JOIN source_document_claim c ON c.source_chunk_id=b.source_chunk_id AND c.review_id=b.review_id
                JOIN source_applicability_review r ON r.id=b.review_id
                JOIN source_review_decision_audit a ON a.id=b.approval_audit_id AND a.review_id=r.id AND a.decision='APPROVED'
                WHERE b.retrieval_generation_id=? AND b.source_chunk_id=?
                  AND r.review_status='APPROVED' AND r.current_claim_approval_id=b.approval_audit_id)
                """, Boolean.class, generationId, chunkId);
        return Boolean.TRUE.equals(eligible);
    }

    @Transactional(readOnly = true)
    public Eligibility evaluateEligibility(UUID documentId, String candidateId, String expectedRaw, String scope) {
        if (!Set.of("SEARCH", "EXCERPT", "EXTERNAL_AI").contains(scope)) throw failure("SOURCE_USAGE_SCOPE_INVALID");
        return evaluateEligibilityScopes(documentId, candidateId, expectedRaw, Set.of(scope));
    }

    private Eligibility evaluateEligibilityScopes(UUID documentId, String candidateId, String expectedRaw, Set<String> scopes) {
        SourceDocument doc = documents.findWithSourceRegistryById(documentId).orElseThrow(() -> failure("SOURCE_REVIEW_DOCUMENT_MISSING"));
        checkDocument(doc, expectedRaw);
        Boolean changed = jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM document_change_observation WHERE source_document_id=? AND change_detected)", Boolean.class, documentId);
        if (!Boolean.FALSE.equals(changed)) return new Eligibility(false, "SOURCE_DOCUMENT_CHANGED", "NOT_VERIFIED");
        List<UsageReview> history = usageHistory(doc);
        if (history.isEmpty() || !"ALLOWED".equals(history.getFirst().decision())
                || !Set.of(history.getFirst().allowedScope().split(",")).containsAll(scopes))
            return new Eligibility(false, "SOURCE_USAGE_SCOPE_HOLD", "NOT_VERIFIED");
        requireUsage(doc);
        Claim candidate = readClaims(doc).stream().filter(c -> c.candidateId().equals(candidateId)).findFirst()
                .orElseThrow(() -> failure("SOURCE_REVIEW_NOT_FOUND"));
        var original = validateBundle(doc, candidate);
        if (candidate.hold() || original.hold() || !"APPROVED".equals(candidate.status()))
            return new Eligibility(false, "SOURCE_REVIEW_NOT_APPROVED", "NOT_VERIFIED");
        Long approved = jdbc.queryForObject("""
            SELECT count(*) FROM source_applicability_review r JOIN source_chunk c ON c.id=?
            WHERE r.id=? AND r.review_target_kind='DOCUMENT_CLAIM' AND r.review_status='APPROVED'
              AND r.conditions_reviewed AND r.exceptions_reviewed AND r.revoked_at IS NULL
              AND c.review_status='APPROVED' AND EXISTS (SELECT 1 FROM source_review_decision_audit a
                 WHERE a.review_id=r.id AND a.source_document_id=? AND a.reviewed_raw_sha256=?
                   AND a.decision='APPROVED' AND a.reviewer_account_id::text=r.reviewed_by)
            """, Long.class, candidate.chunkId(),candidate.reviewId(),documentId,expectedRaw);
        return new Eligibility(approved != null && approved == 1,
                approved != null && approved == 1 ? "ELIGIBLE" : "SOURCE_REVIEW_NOT_APPROVED", "NOT_VERIFIED");
    }

    /** Called at corpus read time. Any malformed or stale generic evidence is unavailable. */
    @Transactional(readOnly = true)
    public boolean genericChunkMayBeSearched(UUID chunkId) {
        var rows = jdbc.query("""
            SELECT source_document_id,candidate_id,raw_sha256 FROM source_document_claim WHERE source_chunk_id=?
            """, (rs,n) -> new Object[]{rs.getObject(1, UUID.class),rs.getString(2),rs.getString(3)}, chunkId);
        if (rows.size() != 1) return false;
        var row = rows.getFirst();
        try {
            return evaluateEligibility((UUID)row[0], (String)row[1], (String)row[2], "SEARCH").eligible();
        } catch (RuntimeException invalid) {
            return false;
        }
    }

    /** Uses the persisted claim relation, not the chunk configuration, to classify evidence. */
    @Transactional(readOnly = true)
    public boolean isDocumentClaimChunk(UUID chunkId) {
        Boolean schema = jdbc.queryForObject("SELECT to_regclass('source_document_claim') IS NOT NULL", Boolean.class);
        if (!Boolean.TRUE.equals(schema)) return false;
        Boolean exists = jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM source_document_claim WHERE source_chunk_id=?)", Boolean.class, chunkId);
        if (exists == null) throw failure("SOURCE_REVIEW_SCOPE_CHANGED");
        return exists;
    }

    /** Search and excerpt are distinct rights; both are needed for a returned passage. */
    @Transactional(readOnly = true)
    public boolean genericChunkMayBeDisplayed(UUID chunkId) {
        var rows = jdbc.query("SELECT source_document_id,candidate_id,raw_sha256 FROM source_document_claim WHERE source_chunk_id=?",
                (rs,n) -> new Object[]{rs.getObject(1, UUID.class),rs.getString(2),rs.getString(3)}, chunkId);
        if (rows.size() != 1) return false;
        var row = rows.getFirst();
        try {
            if (!evaluateEligibilityScopes((UUID)row[0], (String)row[1], (String)row[2], Set.of("SEARCH", "EXCERPT")).eligible()) return false;
            var fresh = jdbc.query("""
                SELECT d.status,d.document_version,d.raw_sha256,d.original_content,d.content_sha256,d.normalized_content,
                       c.document_version,c.raw_sha256,c.content_sha256
                FROM source_document_claim c JOIN source_document d ON d.id=c.source_document_id WHERE c.source_chunk_id=?
                """, (rs,n) -> "ACTIVE".equals(rs.getString(1)) && rs.getInt(2) == rs.getInt(7)
                    && rs.getString(3).equals(rs.getString(8)) && rs.getString(5).equals(rs.getString(9))
                    && rs.getString(3).equals(SourceHashing.sha256(rs.getBytes(4)))
                    && rs.getString(5).equals(SourceHashing.sha256(rs.getString(6))), chunkId);
            return fresh.size() == 1 && Boolean.TRUE.equals(fresh.getFirst());
        } catch (RuntimeException invalid) { return false; }
    }

    private GenericClaimEvidenceBundle.Bundle validateBundle(SourceDocument doc, Claim candidate) {
        return validateBundle(doc, candidate, false);
    }
    private GenericClaimEvidenceBundle.Bundle validateBundle(SourceDocument doc, Claim candidate, boolean historical) {
        String candidateId = candidate.candidateId(); UUID documentId = doc.getId();
        // Recompute the complete candidate set to detect changed extraction rules or missing relations.
        if (!Set.of(VERSION, ADDITIONAL_VERSION, STRUCTURED_VERSION).contains(candidate.extractorVersion())) throw failure("SOURCE_REVIEW_SCOPE_CHANGED");
        var original = (historical ? extractor.prepareHistoricalForComparison(doc, candidate.extractorVersion()) : extractor.prepare(doc, candidate.extractorVersion())).stream().filter(b -> b.candidateId().equals(candidateId))
                .findFirst().orElseThrow(() -> failure("SOURCE_REVIEW_SCOPE_CHANGED"));
        boolean sameRelations = candidate.relations().size() == original.related().size()
                && original.related().stream().allMatch(r -> candidate.relations().stream().anyMatch(saved ->
                    saved.role().equals(r.relation().name()) && saved.start() == r.span().start()
                    && saved.end() == r.span().end() && saved.text().equals(r.span().text())
                    && saved.ambiguous() == r.ambiguous()));
        if (!sameRelations || !candidate.body().equals(original.body()) || !candidate.context().equals(original.context().text()))
            throw failure("SOURCE_REVIEW_SCOPE_CHANGED");
        var counts = jdbc.queryForObject("""
                SELECT count(*) FILTER (WHERE evidence_role='SUPPORT'),
                       count(*) FILTER (WHERE evidence_role='CONDITION'),
                       count(*) FILTER (WHERE evidence_role='EXCEPTION')
                FROM source_applicability_chunk
                WHERE review_id=? AND source_document_id=? AND source_chunk_id=?
                """, (rs, n) -> new long[]{rs.getLong(1), rs.getLong(2), rs.getLong(3)},
                candidate.reviewId(), documentId, candidate.chunkId());
        boolean condition = candidate.relations().stream().anyMatch(r -> "CONDITION".equals(r.role()));
        boolean exception = candidate.relations().stream().anyMatch(r -> "EXCEPTION".equals(r.role()));
        if (counts == null || counts[0] != 1 || counts[1] != (condition ? 1 : 0)
                || counts[2] != (exception ? 1 : 0)) throw failure("SOURCE_REVIEW_SCOPE_CHANGED");
        return original;
    }

    private SourceDocument locked(UUID id, String raw) {
        SourceDocument doc = documents.findForUpdateById(id).orElseThrow(() -> failure("SOURCE_REVIEW_DOCUMENT_MISSING"));
        checkDocument(doc, raw); return doc;
    }
    private void checkDocument(SourceDocument doc, String raw) {
        if (doc.getStatus() != SourceDocumentStatus.ACTIVE || raw == null || !raw.equals(doc.getRawSha256())
                || !raw.equals(SourceHashing.sha256(doc.getOriginalContent()))
                || !doc.getContentSha256().equals(SourceHashing.sha256(doc.getNormalizedContent())))
            throw failure("SOURCE_REVIEW_VERSION_CHANGED");
    }
    private void requireUsage(SourceDocument doc) {
        List<UsageReview> history = usageHistory(doc);
        if (history.isEmpty()) throw failure("SOURCE_USAGE_REVIEW_REQUIRED");
        UsageReview current = history.getFirst();
        Set<String> scopes = Set.of(current.allowedScope().split(","));
        if (!"ALLOWED".equals(current.decision()) || !current.privacyReviewed() || !current.thirdPartyReviewed()
                || !(scopes.containsAll(Set.of("STORE", "EXTRACT", "REVIEW")) || scopes.contains("STORE_EXTRACT_REVIEW")))
            throw failure("SOURCE_USAGE_REVIEW_REQUIRED");
    }
    private void role(UUID review, UUID doc, UUID chunk, String role) {
        jdbc.update("INSERT INTO source_applicability_chunk(review_id,source_document_id,source_chunk_id,evidence_role) VALUES (?,?,?,?)", review,doc,chunk,role);
    }
    private static String scopeNames(String text) {
        if (text == null || text.isBlank()) return "없음";
        return java.util.Arrays.stream(text.split(",")).map(scope -> switch (scope) {
            case "STORE" -> "본문 저장"; case "EXTRACT" -> "추출"; case "REVIEW" -> "검토";
            case "SEARCH" -> "검색"; case "EXCERPT" -> "원문 발췌"; case "EXTERNAL_AI" -> "외부 AI 전달";
            case "STORE_EXTRACT_REVIEW" -> "본문 저장, 추출, 검토"; default -> "확인 필요";
        }).collect(java.util.stream.Collectors.joining(", "));
    }
    private SourceReviewException failure(String code) { return new SourceReviewException(HttpStatus.CONFLICT, code); }
    public record Relation(String role, int start, int end, String text, boolean ambiguous) {}
    public record DocumentSummary(UUID documentId, String organization, String platformDomain, String title, String officialUrl,
                                  java.time.LocalDate publishedAt, int documentVersion, String rawSha256, boolean hashMatches,
                                  String usageDecision, long approvedCount, long pendingCount, boolean activeGenerationMembership,
                                  boolean textOnly) {}
    public record ClaimAudit(String candidateId, UUID reviewerId, String decision, String reason, java.time.OffsetDateTime decidedAt) {}
    public record GenerationSummary(UUID generationId, String status, boolean active, String buildStatus) {}
    public record Eligibility(boolean eligible, String reason, String currentApplicability) {}
    public record UsageReview(UUID id, UUID documentId, int documentVersion, String rawSha256, String officialUrl,
                              String basisUrl, String allowedScope, boolean privacyReviewed,
                              boolean thirdPartyReviewed, String decision, UUID reviewerId,
                              java.time.OffsetDateTime decidedAt, String reason, UUID previousReviewId) {}
    public record Claim(String candidateId, UUID documentId, int documentVersion, String rawSha256,
                        String contentSha256, String extractorVersion, java.time.LocalDate publishedAt, String currentApplicability,
                        UUID reviewId, UUID chunkId, String body, int start, int end,
                        String context, int contextStart, List<Relation> relations, boolean hold, String holdReason, String status) {}
}
