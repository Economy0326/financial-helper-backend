package com.financialhelper.retrieval;

import com.financialhelper.consultation.ConsultationInputPrivacy;
import com.financialhelper.account.AccountSessionService;
import com.financialhelper.account.AccountAuthenticationException;
import com.financialhelper.account.AccountProperties;
import com.financialhelper.account.InputLimitException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/information-evidence-snapshots")
public class DocumentClaimSnapshotController {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(DocumentClaimSnapshotController.class);
    private final OfficialSourceRetrievalService search;
    private final DocumentClaimSnapshotService snapshots;
    private final AccountSessionService accounts;
    private final AccountProperties properties;
    private final DocumentClaimExplanationService explanations;
    private final DocumentAiExplanationQuota quota;
    public DocumentClaimSnapshotController(OfficialSourceRetrievalService search, DocumentClaimSnapshotService snapshots,
                                           AccountSessionService accounts, AccountProperties properties,
                                           DocumentClaimExplanationService explanations, DocumentAiExplanationQuota quota) {
        this.search = search; this.snapshots = snapshots; this.accounts = accounts; this.properties = properties; this.explanations = explanations; this.quota = quota;
    }
    @PostMapping
    public DocumentClaimSnapshotService.Response search(@Valid @RequestBody Request request, HttpServletResponse response) {
        long start = System.nanoTime();
        response.setHeader("Cache-Control", "no-store");
        validateQuestion(request.question());
        var result = search.searchDocumentClaims(request.question());
        if (request.selection() != null && result.status() != RetrievalStatus.TEMPORARILY_UNAVAILABLE) {
            var selection = request.selection();
            String selectedTitle = selection.title(), selectedExcerpt = selection.excerpt();
            var matches = result.status() == RetrievalStatus.NEEDS_CLARIFICATION && selection.title() != null
                    ? result.candidates().stream().filter(candidate -> selectedTitle.equals(candidate.title())
                            && (selectedExcerpt == null || selectedExcerpt.equals(candidate.body()))).toList() : java.util.List.<OfficialEvidenceCandidate>of();
            if (matches.stream().map(OfficialEvidenceCandidate::sourceDocumentId).distinct().count() > 1) matches = java.util.List.of();
            if (!matches.isEmpty() && selection.excerpt() == null) {
                var scoped = new OfficialSearchResponse(result.searchId(), RetrievalStatus.NEEDS_CLARIFICATION, result.degraded(),
                        java.util.List.of(), java.util.List.of(), matches);
                var requested = DocumentClaimRelevance.requestedPurposes(request.question());
                if (selection.purpose() == null && requested.isEmpty()) {
                    var purposeOptions = snapshots.clarificationPurposes(scoped, request.question(), selection.title());
                    if (!"NEEDS_CLARIFICATION".equals(purposeOptions.status()) || purposeOptions.options().size() != 1) return purposeOptions;
                    selection = new Selection(selection.title(), null, purposeOptions.options().getFirst().purpose());
                }
                if (!requested.isEmpty()) {
                    if (selection.purpose() != null && !requested.contains(selection.purpose())
                            && !(requested.contains("방법") && selection.purpose().equals("신청"))) matches = java.util.List.of();
                    matches = matches.stream().filter(candidate -> !DocumentClaimRelevance.purposesCoveredByEvidence(request.question(), candidate.body()).isEmpty()).toList();
                } else {
                    String chosenPurpose = selection.purpose();
                    matches = matches.stream().filter(candidate -> DocumentClaimRelevance.availablePurposes(candidate.body()).contains(chosenPurpose)).toList();
                }
            }
            result = new OfficialSearchResponse(result.searchId(), !matches.isEmpty() ? RetrievalStatus.FOUND : RetrievalStatus.NO_MATCH,
                    result.degraded(), java.util.List.of(), java.util.List.of(), matches);
        }
        long searchDone = System.nanoTime();
        var output = result.status() == RetrievalStatus.NEEDS_CLARIFICATION && !result.candidates().isEmpty()
                ? snapshots.clarification(result, request.question()) : accounts.currentAccountId().isPresent() ? snapshots.save(result, request.question())
                : snapshots.preview(result, request.question());
        log.info("Document response timing ms: search={} snapshot_and_response={} controller_total={}",
                (searchDone - start) / 1_000_000, (System.nanoTime() - searchDone) / 1_000_000,
                (System.nanoTime() - start) / 1_000_000);
        return output;
    }
    @GetMapping("/topics")
    public java.util.List<String> topics(HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        return snapshots.topics();
    }
    private void validateQuestion(String question) {
        if (question.length() > properties.limits().maxSituationCharacters()) {
            throw new InputLimitException("situation");
        }
        ConsultationInputPrivacy.requireSafe(question);
    }
    @GetMapping("/{id}")
    public DocumentClaimSnapshotService.Response get(@PathVariable UUID id) { return snapshots.get(id); }
    @PostMapping("/{id}/explanation")
    public DocumentClaimExplanationService.Result explanation(@PathVariable UUID id, @Valid @RequestBody ExplanationRequest request, HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        if (request.question() != null) validateQuestion(request.question());
        return explanations.explain(id, request.requestId(), request.question());
    }
    @GetMapping("/explanation-quota")
    public ExplanationQuotaResponse explanationQuota(@RequestParam(required = false) UUID snapshotId, HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        var status = quota.status(accounts.currentAccountId().orElseThrow(AccountAuthenticationException::required));
        String reason = explanations.availability(snapshotId);
        return new ExplanationQuotaResponse(status.remaining(), status.limit(), status.resetsAt(), "AVAILABLE".equals(reason), reason);
    }
    @PostMapping("/{id}/report")
    public DocumentClaimSnapshotService.Report createReport(@PathVariable UUID id, @Valid @RequestBody Request request,
                                                             HttpServletResponse response) {
        accounts.currentAccountId().orElseThrow(AccountAuthenticationException::required);
        validateQuestion(request.question());
        response.setHeader("Cache-Control", "no-store");
        return snapshots.createReport(id, request.question());
    }
    @GetMapping("/reports/{id}")
    public DocumentClaimSnapshotService.Report report(@PathVariable UUID id, HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        return snapshots.report(id);
    }
    @GetMapping("/reports")
    public java.util.List<DocumentClaimSnapshotService.ReportSummary> reports(HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        return snapshots.reports();
    }
    @DeleteMapping("/reports/{id}")
    @ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT)
    public void deleteReport(@PathVariable UUID id) { snapshots.deleteReport(id); }
    public record Selection(String title, String excerpt, String purpose) {
        public Selection(String title, String excerpt) { this(title, excerpt, null); }
    }
    public record Request(@NotBlank String question, Selection selection) {
        public Request(String question) { this(question, null); }
    }
    public record ExplanationRequest(@jakarta.validation.constraints.NotNull UUID requestId, String question) {}
    public record ExplanationQuotaResponse(int remaining, int limit, String resetsAt, boolean available, String reason) {}
}
