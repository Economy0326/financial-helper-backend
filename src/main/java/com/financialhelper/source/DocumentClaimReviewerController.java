package com.financialhelper.source;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.UUID;

@RestController
@Profile({"official-demo", "official-local-demo", "test"})
@RequestMapping("/api/v1/source-review/document-claims")
public class DocumentClaimReviewerController {
    private final DocumentClaimReviewerService service;
    private final TextOnlySourceRegistrationService textRegistration;
    private final com.financialhelper.retrieval.DocumentClaimBatchReadyService batchReady;
    private final DocumentChangeService documentChanges;
    public DocumentClaimReviewerController(DocumentClaimReviewerService service, TextOnlySourceRegistrationService textRegistration,
            com.financialhelper.retrieval.DocumentClaimBatchReadyService batchReady, DocumentChangeService documentChanges) {
        this.service = service; this.textRegistration = textRegistration;
        this.batchReady = batchReady;
        this.documentChanges = documentChanges;
    }

    @GetMapping("/{documentId}/change-status")
    public DocumentChangeService.Observation changeStatus(@PathVariable UUID documentId) { return documentChanges.status(documentId); }

    @PostMapping("/{documentId}/change-check")
    public DocumentChangeService.Observation checkChange(@PathVariable UUID documentId) { return documentChanges.check(documentId); }

    @PostMapping("/batch-ready")
    public UUID prepareBatch(@Valid @RequestBody BatchRequest request) {
        UUID existing = batchReady.unchangedGeneration(request.documentIds(), request.versions());
        return existing != null ? existing : batchReady.prepare(request.documentIds(), request.versions());
    }
    @PostMapping("/batch-ready/unchanged")
    public boolean unchangedBatch(@Valid @RequestBody BatchRequest request) { return batchReady.unchanged(request.documentIds(), request.versions()); }
    @PostMapping("/batch-ready/{generationId}/build")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void buildBatch(@PathVariable UUID generationId) { batchReady.build(generationId); }
    @PostMapping("/batch-ready/{generationId}/activate")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void activateBatch(@PathVariable UUID generationId) { batchReady.activate(generationId); }
    public record BatchRequest(@NotNull @Size(min=1, max=100) List<UUID> documentIds, java.util.Map<UUID, String> versions) {}

    @PostMapping("/text-registration")
    public TextOnlySourceRegistrationService.Result registerText(@Valid @RequestBody TextRegistrationRequest request) {
        return textRegistration.register(request.registryId(), request.html(), request.title(), request.publishedAt(),
                request.basisUrl(), request.reason(), request.privacyReviewed(), request.thirdPartyReviewed(), request.storageApproved());
    }

    @PostMapping("/registered-source")
    public TextOnlySourceRegistrationService.Result registerSource(@Valid @RequestBody RegisteredSourceRequest request) {
        var fetched = textRegistration.fetchRegistered(request.registryId(), request.storageApproved(),
                request.privacyReviewed(), request.thirdPartyReviewed());
        if (Boolean.TRUE.equals(request.requireTypeOne()) && !TextOnlySourceRegistrationService.hasTypeOneTextLicense(
                fetched.snapshot().officialDomain(), org.jsoup.Jsoup.parse(fetched.html())))
            throw new SourceReviewException(HttpStatus.CONFLICT, "SOURCE_TEXT_LICENSE_UNVERIFIED");
        if (Boolean.TRUE.equals(request.requireTypeOne()) && (request.expectedPreviewSha256() == null
                || !request.expectedPreviewSha256().equals(TextOnlySourceRegistrationService.policyPreviewHash(fetched))))
            throw new SourceReviewException(HttpStatus.CONFLICT, "SOURCE_TEXT_PREVIEW_CHANGED");
        return textRegistration.registerFetched(fetched, request.basisUrl(), request.reason(),
                request.privacyReviewed(), request.thirdPartyReviewed(), request.storageApproved());
    }

    @PostMapping("/registry-candidates")
    public TextOnlySourceRegistrationService.RegistryCandidate registerCandidate(@Valid @RequestBody RegistryCandidateRequest request) {
        return textRegistration.registerCandidate(request.domain(), request.url());
    }

    @PostMapping("/registry-candidates/{registryId}/preview")
    public TextOnlySourceRegistrationService.CandidateMetadata previewCandidate(@PathVariable UUID registryId,
                                                                                 jakarta.servlet.http.HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        return textRegistration.previewCandidate(registryId);
    }

    @PostMapping("/table-preview")
    public StructuredTablePreview.Result previewTables(@Valid @RequestBody TablePreviewRequest request) {
        return textRegistration.previewTables(request.registryId(), request.html());
    }

    @GetMapping
    public List<DocumentClaimReviewerService.DocumentSummary> documents() { return service.documents(); }

