package com.financialhelper.source;

import com.financialhelper.account.AccountPrincipal;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.Comparator;
import com.financialhelper.retrieval.KureRuntimeProperties;
import com.financialhelper.retrieval.RetrievalGenerationIndexingService;
import com.financialhelper.retrieval.GeneralTopicCoverageService;
import com.financialhelper.consultation.FinancialTopic;

/** Human decisions only. Grant provisioning is deliberately absent from HTTP. */
@Service
public class SourceReviewerService {
    private static final String SOURCE_KEY = "fsc-deposit-protection-qa-pdf";
    private static final Set<Integer> CONDITION_QUESTIONS = Set.of(2, 5, 6, 8, 9, 10);
    private static final Set<Integer> EXCEPTION_QUESTIONS = Set.of(2, 3, 10);

    private final JdbcTemplate jdbc;
    private final SourceRegistryRepository registryRepository;
    private final SourceDocumentRepository documentRepository;
    private final SourceChunkRepository chunkRepository;
    private final SourceIngestionService ingestionService;
    private final SourceChunkingService chunkingService;
    private final SourceChunkingProperties chunkingProperties;
    private final RetrievalGenerationPersistenceService generationPersistence;
    private final RetrievalGenerationIndexingService generationIndexing;
    private final KureRuntimeProperties kureProperties;
    private final GeneralTopicCoverageService topicCoverage;

    public SourceReviewerService(JdbcTemplate jdbc, SourceRegistryRepository registryRepository,
                                 SourceDocumentRepository documentRepository,
                                 SourceChunkRepository chunkRepository,
                                 SourceIngestionService ingestionService,
                                 SourceChunkingService chunkingService,
                                 SourceChunkingProperties chunkingProperties,
                                 RetrievalGenerationPersistenceService generationPersistence,
                                 RetrievalGenerationIndexingService generationIndexing,
                                 KureRuntimeProperties kureProperties,
                                 GeneralTopicCoverageService topicCoverage) {
        this.jdbc = jdbc;
        this.registryRepository = registryRepository;
        this.documentRepository = documentRepository;
        this.chunkRepository = chunkRepository;
        this.ingestionService = ingestionService;
        this.chunkingService = chunkingService;
        this.chunkingProperties = chunkingProperties;
        this.generationPersistence = generationPersistence;
        this.generationIndexing = generationIndexing;
        this.kureProperties = kureProperties;
        this.topicCoverage = topicCoverage;
    }

