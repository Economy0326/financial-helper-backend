package com.financialhelper.source;

import com.financialhelper.consultation.FinancialTopic;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.UUID;

/** Human review of narrow, source-bound INFORMATION claims. No method grants READY. */
@Service
public class OfficialInformationReviewerService {
    private final SourceReviewerService authority;
    private final SourceRegistryRepository registries;
    private final SourceDocumentRepository documents;
    private final SourceChunkRepository chunks;
    private final SourceIngestionService ingestion;
    private final SourceChunkingService chunking;
    private final JdbcTemplate jdbc;

    public OfficialInformationReviewerService(SourceReviewerService authority,
            SourceRegistryRepository registries, SourceDocumentRepository documents,
            SourceChunkRepository chunks, SourceIngestionService ingestion,
            SourceChunkingService chunking, JdbcTemplate jdbc) {
        this.authority = authority;
        this.registries = registries;
        this.documents = documents;
        this.chunks = chunks;
        this.ingestion = ingestion;
        this.chunking = chunking;
        this.jdbc = jdbc;
    }

    public SourceIngestionResult ingest(String sourceKey) {
        authority.requireReviewer();
        registry(sourceKey);
        return ingestion.ingestOne(sourceKey);
    }

    /** Read-only preflight before any external request; decision rechecks under lock. */
    public void requireCandidate(String sourceKey, UUID documentId, UUID chunkId, String expectedRaw) {
        authority.requireReviewer();
        SourceDocument document = exact(sourceKey, documentId);
        SourceChunk chunk = chunks.findWithDocumentById(chunkId).orElseThrow(() ->
                new SourceReviewException(HttpStatus.NOT_FOUND, "SOURCE_REVIEW_NOT_FOUND"));
        if (!document.getRawSha256().equals(expectedRaw) || !documentId.equals(chunk.getSourceDocument().getId())
                || !"SUPPORT".equals(OfficialInformationClaimSelector.role(sourceKey, chunk.getSequence()))) {
            throw new SourceReviewException(HttpStatus.CONFLICT, "SOURCE_REVIEW_SCOPE_CHANGED");
        }
    }

    public OfficialInformationClaimSelector.ReviewGuide guidance(String sourceKey) {
        authority.requireReviewer();
        registry(sourceKey);
        return OfficialInformationClaimSelector.guide(sourceKey);
    }

    public DocumentView document(String sourceKey) {
        authority.requireReviewer();
        SourceDocument document = active(sourceKey);
        return new DocumentView(document.getId(), document.getDocumentVersion(),
                document.getTitle(), document.getPublishedAt(), document.getRawSha256(),
                document.getCanonicalUrl(), document.getRetrievedAt(),
                SourceHashing.sha256(document.getOriginalContent()).equals(document.getRawSha256()));
    }

    /** Persist only the exact excerpt, never the surrounding unreviewed HTML. */
    public List<Candidate> extract(String sourceKey, UUID documentId) {
        authority.requireReviewer();
        SourceDocument document = exact(sourceKey, documentId);
        // Tokenization and each idempotent write finish before the response is assembled.
        // Reload the exact saved rows with their document/registry graph; saveIfAbsent
        // returns detached entities after its short transaction closes.
        List<UUID> ids = chunking.chunk(document).stream().map(SourceChunk::getId).toList();
        return ids.stream()
                .map(id -> chunks.findWithDocumentById(id).orElseThrow(() ->
                        new SourceReviewException(HttpStatus.CONFLICT, "SOURCE_REVIEW_CANDIDATES_MISSING")))
                .map(chunk -> candidate(sourceKey, chunk)).toList();
    }

    public List<Candidate> candidates(String sourceKey, UUID documentId) {
        authority.requireReviewer();
        exact(sourceKey, documentId);
        return chunks.findAllBySourceDocument_IdOrderBySequenceAsc(documentId).stream()
                .map(chunk -> candidate(sourceKey, chunk)).toList();
    }

    public List<ReviewState> reviews(String sourceKey, UUID documentId) {
        authority.requireReviewer();
        exact(sourceKey, documentId);
        return jdbc.query("""
                SELECT r.id, c.source_chunk_id, r.review_status, r.reviewed_raw_sha256,
                       r.applicability_start_date, r.applicability_end_date, r.reviewed_at, r.revoked_at,
                       r.temporal_policy, r.temporal_verified_on, r.temporal_valid_until
                FROM source_applicability_review r
                JOIN source_applicability_chunk c ON c.review_id = r.id AND c.evidence_role = 'SUPPORT'
                WHERE r.source_document_id = ? AND r.review_target_kind = 'TOPIC'
                  AND r.financial_topic = ? ORDER BY r.created_at
                """, (rs, ignored) -> new ReviewState(rs.getObject(1, UUID.class),
                rs.getObject(2, UUID.class), rs.getString(3), rs.getString(4),
                rs.getObject(5, LocalDate.class), rs.getObject(6, LocalDate.class),
                rs.getObject(7, OffsetDateTime.class), rs.getObject(8, OffsetDateTime.class),
                TemporalEvidencePolicy.valueOf(rs.getString(9)),
                rs.getObject(10, LocalDate.class), rs.getObject(11, LocalDate.class),
                temporalState(TemporalEvidencePolicy.valueOf(rs.getString(9)),
                        rs.getString(3), rs.getObject(6, LocalDate.class),
                        rs.getObject(10, LocalDate.class), rs.getObject(11, LocalDate.class))),
                documentId, OfficialInformationClaimSelector.topic(sourceKey).name());
    }

