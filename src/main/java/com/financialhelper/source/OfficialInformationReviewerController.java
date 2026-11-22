package com.financialhelper.source;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import com.financialhelper.retrieval.OfficialInformationReadyService;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;

/** Local human-review API for the six explicitly registered INFORMATION sources. */
@RestController
@Profile({"official-demo", "official-local-demo", "test"})
@RequestMapping("/api/v1/source-review/information/{sourceKey}")
public class OfficialInformationReviewerController {
    private final OfficialInformationReviewerService service;
    private final OfficialInformationReadyService ready;
    private final ObjectProvider<SourceUrlVerificationRecordService> verificationRecords;

    public OfficialInformationReviewerController(OfficialInformationReviewerService service,
            OfficialInformationReadyService ready, ObjectProvider<SourceUrlVerificationRecordService> verificationRecords) {
        this.service = service;
        this.ready = ready;
        this.verificationRecords = verificationRecords;
    }

    @GetMapping("/guidance")
    public OfficialInformationClaimSelector.ReviewGuide guidance(@PathVariable String sourceKey) {
        return service.guidance(sourceKey);
    }

    @PostMapping("/ingest")
    public SourceIngestionResult ingest(@PathVariable String sourceKey) {
        return service.ingest(sourceKey);
    }

    @GetMapping("/document")
    public OfficialInformationReviewerService.DocumentView document(@PathVariable String sourceKey) {
        return service.document(sourceKey);
    }

    @PostMapping("/extract")
    public List<OfficialInformationReviewerService.Candidate> extract(@PathVariable String sourceKey,
            @RequestParam UUID documentId) {
        return service.extract(sourceKey, documentId);
    }

    @GetMapping("/candidates")
    public List<OfficialInformationReviewerService.Candidate> candidates(@PathVariable String sourceKey,
            @RequestParam UUID documentId) {
        return service.candidates(sourceKey, documentId);
    }

    @GetMapping("/reviews")
    public List<OfficialInformationReviewerService.ReviewState> reviews(@PathVariable String sourceKey,
            @RequestParam UUID documentId) {
        return service.reviews(sourceKey, documentId);
    }

    @GetMapping("/audit")
    public List<OfficialInformationReviewerService.DecisionAudit> audit(@PathVariable String sourceKey,
            @RequestParam UUID documentId) {
        return service.audit(sourceKey, documentId);
    }

    @PostMapping("/prepare")
    public List<OfficialInformationReviewerService.ReviewState> prepare(@PathVariable String sourceKey,
            @Valid @RequestBody PrepareRequest request) {
        return service.prepare(sourceKey, request.documentId(), request.expectedRawSha256(),
                request.applicabilityStartDate(), request.applicabilityEndDate(),
                request.temporalPolicy(), request.temporalVerifiedOn(), request.temporalValidUntil(),
                request.claimScopes());
    }

    @PostMapping("/{reviewId}/decision")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void decide(@PathVariable String sourceKey, @PathVariable UUID reviewId,
            @Valid @RequestBody DecisionRequest request) {
        if (request.decision() == SourceReviewerService.Decision.APPROVE && verificationRecords.getIfAvailable() != null) {
            throw new SourceReviewException(HttpStatus.CONFLICT, "SOURCE_REVIEW_USE_CANDIDATE_DECISION");
        }
        service.decide(sourceKey, reviewId, request.expectedRawSha256(),
                request.conditionsReviewed(), request.exceptionsReviewed(), request.decision(),
                request.correctionCode(), request.evidenceLocator());
    }

    @PostMapping("/candidate-decision")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void decideCandidate(@PathVariable String sourceKey, @Valid @RequestBody CandidateDecisionRequest request) {
        SourceUrlVerificationRecordService records = verificationRecords.getIfAvailable();
        if (records != null && request.decision() == SourceReviewerService.Decision.APPROVE) {
            service.requireCandidate(sourceKey, request.documentId(), request.chunkId(), request.expectedRawSha256());
        }
        UUID verificationId = records != null && request.decision() == SourceReviewerService.Decision.APPROVE
                ? records.verifyAndRecord(sourceKey, request.documentId()) : null;
        service.decideCandidateWithEvidence(sourceKey, request.documentId(), request.chunkId(), request.expectedRawSha256(),
                request.applicabilityStartDate(), request.applicabilityEndDate(), request.temporalPolicy(),
                request.temporalVerifiedOn(), request.temporalValidUntil(), request.claimScopes(),
                request.conditionsReviewed(), request.exceptionsReviewed(), request.decision(),
                request.correctionCode(), request.evidenceLocator(), verificationId);
    }

    @PostMapping("/{reviewId}/revoke")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revoke(@PathVariable String sourceKey, @PathVariable UUID reviewId) {
        service.revoke(sourceKey, reviewId);
    }

    @PostMapping("/ready")
    public UUID ready(@PathVariable String sourceKey, @RequestParam UUID documentId) {
        return ready.build(sourceKey, documentId);
    }

    @PostMapping("/ready-candidate")
    public UUID readyCandidate(@PathVariable String sourceKey, @Valid @RequestBody MixedReadyRequest request) {
        return ready.prepareMixedCandidate(sourceKey, request.documentId(), request.genericDocumentIds());
    }

    @PostMapping("/ready-candidate/{generationId}/build")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void buildReadyCandidate(@PathVariable String sourceKey, @PathVariable UUID generationId) {
        ready.buildMixedCandidate(sourceKey, generationId);
    }

    @PostMapping("/ready-candidate/{generationId}/activate")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void activateReadyCandidate(@PathVariable String sourceKey, @PathVariable UUID generationId) {
        ready.activateMixedCandidate(sourceKey, generationId);
    }

    @PostMapping("/ready-candidate/{generationId}/cancel")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void cancelReadyCandidate(@PathVariable String sourceKey, @PathVariable UUID generationId) {
        ready.cancelMixedCandidate(sourceKey, generationId);
    }

    public record MixedReadyRequest(@NotNull UUID documentId, @NotNull List<UUID> genericDocumentIds) {}

    public record PrepareRequest(@NotNull UUID documentId,
                                 @NotNull @Pattern(regexp = "[a-f0-9]{64}") String expectedRawSha256,
                                 LocalDate applicabilityStartDate,
                                 LocalDate applicabilityEndDate,
                                 TemporalEvidencePolicy temporalPolicy,
                                 LocalDate temporalVerifiedOn,
                                 LocalDate temporalValidUntil,
                                 List<OfficialInformationReviewerService.ClaimScope> claimScopes) {}

    public record DecisionRequest(@NotNull @Pattern(regexp = "[a-f0-9]{64}") String expectedRawSha256,
                                  boolean conditionsReviewed, boolean exceptionsReviewed,
                                  @NotNull SourceReviewerService.Decision decision,
                                  OfficialInformationReviewerService.CorrectionCode correctionCode,
                                  String evidenceLocator) {}

    public record CandidateDecisionRequest(@NotNull UUID documentId, @NotNull UUID chunkId,
                                  @NotNull @Pattern(regexp = "[a-f0-9]{64}") String expectedRawSha256,
                                  LocalDate applicabilityStartDate, LocalDate applicabilityEndDate,
                                  TemporalEvidencePolicy temporalPolicy, LocalDate temporalVerifiedOn,
                                  LocalDate temporalValidUntil, List<OfficialInformationReviewerService.ClaimScope> claimScopes,
                                  boolean conditionsReviewed, boolean exceptionsReviewed,
                                  @NotNull SourceReviewerService.Decision decision,
                                  OfficialInformationReviewerService.CorrectionCode correctionCode,
                                  String evidenceLocator) {}
}
