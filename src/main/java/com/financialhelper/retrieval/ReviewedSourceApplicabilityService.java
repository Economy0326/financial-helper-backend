package com.financialhelper.retrieval;

import com.financialhelper.consultation.FinancialTopic;
import com.financialhelper.source.VerifiedPdfMetadata;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Human-reviewed applicability is independent of ingestion, chunk approval and indexing. */
@Service
public class ReviewedSourceApplicabilityService {
    private final JdbcTemplate jdbc;

    public ReviewedSourceApplicabilityService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public Map<UUID, Match> eligible(OfficialSearchRequest request,
                                      List<RetrievalCorpusSnapshotService.EligibleChunk> ready) {
        if (request.scenario() == null || request.incidentDate() == null) return Map.of();
        List<Review> reviews = jdbc.query("""
                SELECT r.id, r.source_document_id, r.scope_kind, r.institution_name,
                       r.product_type, r.applicability_start_date, r.applicability_end_date,
                       r.condition_required, r.exception_required, d.canonical_url, d.raw_sha256
                FROM source_applicability_review r
                JOIN source_document d ON d.id = r.source_document_id
                WHERE r.review_status = 'APPROVED' AND r.conditions_reviewed = TRUE
                  AND r.exceptions_reviewed = TRUE AND r.revoked_at IS NULL
                  AND d.status = 'ACTIVE' AND d.raw_sha256 = r.reviewed_raw_sha256
                  AND r.review_target_kind = 'SCENARIO'
                  AND r.scenario = ? AND r.event_type IS NULL
                  AND r.applicability_start_date <= ?
                  AND (r.applicability_end_date IS NULL OR r.applicability_end_date >= ?)
                  AND d.applicability_start_date IS NOT NULL
                  AND d.applicability_start_date <= ?
                  AND (d.applicability_end_date IS NULL OR d.applicability_end_date >= ?)
                  AND (r.product_type IS NULL OR r.product_type = ?)
                  AND (r.scope_kind = 'COMMON' OR (r.scope_kind = 'INSTITUTION_SPECIFIC'
                       AND r.institution_name = ?))
                """, (rs, n) -> new Review((UUID) rs.getObject(1), (UUID) rs.getObject(2),
                rs.getString(3), rs.getString(4), rs.getString(5),
                rs.getObject(6, LocalDate.class), rs.getObject(7, LocalDate.class),
                rs.getBoolean(8), rs.getBoolean(9), rs.getString(10), rs.getString(11)),
                request.scenario(), request.incidentDate(), request.incidentDate(),
                request.incidentDate(), request.incidentDate(), request.productType(),
                request.institution());
        return matches(reviews, ready, null);
    }

    /** Topic reviews use an explicit as-of date, never a fabricated incident date. */
    @Transactional(readOnly = true)
    public Map<UUID, Match> eligibleTopic(FinancialTopic topic, LocalDate asOfDate,
                                          String institution, String product,
                                          List<RetrievalCorpusSnapshotService.EligibleChunk> ready) {
        if (topic == null || asOfDate == null || ready == null) return Map.of();
        List<Review> reviews = jdbc.query("""
                SELECT r.id, r.source_document_id, r.scope_kind, r.institution_name,
                       r.product_type, r.applicability_start_date, r.applicability_end_date,
                       r.condition_required, r.exception_required, d.canonical_url, d.raw_sha256
                FROM source_applicability_review r
                JOIN source_document d ON d.id = r.source_document_id
                WHERE r.review_target_kind = 'TOPIC' AND r.financial_topic = ?
                  AND r.review_status = 'APPROVED' AND r.conditions_reviewed = TRUE
                  AND r.exceptions_reviewed = TRUE AND r.revoked_at IS NULL
                  AND d.status = 'ACTIVE' AND d.raw_sha256 = r.reviewed_raw_sha256
                  AND r.applicability_start_date <= ?
                  AND (r.applicability_end_date IS NULL OR r.applicability_end_date >= ?)
                  AND d.applicability_start_date IS NOT NULL
                  AND d.applicability_start_date <= ?
                  AND (d.applicability_end_date IS NULL OR d.applicability_end_date >= ?)
                  AND (r.product_type IS NULL OR r.product_type = ?)
                  AND (r.scope_kind = 'COMMON' OR (r.scope_kind = 'INSTITUTION_SPECIFIC'
                       AND r.institution_name = ?))
                """, (rs, n) -> new Review((UUID) rs.getObject(1), (UUID) rs.getObject(2),
                rs.getString(3), rs.getString(4), rs.getString(5),
                rs.getObject(6, LocalDate.class), rs.getObject(7, LocalDate.class),
                rs.getBoolean(8), rs.getBoolean(9), rs.getString(10), rs.getString(11)),
                topic.name(), asOfDate, asOfDate,
                asOfDate, asOfDate, product, institution);
        return matches(reviews, ready, topic);
    }

