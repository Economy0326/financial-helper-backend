package com.financialhelper.retrieval;

import com.financialhelper.source.SourceReviewerService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.util.List;
import java.util.UUID;

@RestController
public class DocumentClaimFaqController {
    private final JdbcTemplate jdbc;
    private final SourceReviewerService reviewers;
    private final DocumentClaimSnapshotService snapshots;
    public DocumentClaimFaqController(JdbcTemplate jdbc, SourceReviewerService reviewers, DocumentClaimSnapshotService snapshots) {
        this.jdbc = jdbc; this.reviewers = reviewers; this.snapshots = snapshots;
    }
    private List<Entry> entries(String where, Object... args) {
        return jdbc.query("SELECT f.id,f.candidate_id,f.question,f.display_order,f.published FROM document_claim_faq f " + where + " ORDER BY f.display_order,f.created_at,f.id",
                (rs,n) -> new Entry(rs.getObject(1,UUID.class),rs.getString(2),rs.getString(3),rs.getInt(4),rs.getBoolean(5),List.of(),List.of()), args).stream().map(e -> new Entry(e.id(), e.claimId(), e.question(), e.displayOrder(), e.published(), List.of(), claimIds(e.id()))).toList();
    }
    private List<String> claimIds(UUID id) {
        return jdbc.query("SELECT candidate_id FROM document_claim_faq_evidence WHERE faq_id=? ORDER BY position", (rs,n) -> rs.getString(1), id);
    }
    @GetMapping("/api/v1/faq")
    @Transactional
    public List<Entry> published() {
        return entries("WHERE f.published=TRUE").stream().map(e -> {
            var evidence = e.claimIds().stream().map(snapshots::currentClaimEvidence).toList();
            return new Entry(e.id(), null, e.question(), e.displayOrder(), true,
                    evidence.stream().anyMatch(List::isEmpty) ? List.of() : evidence.stream().flatMap(List::stream).toList(), List.of());
        }).filter(e -> !e.evidence().isEmpty()).toList();
    }
    @GetMapping("/api/v1/source-review/faq")
    public List<Entry> manage(@RequestParam UUID documentId) {
        reviewers.requireReviewer();
        return entries("JOIN source_document_claim c ON c.candidate_id=f.candidate_id WHERE c.source_document_id=?", documentId);
    }
    @GetMapping("/api/v1/source-review/faq/eligible-claims")
    @Transactional
    public List<String> eligibleClaims(@RequestParam UUID documentId) {
        reviewers.requireReviewer();
        return jdbc.queryForList("SELECT candidate_id FROM source_document_claim WHERE source_document_id=? ORDER BY candidate_id", String.class, documentId)
                .stream().filter(id -> !snapshots.currentClaimEvidence(id).isEmpty()).toList();
    }
    @PostMapping("/api/v1/source-review/faq")
    @Transactional
    public Entry create(@Valid @RequestBody Request request) {
        UUID reviewer = reviewers.requireReviewer();
        if (!request.meaningConfirmed()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "질문과 근거의 의미를 확인해 주세요.");
        var ids = validatedClaims(request, true);
        UUID id = UUID.randomUUID();
        Integer order = request.displayOrder();
        if (order == null) {
            jdbc.execute("SELECT pg_advisory_xact_lock(704021893)");
            Long next = jdbc.queryForObject("SELECT COALESCE(MAX(display_order)::bigint + 1, 0) FROM document_claim_faq", Long.class);
            if (next == null || next > Integer.MAX_VALUE) throw new ResponseStatusException(HttpStatus.CONFLICT, "표시 순위를 확인해 주세요.");
            order = next.intValue();
        }
        jdbc.update("INSERT INTO document_claim_faq(id,candidate_id,question,display_order,published,reviewer_account_id) VALUES (?,?,?,?,?,?)",
                id, ids.getFirst(), request.question().trim(), order, request.published(), reviewer);
        saveClaims(id, ids);
        return entries("WHERE f.id=?", id).getFirst();
    }
    @PutMapping("/api/v1/source-review/faq/{id}")
    @Transactional
    public Entry update(@PathVariable UUID id, @Valid @RequestBody Request request) {
        UUID reviewer = reviewers.requireReviewer();
        if (!request.meaningConfirmed()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "질문과 근거의 의미를 확인해 주세요.");
        if (request.displayOrder() == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "표시 순서를 입력해 주세요.");
        var ids = validatedClaims(request, request.published());
        if (jdbc.update("UPDATE document_claim_faq SET candidate_id=?,question=?,display_order=?,published=?,reviewer_account_id=?,updated_at=now() WHERE id=?",
                ids.getFirst(),request.question().trim(),request.displayOrder(),request.published(),reviewer,id) != 1) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        jdbc.update("DELETE FROM document_claim_faq_evidence WHERE faq_id=?", id);
        saveClaims(id, ids);
        return entries("WHERE f.id=?",id).getFirst();
    }
    @DeleteMapping("/api/v1/source-review/faq/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) {
        reviewers.requireReviewer();
        if (jdbc.update("DELETE FROM document_claim_faq WHERE id=?",id) != 1) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    }
    @PostMapping("/api/v1/source-review/faq/{id}/unpublish")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unpublish(@PathVariable UUID id) {
        UUID reviewer = reviewers.requireReviewer();
        if (jdbc.update("UPDATE document_claim_faq SET published=false,reviewer_account_id=?,updated_at=now() WHERE id=?", reviewer, id) != 1)
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    }
    private List<String> validatedClaims(Request request, boolean requireEligible) {
        var ids = request.claimIds() == null || request.claimIds().isEmpty() ? List.of(request.claimId()) : request.claimIds();
        if (ids.size() > 10 || ids.stream().anyMatch(id -> id == null || id.isBlank() || id.length() > 64) || ids.stream().distinct().count() != ids.size())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "근거를 확인해 주세요.");
        if (requireEligible && ids.stream().anyMatch(id -> snapshots.currentClaimEvidence(id).isEmpty()))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "승인 및 서비스 반영된 공개 가능 근거를 선택해 주세요.");
        return List.copyOf(ids);
    }
    private void saveClaims(UUID id, List<String> ids) {
        for (int i=0; i<ids.size(); i++) jdbc.update("INSERT INTO document_claim_faq_evidence(faq_id,candidate_id,position) VALUES (?,?,?)", id, ids.get(i), i);
    }
    public record Request(@NotBlank @Size(max=64) String claimId, @NotBlank @Size(max=300) String question, Integer displayOrder, boolean published, boolean meaningConfirmed, List<String> claimIds) {
        public Request(String claimId, String question, int displayOrder, boolean published, boolean meaningConfirmed) { this(claimId, question, displayOrder, published, meaningConfirmed, null); }
    }
    public record Entry(UUID id, String claimId, String question, int displayOrder, boolean published, List<DocumentClaimSnapshotService.Evidence> evidence, List<String> claimIds) {}
}
