package com.financialhelper.source;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@RestController
@Profile({"official-demo", "official-local-demo", "test"})
@RequestMapping("/api/v1/source-review/deposit")
public class SourceReviewerController {
    private final SourceReviewerService service;

    public SourceReviewerController(SourceReviewerService service) {
        this.service = service;
    }

    @GetMapping("/sources")
    public List<SourceReviewerService.ReviewableSource> sources() {
        return service.sources();
    }

    @PostMapping("/ingest")
    public SourceIngestionResult ingest() {
        return service.ingestDeposit();
    }

    @GetMapping("/document")
    public SourceReviewerService.DocumentView document() {
        return service.currentDocument();
    }

    @GetMapping("/reviews")
    public List<SourceReviewerService.ReviewState> reviews(@RequestParam UUID documentId) {
        return service.reviews(documentId);
    }

    @GetMapping("/audit")
    public List<SourceReviewerService.DecisionAudit> audit(@RequestParam UUID documentId) {
        return service.audit(documentId);
    }

    @GetMapping("/candidates")
    public List<DepositPdfReviewCandidateSelector.Candidate> candidates(@RequestParam UUID documentId) {
        return service.candidates(documentId);
    }

    @PostMapping("/chunk")
    public List<DepositPdfReviewCandidateSelector.Candidate> chunk(@RequestParam UUID documentId) {
        return service.chunkDeposit(documentId);
    }

    @PostMapping("/prepare")
    public List<SourceReviewerService.PreparedReview> prepare(@Valid @RequestBody PrepareRequest request) {
        return service.prepare(request.documentId(), request.applicabilityStartDate(), request.applicabilityEndDate());
    }

    @PostMapping("/{reviewId}/decision")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void decide(@PathVariable UUID reviewId, @Valid @RequestBody DecisionRequest request) {
        service.decide(reviewId, request.expectedRawSha256(), request.conditionsReviewed(),
                request.exceptionsReviewed(), request.decision());
    }

    @PostMapping("/{reviewId}/revoke")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revoke(@PathVariable UUID reviewId) {
        service.revoke(reviewId);
    }

    @PostMapping("/ready")
    public UUID ready(@RequestParam UUID documentId) {
        return service.buildReady(documentId);
    }

    public record PrepareRequest(@NotNull UUID documentId,
                                 @NotNull LocalDate applicabilityStartDate,
                                 LocalDate applicabilityEndDate) { }

    public record DecisionRequest(
            @NotNull @Pattern(regexp = "[a-f0-9]{64}") String expectedRawSha256,
            boolean conditionsReviewed,
            boolean exceptionsReviewed,
            @NotNull SourceReviewerService.Decision decision) { }
}