    private Map<UUID, Match> matches(List<Review> reviews,
                                     List<RetrievalCorpusSnapshotService.EligibleChunk> ready,
                                     FinancialTopic topic) {
        Map<UUID, RetrievalCorpusSnapshotService.EligibleChunk> byChunk = new HashMap<>();
        ready.forEach(chunk -> byChunk.put(chunk.sourceChunkId(), chunk));
        Map<UUID, Match> matches = new HashMap<>();
        Set<UUID> ambiguous = new HashSet<>();
        for (Review review : reviews) {
            List<Link> links = jdbc.query("""
                    SELECT source_chunk_id, evidence_role FROM source_applicability_chunk
                    WHERE review_id = ? AND source_document_id = ?
                    """, (rs, n) -> new Link((UUID) rs.getObject(1), rs.getString(2)),
                    review.id(), review.documentId());
            if (links.stream().noneMatch(link -> "SUPPORT".equals(link.role()))) continue;
            if (review.conditionRequired() && links.stream().noneMatch(link -> "CONDITION".equals(link.role()))) continue;
            if (review.exceptionRequired() && links.stream().noneMatch(link -> "EXCEPTION".equals(link.role()))) continue;
            // Every linked condition and exception must be approved, indexed in the current
            // READY generation, located and attached to this document.
            if (links.stream().anyMatch(link -> {
                var chunk = byChunk.get(link.chunkId());
                return chunk == null || !review.documentId().equals(chunk.sourceDocumentId())
                        || chunk.locator() == null || chunk.locator().isBlank();
            })) continue;
            List<String> conditions = new ArrayList<>();
            List<String> exceptions = new ArrayList<>();
            for (Link link : links) {
                String locator = byChunk.get(link.chunkId()).locator();
                if ("CONDITION".equals(link.role())) conditions.add(locator);
                if ("EXCEPTION".equals(link.role())) exceptions.add(locator);
            }
            for (Link link : links) {
                if (!"SUPPORT".equals(link.role())) continue;
                if (topic != null && !topicSupportAllowed(topic, review, byChunk.get(link.chunkId()), links))
                    continue;
                Match match = new Match(review.id(), review.scopeKind(), review.institution(),
                        review.product(), review.start(), review.end(),
                        List.copyOf(conditions), List.copyOf(exceptions));
                if (matches.putIfAbsent(link.chunkId(), match) != null) ambiguous.add(link.chunkId());
            }
        }
        ambiguous.forEach(matches::remove);
        return Map.copyOf(matches);
    }

    private static boolean topicSupportAllowed(FinancialTopic topic, Review review,
            RetrievalCorpusSnapshotService.EligibleChunk chunk, List<Link> links) {
        if (!VerifiedPdfMetadata.DEPOSIT_PDF_URL.equals(review.canonicalUrl())) return true;
        if (topic != FinancialTopic.DEPOSITS_ACCOUNTS
                || !VerifiedPdfMetadata.DEPOSIT_RAW_SHA256.equals(review.rawHash())) return false;
        if (chunk == null || chunk.parentSection() == null || chunk.body() == null) return false;
        int question = -1;
        for (int allowed : List.of(1, 2, 3, 5, 6, 7, 8, 9, 10)) {
            if (chunk.parentSection().startsWith(allowed + ". ")
                    && chunk.body().startsWith(allowed + ". ")) question = allowed;
        }
        if (question < 0) return false; // Q4 and the preamble are outside the reviewed candidate scope.
        List<String> required = switch (question) {
            case 2 -> List.of("보호상품으로 운용되는 경우", "주식", "펀드");
            case 6 -> List.of("이자", "약정이율", "중앙회가 정하는 이율");
            case 10 -> List.of("보호상품으로 운용되는 금액", "사고보험금", "미지급된", "※ (예)");
            default -> List.of();
        };
        if (required.stream().anyMatch(text -> !chunk.body().contains(text))) return false;
        return required.isEmpty() || (review.conditionRequired()
                && links.stream().anyMatch(link -> "CONDITION".equals(link.role())
                && link.chunkId().equals(chunk.sourceChunkId())));
    }

    public record Match(UUID reviewId, String scopeKind, String institution, String product,
                        LocalDate start, LocalDate end, List<String> conditionLocators,
                        List<String> exceptionLocators) {}
    private record Review(UUID id, UUID documentId, String scopeKind, String institution,
                          String product, LocalDate start, LocalDate end,
                          boolean conditionRequired, boolean exceptionRequired,
                          String canonicalUrl, String rawHash) {}
    private record Link(UUID chunkId, String role) {}
}