    public List<DecisionAudit> audit(String sourceKey, UUID documentId) {
        authority.requireReviewer();
        exact(sourceKey, documentId);
        return jdbc.query("""
                SELECT a.review_id, a.reviewer_account_id, a.decision, a.decision_reason,
                       a.decided_at, a.reviewed_raw_sha256
                FROM source_review_decision_audit a
                JOIN source_applicability_review r ON r.id = a.review_id
                WHERE a.source_document_id = ? AND r.review_target_kind = 'TOPIC'
                  AND r.financial_topic = ? ORDER BY a.decided_at
                """, (rs, ignored) -> new DecisionAudit(rs.getObject(1, UUID.class),
                rs.getObject(2, UUID.class), rs.getString(3), rs.getString(4),
                rs.getObject(5, OffsetDateTime.class), rs.getString(6)), documentId,
                OfficialInformationClaimSelector.topic(sourceKey).name());
    }

    @Transactional
    public List<ReviewState> prepare(String sourceKey, UUID documentId, String expectedRaw,
            LocalDate start, LocalDate end) {
        return prepare(sourceKey, documentId, expectedRaw, start, end,
                TemporalEvidencePolicy.EXPLICIT_EFFECTIVE, null, null);
    }

    @Transactional
    public List<ReviewState> prepare(String sourceKey, UUID documentId, String expectedRaw,
            LocalDate start, LocalDate end, TemporalEvidencePolicy policy,
            LocalDate verifiedOn, LocalDate validUntil) {
        return prepare(sourceKey, documentId, expectedRaw, start, end, policy,
                verifiedOn, validUntil, null);
    }