    public UUID requireReviewer() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof AccountPrincipal principal)) {
            throw new SourceReviewException(HttpStatus.UNAUTHORIZED, "SOURCE_REVIEW_LOGIN_REQUIRED");
        }
        UUID accountId = principal.accountId();
        Boolean allowed = jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM source_reviewer_grant g JOIN account a ON a.id = g.account_id
                    WHERE g.account_id = ? AND g.revoked_at IS NULL AND a.status = 'ACTIVE')
                """, Boolean.class, accountId);
        if (!Boolean.TRUE.equals(allowed)) {
            throw new SourceReviewException(HttpStatus.FORBIDDEN, "SOURCE_REVIEW_FORBIDDEN");
        }
        return accountId;
    }

    /** Read-only inventory; registration alone never makes a source reviewable or searchable. */
    public List<ReviewableSource> sources() {
        requireReviewer();
        return registryRepository.findAllByEnabledTrueOrderBySourceKeyAsc().stream()
                .map(source -> {
                    SourceDocument document = documentRepository.findBySourceRegistry_IdAndStatus(
                            source.getId(), SourceDocumentStatus.ACTIVE).orElse(null);
                    boolean deposit = SOURCE_KEY.equals(source.getSourceKey())
                            && VerifiedPdfMetadata.DEPOSIT_PDF_URL.equals(source.getCanonicalUrl());
                    ReviewCounts counts = document == null ? new ReviewCounts(0, 0, 0, 0)
                            : jdbc.queryForObject("""
                                    SELECT count(*), count(*) FILTER (WHERE review_status = 'APPROVED'),
                                           count(*) FILTER (WHERE review_status = 'PENDING'),
                                           count(*) FILTER (WHERE review_status = 'REJECTED')
                                    FROM source_applicability_review WHERE source_document_id = ?
                                    """, (rs, ignored) -> new ReviewCounts(rs.getInt(1), rs.getInt(2),
                                    rs.getInt(3), rs.getInt(4)), document.getId());
                    return new ReviewableSource(source.getSourceKey(), source.getOrganizationName(),
                            source.getCanonicalUrl(), source.getAcquisitionType().name(),
                            document == null ? null : document.getId(),
                            document == null ? null : document.getTitle(),
                            document == null ? null : document.getPublishedAt(),
                            document == null ? null : document.getDocumentVersion(),
                            document == null ? null : document.getRawSha256(), deposit,
                            counts.total(), counts.approved(), counts.pending(), counts.rejected(),
                            deposit && topicCoverage.hasCurrentCommonCorpus(FinancialTopic.DEPOSITS_ACCOUNTS));
                }).toList();
    }

    /** Fetch and parse outside a database transaction. Registry must already be explicitly provisioned. */
    public SourceIngestionResult ingestDeposit() {
        requireReviewer();
        requireRegistry();
        return ingestionService.ingestOne(SOURCE_KEY);
    }

    /** Tokenizer work remains outside the review write transaction. */
    public List<DepositPdfReviewCandidateSelector.Candidate> chunkDeposit(UUID documentId) {
        requireReviewer();
        SourceDocument document = requireExactDocument(documentId);
        chunkingService.chunk(document);
        return candidates(documentId);
    }

    public List<DepositPdfReviewCandidateSelector.Candidate> candidates(UUID documentId) {
        requireReviewer();
        SourceDocument document = requireExactDocument(documentId);
        return new DepositPdfReviewCandidateSelector().select(document,
                chunkRepository.findAllBySourceDocument_IdOrderBySequenceAsc(documentId));
    }

    public DocumentView currentDocument() {
        requireReviewer();
        SourceRegistry registry = requireRegistry();
        SourceDocument document = documentRepository.findBySourceRegistry_IdAndStatus(
                        registry.getId(), SourceDocumentStatus.ACTIVE)
                .orElseThrow(() -> new SourceReviewException(HttpStatus.CONFLICT, "SOURCE_REVIEW_DOCUMENT_MISSING"));
        return new DocumentView(document.getId(), document.getDocumentVersion(), document.getRawSha256(),
                document.getTitle(), document.getPublishedAt(), document.getCanonicalUrl(),
                document.getMetadataSourceUrl(), document.getRetrievedAt(),
                SourceHashing.sha256(document.getOriginalContent()).equals(document.getRawSha256()),
                VerifiedPdfMetadata.DEPOSIT_RAW_SHA256.equals(document.getRawSha256()),
                OffsetDateTime.now(ZoneOffset.UTC));
    }

    public List<ReviewState> reviews(UUID documentId) {
        requireReviewer();
        requireExactDocument(documentId);
        return jdbc.query("""
                SELECT id, question_number, review_status, reviewed_raw_sha256,
                       applicability_start_date, applicability_end_date, reviewed_at, revoked_at
                FROM source_applicability_review
                WHERE source_document_id = ? AND review_target_kind = 'TOPIC'
                  AND financial_topic = 'DEPOSITS_ACCOUNTS' AND question_number IS NOT NULL
                ORDER BY question_number, created_at
                """, (rs, ignored) -> new ReviewState(rs.getObject("id", UUID.class),
                rs.getInt("question_number"), rs.getString("review_status"),
                rs.getString("reviewed_raw_sha256"),
                rs.getObject("applicability_start_date", LocalDate.class),
                rs.getObject("applicability_end_date", LocalDate.class),
                rs.getObject("reviewed_at", OffsetDateTime.class),
                rs.getObject("revoked_at", OffsetDateTime.class)), documentId);
    }

    public List<DecisionAudit> audit(UUID documentId) {
        requireReviewer();
        requireExactDocument(documentId);
        return jdbc.query("""
                SELECT a.review_id, r.question_number, a.reviewer_account_id, a.decision,
                       a.decision_reason, a.decided_at, a.reviewed_raw_sha256
                FROM source_review_decision_audit a
                JOIN source_applicability_review r ON r.id = a.review_id
                WHERE a.source_document_id = ? AND r.review_target_kind = 'TOPIC'
                  AND r.financial_topic = 'DEPOSITS_ACCOUNTS'
                ORDER BY a.decided_at, r.question_number
                """, (rs, ignored) -> new DecisionAudit(rs.getObject("review_id", UUID.class),
                rs.getInt("question_number"), rs.getObject("reviewer_account_id", UUID.class),
                rs.getString("decision"), rs.getString("decision_reason"),
                rs.getObject("decided_at", OffsetDateTime.class),
                rs.getString("reviewed_raw_sha256")), documentId);
    }

    /** Creates pending decisions only; it cannot approve or activate anything. */
    @Transactional
    public List<PreparedReview> prepare(UUID documentId, LocalDate startDate, LocalDate endDate) {
        requireReviewer();
        if (startDate == null || startDate.isBefore(LocalDate.of(2025, 9, 1))
                || (endDate != null && endDate.isBefore(startDate))) {
            throw new SourceReviewException(HttpStatus.BAD_REQUEST, "SOURCE_REVIEW_SCOPE_INVALID");
        }
        SourceDocument document = requireExactDocument(documentId);
        List<DepositPdfReviewCandidateSelector.Candidate> candidates =
                new DepositPdfReviewCandidateSelector().select(document,
                        chunkRepository.findAllBySourceDocument_IdOrderBySequenceAsc(documentId));
        if (candidates.stream().anyMatch(candidate -> chunkRepository.findById(candidate.supportChunkId())
                .orElseThrow().getReviewStatus() != SourceChunkReviewStatus.PENDING)) {
            throw new SourceReviewException(HttpStatus.CONFLICT, "SOURCE_REVIEW_ALREADY_PREPARED");
        }
        String existing = "SELECT id FROM source_applicability_review WHERE source_document_id = ? AND review_target_kind = 'TOPIC' AND financial_topic = 'DEPOSITS_ACCOUNTS' AND review_status <> 'REVOKED'";
        if (!jdbc.queryForList(existing, UUID.class, documentId).isEmpty()) {
            throw new SourceReviewException(HttpStatus.CONFLICT, "SOURCE_REVIEW_ALREADY_PREPARED");
        }
        // The document window is an explicit reviewer supplied scope, never an invented incident date.
        document.setApplicabilityWindow(startDate, endDate);
        return candidates.stream().map(candidate -> {
            UUID reviewId = UUID.randomUUID();
            boolean condition = CONDITION_QUESTIONS.contains(candidate.question());
            boolean exception = EXCEPTION_QUESTIONS.contains(candidate.question());
            jdbc.update("""
                    INSERT INTO source_applicability_review
                        (id, source_document_id, reviewed_raw_sha256, scope_kind, scenario,
                         review_target_kind, financial_topic, applicability_start_date, applicability_end_date,
                         review_status, condition_required, exception_required, question_number)
                    VALUES (?, ?, ?, 'COMMON', NULL, 'TOPIC', 'DEPOSITS_ACCOUNTS', ?, ?, 'PENDING', ?, ?, ?)
                    """, reviewId, documentId, document.getRawSha256(), startDate, endDate, condition, exception,
                    candidate.question());
            addRole(reviewId, documentId, candidate.supportChunkId(), "SUPPORT");
            if (condition) addRole(reviewId, documentId, candidate.supportChunkId(), "CONDITION");
            if (exception) addRole(reviewId, documentId, candidate.supportChunkId(), "EXCEPTION");
            return new PreparedReview(candidate.question(), reviewId);
        }).toList();
    }

    @Transactional
    public void decide(UUID reviewId, String expectedRawSha256, boolean conditionsReviewed,
                       boolean exceptionsReviewed, Decision decision) {
        UUID reviewer = requireReviewer();
        if (decision == null || expectedRawSha256 == null || !expectedRawSha256.matches("[a-f0-9]{64}")) {
            throw new SourceReviewException(HttpStatus.BAD_REQUEST, "SOURCE_REVIEW_DECISION_INVALID");
        }
        ReviewRow row = lockedReview(reviewId);
        SourceDocument document = requireExactDocument(row.documentId());
        if (!document.getRawSha256().equals(expectedRawSha256)
                || !row.rawSha256().equals(expectedRawSha256) || !"PENDING".equals(row.status())) {
            throw new SourceReviewException(HttpStatus.CONFLICT, "SOURCE_REVIEW_VERSION_CHANGED");
        }
        if (decision == Decision.APPROVE && (!conditionsReviewed || !exceptionsReviewed)) {
            throw new SourceReviewException(HttpStatus.BAD_REQUEST, "SOURCE_REVIEW_INCOMPLETE");
        }
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        if (decision == Decision.APPROVE) {
            UUID chunkId = supportChunkId(reviewId);
            SourceChunk chunk = chunkRepository.findWithDocumentById(chunkId).orElseThrow();
            if (chunk.getReviewStatus() != SourceChunkReviewStatus.PENDING) {
                throw new SourceReviewException(HttpStatus.CONFLICT, "SOURCE_REVIEW_CHUNK_CHANGED");
            }
            chunk.approve(now);
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
        audit(reviewId, document.getId(), reviewer, decision == Decision.APPROVE ? "APPROVED" : "REJECTED",
                decision == Decision.APPROVE ? "SOURCE_TEXT_MATCHED" : "SOURCE_SCOPE_REJECTED",
                now, expectedRawSha256);
    }

    @Transactional
    public void revoke(UUID reviewId) {
        UUID reviewer = requireReviewer();
        ReviewRow row = lockedReview(reviewId);
        if (!"APPROVED".equals(row.status())) {
            throw new SourceReviewException(HttpStatus.CONFLICT, "SOURCE_REVIEW_NOT_APPROVED");
        }
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        jdbc.update("""
                UPDATE source_applicability_review SET review_status = 'REVOKED',
                    revoked_at = ?, revoked_by = ?, revocation_reason = 'REVIEWER_WITHDRAWAL'
                WHERE id = ?
                """, now, reviewer.toString(), reviewId);
        SourceChunk chunk = chunkRepository.findWithDocumentById(supportChunkId(reviewId)).orElseThrow();
        chunk.revokeApproval(now);
        audit(reviewId, row.documentId(), reviewer, "REVOKED", "REVIEWER_WITHDRAWAL", now, row.rawSha256());
    }

    /** Explicit reviewer action. The external index build is deliberately outside a DB transaction. */
    public UUID buildReady(UUID documentId) {
        requireReviewer();
        SourceDocument document = requireExactDocument(documentId);
        if (document.getApplicabilityStartDate() == null) {
            throw new SourceReviewException(HttpStatus.CONFLICT, "SOURCE_REVIEW_SCOPE_INVALID");
        }
        List<SourceChunk> approved = chunkRepository.findAllApprovedActive().stream()
                .filter(chunk -> documentId.equals(chunk.getSourceDocument().getId()))
                .sorted(Comparator.comparing(chunk -> chunk.getId().toString())).toList();
        Set<Integer> expected = Set.of(1, 2, 3, 5, 6, 7, 8, 9, 10);
        List<ReviewState> states = reviews(documentId);
        if (approved.size() != expected.size() || states.size() != expected.size()
                || !states.stream().map(ReviewState::question).collect(java.util.stream.Collectors.toSet()).equals(expected)
                || states.stream().anyMatch(state -> !"APPROVED".equals(state.status())
                || !document.getRawSha256().equals(state.rawSha256()))) {
            throw new SourceReviewException(HttpStatus.CONFLICT, "SOURCE_REVIEW_INCOMPLETE");
        }
        for (SourceChunk chunk : approved) {
            int number = states.stream().filter(state -> supportChunkId(state.reviewId()).equals(chunk.getId()))
                    .map(ReviewState::question).findFirst().orElse(-1);
            if (!expected.contains(number) || !chunkingProperties.configVersion().equals(chunk.getChunkConfigVersion())
                    || chunk.getParentSection() == null || !chunk.getParentSection().startsWith(number + ". ")
                    || chunk.getPageReference() == null || chunk.getLocator() == null
                    || !chunk.getBody().startsWith(number + ". ")) {
                throw new SourceReviewException(HttpStatus.CONFLICT, "SOURCE_REVIEW_SCOPE_CHANGED");
            }
            long requiredRoles = jdbc.queryForObject("""
                    SELECT count(*) FROM source_applicability_chunk c
                    JOIN source_applicability_review r ON r.id = c.review_id
                    WHERE r.source_document_id = ? AND r.question_number = ?
                      AND r.review_status = 'APPROVED' AND r.reviewed_raw_sha256 = ?
                      AND r.conditions_reviewed AND r.exceptions_reviewed
                      AND c.source_chunk_id = ? AND c.evidence_role IN ('SUPPORT','CONDITION','EXCEPTION')
                    """, Long.class, documentId, number, document.getRawSha256(), chunk.getId());
            int minimum = 1 + (CONDITION_QUESTIONS.contains(number) ? 1 : 0)
                    + (EXCEPTION_QUESTIONS.contains(number) ? 1 : 0);
            if (requiredRoles < minimum) {
                throw new SourceReviewException(HttpStatus.CONFLICT, "SOURCE_REVIEW_SCOPE_CHANGED");
            }
        }
        String snapshot = SourceHashing.sha256(approved.stream().map(chunk -> String.join("|",
                chunk.getId().toString(), chunk.getBodySha256(), documentId.toString(),
                document.getContentSha256())).reduce((left, right) -> left + "\n" + right).orElseThrow());
        RetrievalGeneration generation = generationPersistence.getOrCreate(new RetrievalGenerationData.Definition(
                "deposit-fsc-" + snapshot.substring(0, 16), "kure-v2-plaid-v1",
                kureProperties.modelIdentifier(), kureProperties.modelRevision(),
                kureProperties.tokenizerIdentifier(), kureProperties.tokenizerRevision(),
                "{\"queryLength\":64,\"documentMaxTokens\":8192,\"doQueryExpansion\":true,\"queryPrefix\":\"\",\"documentPrefix\":\"\"}",
                "{\"backend\":\"PLAID\",\"nbits\":4,\"seed\":42,\"useFast\":true,\"useTriton\":false}",
                "{\"corpus\":\"DEPOSIT-FSC\",\"sourceKeys\":[\"" + SOURCE_KEY + "\"]}",
                chunkingProperties.configVersion(), snapshot));
        generationIndexing.buildAndActivate(generation.getId());
        return generation.getId();
    }

    private SourceRegistry requireRegistry() {
        SourceRegistry registry = registryRepository.findBySourceKeyAndEnabledTrue(SOURCE_KEY)
                .orElseThrow(() -> new SourceReviewException(HttpStatus.CONFLICT, "SOURCE_REVIEW_REGISTRY_MISSING"));
        if (!VerifiedPdfMetadata.DEPOSIT_PDF_URL.equals(registry.getCanonicalUrl())
                || !"fsc.go.kr".equals(registry.getOfficialDomain())
                || !"금융위원회".equals(registry.getOrganizationName())
                || registry.getAcquisitionType() != SourceAcquisitionType.PDF) {
            throw new SourceReviewException(HttpStatus.CONFLICT, "SOURCE_REVIEW_REGISTRY_MISMATCH");
        }
        return registry;
    }

    private SourceDocument requireExactDocument(UUID documentId) {
        SourceRegistry registry = requireRegistry();
        SourceDocument document = documentRepository.findBySourceRegistry_IdAndStatus(
                        registry.getId(), SourceDocumentStatus.ACTIVE)
                .orElseThrow(() -> new SourceReviewException(HttpStatus.CONFLICT, "SOURCE_REVIEW_DOCUMENT_MISSING"));
        if (!document.getId().equals(documentId)
                || !VerifiedPdfMetadata.DEPOSIT_RAW_SHA256.equals(document.getRawSha256())
                || !VerifiedPdfMetadata.DEPOSIT_POST_URL.equals(document.getMetadataSourceUrl())) {
            throw new SourceReviewException(HttpStatus.CONFLICT, "SOURCE_REVIEW_VERSION_CHANGED");
        }
        return document;
    }

    private ReviewRow lockedReview(UUID reviewId) {
        List<ReviewRow> rows = jdbc.query("""
                SELECT source_document_id, reviewed_raw_sha256, review_status
                FROM source_applicability_review WHERE id = ? AND review_target_kind = 'TOPIC'
                  AND financial_topic = 'DEPOSITS_ACCOUNTS' FOR UPDATE
                """, (rs, ignored) -> new ReviewRow(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3)), reviewId);
        if (rows.size() != 1) throw new SourceReviewException(HttpStatus.NOT_FOUND, "SOURCE_REVIEW_NOT_FOUND");
        return rows.getFirst();
    }

    private UUID supportChunkId(UUID reviewId) {
        List<UUID> ids = jdbc.queryForList("""
                SELECT source_chunk_id FROM source_applicability_chunk
                WHERE review_id = ? AND evidence_role = 'SUPPORT'
                """, UUID.class, reviewId);
        if (ids.size() != 1) throw new SourceReviewException(HttpStatus.CONFLICT, "SOURCE_REVIEW_SCOPE_CHANGED");
        return ids.getFirst();
    }

    private void addRole(UUID reviewId, UUID documentId, UUID chunkId, String role) {
        jdbc.update("""
                INSERT INTO source_applicability_chunk
                    (review_id, source_document_id, source_chunk_id, evidence_role) VALUES (?, ?, ?, ?)
                """, reviewId, documentId, chunkId, role);
    }

    private void audit(UUID reviewId, UUID documentId, UUID reviewer, String decision,
                       String reason, OffsetDateTime now, String rawSha256) {
        jdbc.update("""
                INSERT INTO source_review_decision_audit
                    (id, review_id, source_document_id, reviewer_account_id, decision,
                     decision_reason, decided_at, reviewed_raw_sha256)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), reviewId, documentId, reviewer, decision, reason, now, rawSha256);
    }

    private record ReviewRow(UUID documentId, String rawSha256, String status) { }
    public record PreparedReview(int question, UUID reviewId) { }
    private record ReviewCounts(int total, int approved, int pending, int rejected) { }
    public record ReviewableSource(String sourceKey, String organization, String officialUrl,
                                   String format, UUID documentId, String title,
                                   LocalDate publishedAt, Integer documentVersion, String rawSha256,
                                   boolean reviewWorkflowAvailable, int reviewItemCount,
                                   int approvedItemCount, int pendingItemCount, int rejectedItemCount,
                                   boolean searchAvailable) { }
    public record DocumentView(UUID documentId, int documentVersion, String rawSha256,
                               String title, LocalDate publishedAt, String pdfUrl, String metadataSourceUrl,
                               OffsetDateTime retrievedAt, boolean storedBytesMatchHash,
                               boolean matchesReviewedHash, OffsetDateTime verifiedAt) { }
    public record ReviewState(UUID reviewId, int question, String status, String rawSha256,
                              LocalDate applicabilityStartDate, LocalDate applicabilityEndDate,
                              OffsetDateTime reviewedAt, OffsetDateTime revokedAt) { }
    public record DecisionAudit(UUID reviewId, int question, UUID reviewerAccountId,
                                String decision, String reason, OffsetDateTime decidedAt,
                                String rawSha256) { }
    public enum Decision { APPROVE, REJECT }
}