    @GetMapping("/{documentId}/audit")
    public List<DocumentClaimReviewerService.ClaimAudit> audit(@PathVariable UUID documentId, @RequestParam String expectedRawSha256) {
        return service.audit(documentId, expectedRawSha256);
    }

    @GetMapping("/{documentId}/version-comparison")
    public DocumentClaimReviewerService.VersionComparison versionComparison(@PathVariable UUID documentId, @RequestParam String expectedRawSha256) {
        return service.versionComparison(documentId, expectedRawSha256);
    }

    @GetMapping("/{documentId}/generations")
    public List<DocumentClaimReviewerService.GenerationSummary> generations(@PathVariable UUID documentId, @RequestParam String expectedRawSha256) {
        return service.generations(documentId, expectedRawSha256);
    }

    @GetMapping("/{documentId}/usage-review")
    public List<DocumentClaimReviewerService.UsageReview> usageReviews(@PathVariable UUID documentId, @RequestParam String expectedRawSha256) {
        return service.usageReviews(documentId, expectedRawSha256);
    }
    @PostMapping("/{documentId}/usage-review")
    public DocumentClaimReviewerService.UsageReview decideUsage(@PathVariable UUID documentId, @Valid @RequestBody UsageRequest request) {
        return service.decideUsage(documentId, request.expectedRawSha256(), request.documentVersion(), request.basisUrl(),
                request.scopes(), request.privacyReviewed(), request.thirdPartyReviewed(), request.decision(), request.reason(), request.expectedPreviousReviewId());
    }

    @PostMapping("/{documentId}/prepare")
    public List<DocumentClaimReviewerService.Claim> prepare(@PathVariable UUID documentId, @Valid @RequestBody VersionRequest request) {
        return service.prepare(documentId, request.expectedRawSha256());
    }
    @PostMapping("/{documentId}/prepare-latest")
    public List<DocumentClaimReviewerService.Claim> prepareLatest(@PathVariable UUID documentId, @Valid @RequestBody VersionRequest request) {
        return service.prepareLatest(documentId, request.expectedRawSha256());
    }
    @GetMapping("/{documentId}")
    public List<DocumentClaimReviewerService.Claim> claims(@PathVariable UUID documentId, @RequestParam String expectedRawSha256) {
        return service.claims(documentId, expectedRawSha256);
    }
    @PostMapping("/{documentId}/{candidateId}/decision")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void decide(@PathVariable UUID documentId, @PathVariable String candidateId, @Valid @RequestBody DecisionRequest request) {
        service.decide(documentId, candidateId, request.expectedRawSha256(), request.decision(), request.reason());
    }
    @PostMapping("/{documentId}/{candidateId}/reconsider")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void reconsider(@PathVariable UUID documentId, @PathVariable String candidateId, @Valid @RequestBody VersionRequest request) {
        service.reconsiderRejected(documentId, candidateId, request.expectedRawSha256());
    }
    @PostMapping("/{documentId}/{candidateId}/withdraw")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void withdraw(@PathVariable UUID documentId, @PathVariable String candidateId, @Valid @RequestBody WithdrawalRequest request) {
        service.withdrawApproval(documentId, candidateId, request.expectedRawSha256(), request.reason());
    }
    public record VersionRequest(@NotNull @Pattern(regexp="[a-f0-9]{64}") String expectedRawSha256) {}
    public record WithdrawalRequest(@NotNull @Pattern(regexp="[a-f0-9]{64}") String expectedRawSha256, @Size(max=978) String reason) {}
    public record TablePreviewRequest(@NotNull UUID registryId, @NotNull @Size(max=2000000) String html) {}
    public record UsageRequest(@NotNull @Pattern(regexp="[a-f0-9]{64}") String expectedRawSha256,
                               int documentVersion, @NotNull String basisUrl, @NotNull List<String> scopes,
                               boolean privacyReviewed, boolean thirdPartyReviewed, @NotNull String decision,
                               @Size(max=1000) String reason, UUID expectedPreviousReviewId) {}
    public record DecisionRequest(@NotNull @Pattern(regexp="[a-f0-9]{64}") String expectedRawSha256,
                                  @NotNull SourceReviewerService.Decision decision, @Size(max=1000) String reason) {}
    public record TextRegistrationRequest(@NotNull UUID registryId, @NotNull @Size(max=2000000) String html,
            @NotNull @Size(max=500) String title, java.time.LocalDate publishedAt, @NotNull String basisUrl,
            @NotNull String reason, boolean privacyReviewed, boolean thirdPartyReviewed, boolean storageApproved) {}
    public record RegisteredSourceRequest(@NotNull UUID registryId, @NotNull String basisUrl,
            @NotNull String reason, boolean privacyReviewed, boolean thirdPartyReviewed, boolean storageApproved,
            Boolean requireTypeOne, String expectedPreviewSha256) {}
    public record RegistryCandidateRequest(@NotNull @Size(max=255) String domain, @NotNull @Size(max=2000) String url) {}
}