    @Transactional
    public List<ReviewState> prepare(String sourceKey, UUID documentId, String expectedRaw,
            LocalDate start, LocalDate end, TemporalEvidencePolicy policy,
            LocalDate verifiedOn, LocalDate validUntil, List<ClaimScope> claimScopes) {
        authority.requireReviewer();
        TemporalEvidencePolicy selectedPolicy = policy == null
                ? TemporalEvidencePolicy.EXPLICIT_EFFECTIVE : policy;
        if ((claimScopes == null || claimScopes.isEmpty())
                && !selectedPolicy.valid(start, end, verifiedOn, validUntil)) {
            throw new SourceReviewException(HttpStatus.BAD_REQUEST, "SOURCE_REVIEW_SCOPE_INVALID");
        }
        SourceDocument document = exact(sourceKey, documentId);
        documents.findForUpdateById(documentId).orElseThrow();
        if (!document.getRawSha256().equals(expectedRaw)) {
            throw new SourceReviewException(HttpStatus.CONFLICT, "SOURCE_REVIEW_VERSION_CHANGED");
        }
        List<SourceChunk> selected = chunks.findAllBySourceDocument_IdOrderBySequenceAsc(documentId);
        List<SourceChunkData.Definition> definitions = selected.isEmpty() ? List.of()
                : OfficialInformationClaimSelector.select(document,
                    selected.getFirst().getChunkConfigVersion(),
                    selected.getFirst().getChunkConfiguration().getConfigJson());
        if (selected.isEmpty() || selected.size() != definitions.size()
                || selected.stream().anyMatch(chunk -> chunk.getReviewStatus() != SourceChunkReviewStatus.PENDING
                    || chunk.getSequence() < 0 || chunk.getSequence() >= definitions.size()
                    || !chunk.hasSameDefinition(definitions.get(chunk.getSequence())))) {
            throw new SourceReviewException(HttpStatus.CONFLICT, "SOURCE_REVIEW_CANDIDATES_MISSING");
        }
        if (!jdbc.queryForList("""
                SELECT id FROM source_applicability_review WHERE source_document_id = ?
                  AND review_target_kind = 'TOPIC' AND financial_topic = ?
                  AND review_status IN ('PENDING', 'APPROVED')
                """, UUID.class, documentId, OfficialInformationClaimSelector.topic(sourceKey).name()).isEmpty()) {
            throw new SourceReviewException(HttpStatus.CONFLICT, "SOURCE_REVIEW_ALREADY_PREPARED");
        }
        FinancialTopic topic = OfficialInformationClaimSelector.topic(sourceKey);
        boolean condition = !"fsc-card-use-qa".equals(sourceKey);
        boolean exception = "fsc-early-repayment-qa".equals(sourceKey);
        List<SourceChunk> support = selected.stream()
                .filter(chunk -> "SUPPORT".equals(OfficialInformationClaimSelector.role(sourceKey, chunk.getSequence())))
                .toList();
        List<SourceChunk> linkedConditions = selected.stream()
                .filter(chunk -> "CONDITION".equals(OfficialInformationClaimSelector.role(sourceKey, chunk.getSequence())))
                .toList();
        if (support.isEmpty() || ("fsc-silsong24-guide".equals(sourceKey) && linkedConditions.size() != 1)) {
            throw new SourceReviewException(HttpStatus.CONFLICT, "SOURCE_REVIEW_CANDIDATES_MISSING");
        }
        Map<UUID, ClaimScope> perClaim;
        try {
            perClaim = claimScopes == null ? Map.of() : claimScopes.stream()
                    .collect(Collectors.toMap(ClaimScope::chunkId, Function.identity()));
        } catch (RuntimeException invalidScope) {
            throw new SourceReviewException(HttpStatus.BAD_REQUEST, "SOURCE_REVIEW_SCOPE_INVALID");
        }
        if (!perClaim.isEmpty() && (perClaim.size() != support.size()
                || support.stream().anyMatch(chunk -> !perClaim.containsKey(chunk.getId()))
                || perClaim.values().stream().anyMatch(scope -> scope.policy() == null
                    || !scope.policy().valid(scope.start(), scope.end(),
                        scope.verifiedOn(), scope.validUntil())))) {
            throw new SourceReviewException(HttpStatus.BAD_REQUEST, "SOURCE_REVIEW_SCOPE_INVALID");
        }
        for (SourceChunk chunk : support) {
            ClaimScope scope = perClaim.getOrDefault(chunk.getId(), new ClaimScope(chunk.getId(),
                    selectedPolicy, start, end, verifiedOn, validUntil));
            UUID reviewId = UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO source_applicability_review
                        (id, source_document_id, reviewed_raw_sha256, scope_kind, scenario,
                         review_target_kind, financial_topic, applicability_start_date,
                         applicability_end_date, review_status, condition_required, exception_required,
                         temporal_policy, temporal_verified_on, temporal_valid_until)
                    VALUES (?, ?, ?, 'COMMON', NULL, 'TOPIC', ?, ?, ?, 'PENDING', ?, ?, ?, ?, ?)
                    """, reviewId, documentId, expectedRaw, topic.name(), scope.start(), scope.end(),
                    condition, exception, scope.policy().name(), scope.verifiedOn(), scope.validUntil());
            role(reviewId, documentId, chunk.getId(), "SUPPORT");
            if (condition) {
                if (linkedConditions.isEmpty()) role(reviewId, documentId, chunk.getId(), "CONDITION");
                else linkedConditions.forEach(linked -> role(reviewId, documentId, linked.getId(), "CONDITION"));
            }
            if (exception) role(reviewId, documentId, chunk.getId(), "EXCEPTION");
        }
        return reviews(sourceKey, documentId);
    }

    @Transactional
    public void decideCandidate(String sourceKey, UUID documentId, UUID chunkId, String expectedRaw,
            LocalDate start, LocalDate end, TemporalEvidencePolicy policy, LocalDate verifiedOn,
            LocalDate validUntil, List<ClaimScope> claimScopes, boolean conditionsReviewed,
            boolean exceptionsReviewed, SourceReviewerService.Decision decision,
            CorrectionCode correctionCode, String evidenceLocator) {
        decideCandidateWithEvidence(sourceKey, documentId, chunkId, expectedRaw, start, end, policy, verifiedOn,
                validUntil, claimScopes, conditionsReviewed, exceptionsReviewed, decision, correctionCode, evidenceLocator, null);
    }

    @Transactional
    public void decideCandidateWithEvidence(String sourceKey, UUID documentId, UUID chunkId, String expectedRaw,
            LocalDate start, LocalDate end, TemporalEvidencePolicy policy, LocalDate verifiedOn,
            LocalDate validUntil, List<ClaimScope> claimScopes, boolean conditionsReviewed,
            boolean exceptionsReviewed, SourceReviewerService.Decision decision,
            CorrectionCode correctionCode, String evidenceLocator, UUID verificationId) {
        UUID reviewer = authority.requireReviewer();
        if (documentId == null || chunkId == null || decision == null || expectedRaw == null
                || !expectedRaw.matches("[a-f0-9]{64}")) {
            throw new SourceReviewException(HttpStatus.BAD_REQUEST, "SOURCE_REVIEW_DECISION_INVALID");
        }
        documents.findForUpdateById(documentId).orElseThrow(() ->
                new SourceReviewException(HttpStatus.NOT_FOUND, "SOURCE_REVIEW_NOT_FOUND"));
        SourceDocument document = exact(sourceKey, documentId);
        if (!expectedRaw.equals(document.getRawSha256())) {
            throw new SourceReviewException(HttpStatus.CONFLICT, "SOURCE_REVIEW_VERSION_CHANGED");
        }
        if (verificationId != null) {
            if (decision != SourceReviewerService.Decision.APPROVE) {
                throw new SourceReviewException(HttpStatus.BAD_REQUEST, "SOURCE_REVIEW_EVIDENCE_INVALID");
            }
            var grants = jdbc.queryForList("SELECT account_id FROM source_reviewer_grant WHERE account_id=? AND revoked_at IS NULL FOR SHARE", UUID.class, reviewer);
            var accounts = jdbc.queryForList("SELECT id FROM account WHERE id=? AND status='ACTIVE' FOR SHARE", UUID.class, reviewer);
            if (grants.size() != 1 || accounts.size() != 1 || !authority.requireReviewer().equals(reviewer)) {
                throw new SourceReviewException(HttpStatus.FORBIDDEN, "SOURCE_REVIEW_FORBIDDEN");
            }
            var records = jdbc.query("""
                    SELECT source_document_id, source_registry_id, document_version, stored_raw_sha256,
                           stored_content_sha256, requested_url, checked_at, valid_until, result,
                           source_identity_verified, legal_version_verification_required
                    FROM source_url_verification_attempt WHERE id=? AND reviewer_account_id=? FOR UPDATE
                    """, (rs, i) -> new UrlVerificationApprovalEvidence.Attempt(rs.getObject(1, UUID.class),
                    rs.getObject(2, UUID.class), rs.getInt(3), rs.getString(4), rs.getString(5), rs.getString(6),
                    rs.getObject(7, OffsetDateTime.class), rs.getObject(8, OffsetDateTime.class), rs.getString(9),
                    rs.getBoolean(10), rs.getBoolean(11)), verificationId, reviewer);
            SourceRegistry currentRegistry = registry(sourceKey);
            var enabledSources = jdbc.queryForList("""
                    SELECT r.id FROM source_registry r JOIN official_source_domain o
                      ON o.domain=r.official_domain AND o.organization_name=r.organization_name
                    WHERE r.id=? AND r.enabled AND o.enabled AND r.canonical_url=? FOR SHARE OF r,o
                    """, UUID.class, currentRegistry.getId(), document.getCanonicalUrl());
            if (records.size() != 1 || !UrlVerificationApprovalEvidence.eligible(records.getFirst(), documentId,
                    currentRegistry.getId(), document.getDocumentVersion(), document.getRawSha256(),
                    document.getContentSha256(), currentRegistry.getCanonicalUrl(), OffsetDateTime.now(ZoneOffset.UTC))
                    || enabledSources.size() != 1
                    || !document.getRawSha256().equals(SourceHashing.sha256(document.getOriginalContent()))
                    || !document.getContentSha256().equals(SourceHashing.sha256(document.getNormalizedContent()))) {
                throw new SourceReviewException(HttpStatus.BAD_REQUEST, "SOURCE_REVIEW_CURRENTNESS_UNVERIFIED");
            }
        }
        List<ReviewState> existing = reviews(sourceKey, documentId);
        if (existing.isEmpty()) {
            if ((claimScopes == null || claimScopes.isEmpty()) && policy == null) {
                claimScopes = automaticScopes(sourceKey, document);
                if (claimScopes.size() > 1 && claimScopes.stream().anyMatch(scope ->
                        !chunkId.equals(scope.chunkId()) && scope.policy() == TemporalEvidencePolicy.HOLD)) {
                    throw new SourceReviewException(HttpStatus.BAD_REQUEST, "SOURCE_REVIEW_SCOPE_INVALID");
                }
            }
            existing = prepare(sourceKey, documentId, expectedRaw, start, end, policy,
                    verifiedOn, validUntil, claimScopes);
        }
        List<ReviewState> matches = existing.stream().filter(row -> chunkId.equals(row.chunkId())).toList();
        if (matches.size() != 1) {
            throw new SourceReviewException(HttpStatus.CONFLICT, "SOURCE_REVIEW_SCOPE_CHANGED");
        }
        ReviewState target = matches.getFirst();
        if (!"PENDING".equals(target.status())) {
            String requested = decision == SourceReviewerService.Decision.APPROVE ? "APPROVED" : "REJECTED";
            Integer count = jdbc.queryForObject("""
                    SELECT count(*) FROM source_review_decision_audit
                    WHERE review_id = ? AND reviewer_account_id = ? AND decision = ?
                      AND reviewed_raw_sha256 = ?
                    """, Integer.class, target.reviewId(), reviewer, requested, expectedRaw);
            if (requested.equals(target.status()) && count != null && count == 1) return;
            throw new SourceReviewException(HttpStatus.CONFLICT, "SOURCE_REVIEW_ALREADY_DECIDED");
        }
        decideWithEvidence(sourceKey, target.reviewId(), expectedRaw, conditionsReviewed, exceptionsReviewed,
                decision, correctionCode, evidenceLocator, verificationId);
    }

    /** Only a unique explicit effective date inside the claim is eligible for automatic scope. */
    private List<ClaimScope> automaticScopes(String sourceKey, SourceDocument document) {
        return chunks.findAllBySourceDocument_IdOrderBySequenceAsc(document.getId()).stream()
                .filter(chunk -> "SUPPORT".equals(OfficialInformationClaimSelector.role(sourceKey, chunk.getSequence())))
                .map(chunk -> {
                    var hint = TemporalEvidenceAssistant.analyze(document.getNormalizedContent(),
                            chunk.getSourceStartOffset(), chunk.getSourceEndOffset(), document.getPublishedAt());
                    var dates = hint.mentions().stream().filter(mention -> mention.inClaim()
                                    && mention.meaning() == TemporalEvidenceAssistant.Meaning.EFFECTIVE)
                            .map(TemporalEvidenceAssistant.DateMention::date).distinct().toList();
                    if (!hint.truncated() && hint.unresolvedDates().isEmpty()
                            && hint.recommendedPolicy() == TemporalEvidencePolicy.EXPLICIT_EFFECTIVE
                            && dates.size() == 1) {
                        return new ClaimScope(chunk.getId(), TemporalEvidencePolicy.EXPLICIT_EFFECTIVE,
                                dates.getFirst(), null, null, null);
                    }
                    return new ClaimScope(chunk.getId(), TemporalEvidencePolicy.HOLD, null, null, null, null);
                }).toList();
    }

    @Transactional
    public void decide(String sourceKey, UUID reviewId, String expectedRaw,
            boolean conditionsReviewed, boolean exceptionsReviewed, SourceReviewerService.Decision decision,
            CorrectionCode correctionCode, String evidenceLocator) {
        decideWithEvidence(sourceKey, reviewId, expectedRaw, conditionsReviewed, exceptionsReviewed,
                decision, correctionCode, evidenceLocator, null);
    }

    private void decideWithEvidence(String sourceKey, UUID reviewId, String expectedRaw,
            boolean conditionsReviewed, boolean exceptionsReviewed, SourceReviewerService.Decision decision,
            CorrectionCode correctionCode, String evidenceLocator, UUID verificationId) {
        UUID reviewer = authority.requireReviewer();
        if (decision == null || expectedRaw == null || !expectedRaw.matches("[a-f0-9]{64}")) {
            throw new SourceReviewException(HttpStatus.BAD_REQUEST, "SOURCE_REVIEW_DECISION_INVALID");
        }
        List<ReviewRow> rows = jdbc.query("""
                SELECT source_document_id, reviewed_raw_sha256, review_status,
                       condition_required, exception_required, temporal_policy,
                       applicability_start_date, applicability_end_date,
                       temporal_verified_on, temporal_valid_until FROM source_applicability_review
                WHERE id = ? AND review_target_kind = 'TOPIC' AND financial_topic = ? FOR UPDATE
                """, (rs, ignored) -> new ReviewRow(rs.getObject(1, UUID.class), rs.getString(2),
                rs.getString(3), rs.getBoolean(4), rs.getBoolean(5),
                TemporalEvidencePolicy.valueOf(rs.getString(6)),
                rs.getObject(7, LocalDate.class), rs.getObject(8, LocalDate.class),
                rs.getObject(9, LocalDate.class), rs.getObject(10, LocalDate.class)),
                reviewId, OfficialInformationClaimSelector.topic(sourceKey).name());
        if (rows.size() != 1) throw new SourceReviewException(HttpStatus.NOT_FOUND, "SOURCE_REVIEW_NOT_FOUND");
        ReviewRow row = rows.getFirst();
        SourceDocument document = exact(sourceKey, row.documentId());
        if (!"PENDING".equals(row.status()) || !expectedRaw.equals(row.rawSha256())
                || !expectedRaw.equals(document.getRawSha256())) {
            throw new SourceReviewException(HttpStatus.CONFLICT, "SOURCE_REVIEW_VERSION_CHANGED");
        }
        if (decision == SourceReviewerService.Decision.APPROVE
                && ((row.conditionRequired() && !conditionsReviewed)
                    || (row.exceptionRequired() && !exceptionsReviewed)
                    || row.policy() == TemporalEvidencePolicy.HOLD
                    || !row.policy().valid(row.start(), row.end(), row.verifiedOn(), row.validUntil()))) {
            throw new SourceReviewException(HttpStatus.BAD_REQUEST, "SOURCE_REVIEW_INCOMPLETE");
        }
        // V48 stores a reviewer attestation but does not bind a verified response from
        // the official URL to this source version. A client-supplied date or locator
        // therefore cannot establish present-day currentness.
        if (decision == SourceReviewerService.Decision.APPROVE
                && (row.policy() == TemporalEvidencePolicy.CURRENT_GUIDANCE
                    || row.policy() == TemporalEvidencePolicy.DYNAMIC_CHECK
                    || row.policy() == TemporalEvidencePolicy.VERSION_EFFECTIVE && row.end() == null)) {
            throw new SourceReviewException(HttpStatus.BAD_REQUEST, "SOURCE_REVIEW_CURRENTNESS_UNVERIFIED");
        }
        List<UUID> chunkIds = jdbc.queryForList("""
                SELECT source_chunk_id FROM source_applicability_chunk
                WHERE review_id = ? AND evidence_role = 'SUPPORT'
                """, UUID.class, reviewId);
        if (chunkIds.size() != 1) throw new SourceReviewException(HttpStatus.CONFLICT, "SOURCE_REVIEW_SCOPE_CHANGED");
        SourceChunk supportChunk = chunks.findWithDocumentById(chunkIds.getFirst()).orElseThrow();
        TemporalEvidenceAssistant.Assistance assistance = TemporalEvidenceAssistant.analyze(
                document.getNormalizedContent(), supportChunk.getSourceStartOffset(),
                supportChunk.getSourceEndOffset(), document.getPublishedAt());
        boolean correction = row.policy() != assistance.recommendedPolicy()
                || (row.start() != null && assistance.mentions().stream()
                    .noneMatch(mention -> mention.inClaim() && mention.date().equals(row.start())));
        String correctionAudit = decision == SourceReviewerService.Decision.APPROVE
                ? "SOURCE_TEXT_AND_SCOPE_MATCHED" : "REVIEWER_DECISION";
        if (decision == SourceReviewerService.Decision.APPROVE && correction) {
            if (correctionCode == CorrectionCode.CLAIM_OUTSIDE_SOURCE
                    || correctionCode == CorrectionCode.CONDITION_MISSING
                    || correctionCode == CorrectionCode.EXCEPTION_MISSING
                    || (row.policy() == TemporalEvidencePolicy.CURRENT_GUIDANCE
                        || row.policy() == TemporalEvidencePolicy.DYNAMIC_CHECK)
                        && correctionCode != CorrectionCode.REVIEWER_CURRENTNESS_CHECK) {
                throw new SourceReviewException(HttpStatus.BAD_REQUEST, "SOURCE_REVIEW_CORRECTION_REQUIRED");
            }
            boolean locatorMatches = evidenceLocator != null &&
                    (evidenceLocator.equals(supportChunk.getLocator())
                    || assistance.mentions().stream().anyMatch(mention -> mention.inClaim()
                        && evidenceLocator.equals(mention.locator())));
            boolean dateMatches = row.start() == null || assistance.mentions().stream()
                    .anyMatch(mention -> mention.inClaim() && mention.date().equals(row.start())
                        && evidenceLocator != null && evidenceLocator.equals(mention.locator())
                        && meaningMatches(row.policy(), mention.meaning()));
            if (correctionCode == null || !locatorMatches || !dateMatches) {
                throw new SourceReviewException(HttpStatus.BAD_REQUEST, "SOURCE_REVIEW_CORRECTION_REQUIRED");
            }
            correctionAudit = "REVIEWER_SCOPE_CORRECTION:" + correctionCode.name()
                    + ":" + evidenceLocator + ":" + row.policy().name()
                    + ":" + row.start() + ":" + row.end()
                    + ":" + row.verifiedOn() + ":" + row.validUntil();
        } else if (correctionCode != null) {
            correctionAudit = "REVIEWER_DECISION:" + correctionCode.name();
        }
        List<UUID> linkedChunkIds = jdbc.queryForList("""
                SELECT DISTINCT source_chunk_id FROM source_applicability_chunk WHERE review_id = ?
                """, UUID.class, reviewId);
        List<String> linkedRoles = jdbc.queryForList("""
                SELECT c.sequence || ':' || a.evidence_role
                FROM source_applicability_chunk a JOIN source_chunk c ON c.id = a.source_chunk_id
                WHERE a.review_id = ? ORDER BY c.sequence, a.evidence_role
                """, String.class, reviewId);
        List<String> expectedRoles = "fsc-silsong24-guide".equals(sourceKey)
                ? List.of("0:SUPPORT", "1:CONDITION")
                : row.conditionRequired() && row.exceptionRequired()
                    ? List.of("0:CONDITION", "0:EXCEPTION", "0:SUPPORT")
                    : row.conditionRequired() ? List.of("0:CONDITION", "0:SUPPORT")
                    : List.of("0:SUPPORT");
        if (!linkedRoles.equals(expectedRoles)) {
            throw new SourceReviewException(HttpStatus.CONFLICT, "SOURCE_REVIEW_SCOPE_CHANGED");
        }
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        if (decision == SourceReviewerService.Decision.APPROVE) {
            List<SourceChunk> linked = linkedChunkIds.stream()
                    .map(id -> chunks.findWithDocumentById(id).orElseThrow()).toList();
            List<SourceChunkData.Definition> definitions = OfficialInformationClaimSelector.select(document,
                    linked.getFirst().getChunkConfigVersion(),
                    linked.getFirst().getChunkConfiguration().getConfigJson());
            if (linked.stream().anyMatch(chunk -> chunk.getReviewStatus() != SourceChunkReviewStatus.PENDING
                    || !document.getId().equals(chunk.getSourceDocument().getId())
                    || chunk.getSequence() < 0 || chunk.getSequence() >= definitions.size()
                    || !chunk.hasSameDefinition(definitions.get(chunk.getSequence())))) {
                throw new SourceReviewException(HttpStatus.CONFLICT, "SOURCE_REVIEW_CHUNK_CHANGED");
            }
            linked.forEach(chunk -> chunk.approve(now));
            jdbc.update("""
                    UPDATE source_applicability_review SET review_status = 'APPROVED',
                        conditions_reviewed = TRUE, exceptions_reviewed = TRUE,
                        reviewed_by = ?, reviewed_at = ? WHERE id = ?
                    """, reviewer.toString(), now, reviewId);
        } else {
            jdbc.update("""
                    UPDATE source_applicability_review SET review_status = 'REJECTED',
                        reviewed_by = ?, reviewed_at = ? WHERE id = ?
                    """, reviewer.toString(), now, reviewId);
        }
        if (verificationId != null) {
            Boolean stillValid = jdbc.queryForObject("""
                    SELECT EXISTS (SELECT 1 FROM source_url_verification_attempt
                    WHERE id=? AND checked_at<=CURRENT_TIMESTAMP AND valid_until>clock_timestamp())
                    """, Boolean.class, verificationId);
            if (!Boolean.TRUE.equals(stillValid)) {
                throw new SourceReviewException(HttpStatus.BAD_REQUEST, "SOURCE_REVIEW_CURRENTNESS_UNVERIFIED");
            }
        }
        UUID auditId = auditDecision(reviewId, document.getId(), reviewer,
                decision == SourceReviewerService.Decision.APPROVE ? "APPROVED" : "REJECTED",
                now, expectedRaw, correctionAudit);
        if (verificationId != null) {
            jdbc.update("INSERT INTO source_review_verification_link (audit_id,verification_id,source_document_id,raw_sha256) VALUES (?,?,?,?)",
                    auditId, verificationId, document.getId(), expectedRaw);
        }
    }

    @Transactional
    public void revoke(String sourceKey, UUID reviewId) {
        UUID reviewer = authority.requireReviewer();
        List<ReviewRow> rows = jdbc.query("""
                SELECT source_document_id, reviewed_raw_sha256, review_status,
                       condition_required, exception_required, temporal_policy,
                       applicability_start_date, applicability_end_date,
                       temporal_verified_on, temporal_valid_until FROM source_applicability_review
                WHERE id = ? AND review_target_kind = 'TOPIC' AND financial_topic = ? FOR UPDATE
                """, (rs, ignored) -> new ReviewRow(rs.getObject(1, UUID.class), rs.getString(2),
                rs.getString(3), rs.getBoolean(4), rs.getBoolean(5),
                TemporalEvidencePolicy.valueOf(rs.getString(6)),
                rs.getObject(7, LocalDate.class), rs.getObject(8, LocalDate.class),
                rs.getObject(9, LocalDate.class), rs.getObject(10, LocalDate.class)),
                reviewId, OfficialInformationClaimSelector.topic(sourceKey).name());
        if (rows.size() != 1 || !"APPROVED".equals(rows.getFirst().status())) {
            throw new SourceReviewException(HttpStatus.CONFLICT, "SOURCE_REVIEW_NOT_APPROVED");
        }
        ReviewRow row = rows.getFirst();
        SourceDocument document = exact(sourceKey, row.documentId());
        List<UUID> chunkIds = jdbc.queryForList("""
                SELECT source_chunk_id FROM source_applicability_chunk
                WHERE review_id = ? AND evidence_role = 'SUPPORT'
                """, UUID.class, reviewId);
        if (chunkIds.size() != 1) throw new SourceReviewException(HttpStatus.CONFLICT, "SOURCE_REVIEW_SCOPE_CHANGED");
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        jdbc.update("""
                UPDATE source_applicability_review SET review_status = 'REVOKED',
                    revoked_at = ?, revoked_by = ?, revocation_reason = 'REVIEWER_WITHDRAWAL'
                WHERE id = ?
                """, now, reviewer.toString(), reviewId);
        List<UUID> linkedChunkIds = jdbc.queryForList("""
                SELECT DISTINCT source_chunk_id FROM source_applicability_chunk WHERE review_id = ?
                """, UUID.class, reviewId);
        linkedChunkIds.forEach(id -> chunks.findWithDocumentById(id).orElseThrow().revokeApproval(now));
        auditDecision(reviewId, document.getId(), reviewer, "REVOKED", now, row.rawSha256(),
                "REVIEWER_WITHDRAWAL");
    }

    private void role(UUID reviewId, UUID documentId, UUID chunkId, String role) {
        jdbc.update("""
                INSERT INTO source_applicability_chunk
                    (review_id, source_document_id, source_chunk_id, evidence_role) VALUES (?, ?, ?, ?)
                """, reviewId, documentId, chunkId, role);
    }

    private UUID auditDecision(UUID reviewId, UUID documentId, UUID reviewer,
            String decision, OffsetDateTime when, String rawSha256, String reason) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO source_review_decision_audit
                    (id, review_id, source_document_id, reviewer_account_id, decision,
                     decision_reason, decided_at, reviewed_raw_sha256)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, id, reviewId, documentId, reviewer, decision,
                reason,
                when, rawSha256);
        return id;
    }

    private SourceRegistry registry(String sourceKey) {
        if (!OfficialInformationClaimSelector.supports(sourceKey)) {
            throw new SourceReviewException(HttpStatus.NOT_FOUND, "SOURCE_REVIEW_NOT_FOUND");
        }
        return registries.findBySourceKeyAndEnabledTrue(sourceKey)
                .orElseThrow(() -> new SourceReviewException(HttpStatus.CONFLICT, "SOURCE_REVIEW_REGISTRY_MISSING"));
    }

    private SourceDocument active(String sourceKey) {
        return documents.findBySourceRegistry_IdAndStatus(registry(sourceKey).getId(), SourceDocumentStatus.ACTIVE)
                .orElseThrow(() -> new SourceReviewException(HttpStatus.CONFLICT, "SOURCE_REVIEW_DOCUMENT_MISSING"));
    }

    private SourceDocument exact(String sourceKey, UUID documentId) {
        SourceDocument document = active(sourceKey);
        if (!document.getId().equals(documentId)
                || !SourceHashing.sha256(document.getOriginalContent()).equals(document.getRawSha256())) {
            throw new SourceReviewException(HttpStatus.CONFLICT, "SOURCE_REVIEW_VERSION_CHANGED");
        }
        return document;
    }

    private Candidate candidate(String sourceKey, SourceChunk chunk) {
        if (!OfficialInformationClaimSelector.supports(sourceKey)
                || !sourceKey.equals(chunk.getSourceDocument().getSourceRegistry().getSourceKey())) {
            throw new SourceReviewException(HttpStatus.CONFLICT, "SOURCE_REVIEW_SCOPE_CHANGED");
        }
        return new Candidate(chunk.getId(), chunk.getParentSection(), chunk.getBody(),
                chunk.getLocator(), chunk.getArticleReference(), chunk.getPageReference(),
                chunk.getReviewStatus().name(), OfficialInformationClaimSelector.role(sourceKey, chunk.getSequence()),
                TemporalEvidenceAssistant.analyze(chunk.getSourceDocument().getNormalizedContent(),
                        chunk.getSourceStartOffset(), chunk.getSourceEndOffset(),
                        chunk.getSourceDocument().getPublishedAt()));
    }

    private static String temporalState(TemporalEvidencePolicy policy, String reviewStatus,
            LocalDate end, LocalDate verifiedOn, LocalDate validUntil) {
        if (policy == TemporalEvidencePolicy.HOLD) return "HOLD";
        if ("REVOKED".equals(reviewStatus)) return "REVOKED";
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Seoul"));
        if (end != null && today.isAfter(end) || validUntil != null && today.isAfter(validUntil))
            return "RECHECK_REQUIRED";
        if (!"APPROVED".equals(reviewStatus)) return "REVIEW_REQUIRED";
        return verifiedOn == null ? "EFFECTIVE_WINDOW_REVIEWED" : "REVIEWER_CHECK_WINDOW";
    }

    private static boolean meaningMatches(TemporalEvidencePolicy policy,
            TemporalEvidenceAssistant.Meaning meaning) {
        return switch (policy) {
            case EXPLICIT_EFFECTIVE -> meaning == TemporalEvidenceAssistant.Meaning.EFFECTIVE;
            case VERSION_EFFECTIVE -> meaning == TemporalEvidenceAssistant.Meaning.LAW_EFFECTIVE;
            case CONTRACT_DATE -> meaning == TemporalEvidenceAssistant.Meaning.CONTRACT;
            case HISTORICAL_FACT -> meaning == TemporalEvidenceAssistant.Meaning.HISTORICAL;
            case CURRENT_GUIDANCE, DYNAMIC_CHECK, HOLD -> false;
        };
    }

    public record DocumentView(UUID documentId, int version, String title, LocalDate publishedAt,
                               String rawSha256, String officialUrl, OffsetDateTime retrievedAt,
                               boolean storedBytesMatchHash) {}
    public record Candidate(UUID chunkId, String label, String body, String locator,
                            String article, String page, String status, String role,
                            TemporalEvidenceAssistant.Assistance temporalAssistance) {}
    public record ReviewState(UUID reviewId, UUID chunkId, String status, String rawSha256,
                              LocalDate start, LocalDate end, OffsetDateTime reviewedAt,
                              OffsetDateTime revokedAt, TemporalEvidencePolicy temporalPolicy,
                              LocalDate temporalVerifiedOn, LocalDate temporalValidUntil,
                              String temporalState) {}
    public record DecisionAudit(UUID reviewId, UUID reviewerAccountId, String decision,
                                String reason, OffsetDateTime decidedAt, String rawSha256) {}
    public record ClaimScope(UUID chunkId, TemporalEvidencePolicy policy, LocalDate start,
                             LocalDate end, LocalDate verifiedOn, LocalDate validUntil) {}
    public enum CorrectionCode { SOURCE_DATE_MISCLASSIFIED, SOURCE_CONTEXT_MISSING,
        REVIEWER_CURRENTNESS_CHECK, CLAIM_OUTSIDE_SOURCE, CONDITION_MISSING,
        EXCEPTION_MISSING, OTHER_SCOPE }
    private record ReviewRow(UUID documentId, String rawSha256, String status,
                             boolean conditionRequired, boolean exceptionRequired,
                             TemporalEvidencePolicy policy, LocalDate start, LocalDate end,
                             LocalDate verifiedOn, LocalDate validUntil) {}
}
