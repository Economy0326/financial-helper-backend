package com.financialhelper.ai.grounded;

import com.financialhelper.ai.AiOutputContractException;
import com.financialhelper.ai.analysis.AnalysisAiResult;
import com.financialhelper.ai.report.ConsultationReportAiResult;
import com.financialhelper.procedure.FinancialActionPlanData;

import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Validates model references against the immutable snapshot and procedure. */
@Component
public class GroundedOutputValidator {
    public void validateAnalysis(
            AnalysisAiResult result,
            AnalysisEvidenceSnapshotData snapshot
    ) {
        if (snapshot == null || result == null) {
            throw new AiOutputContractException("grounded evidence snapshot and analysis result are required");
        }
        validateCitations(result.evidenceCitations, snapshot, true);
    }

    public void validateReport(
            ConsultationReportAiResult result,
            AnalysisEvidenceSnapshotData snapshot
    ) {
        if (snapshot == null || result == null) {
            throw new AiOutputContractException("grounded evidence snapshot and report result are required");
        }
        validateCitations(result.evidenceCitations, snapshot, true);
        FinancialActionPlanData plan = snapshot.actionPlan();
        if (plan == null || plan.status() != com.financialhelper.procedure.PlanStatus.READY) {
            throw new AiOutputContractException("grounded report requires a READY action plan");
        }
        if (result.firstAction == null || result.firstAction.actionId == null
                || result.firstAction.actionId.isBlank()) {
            throw new AiOutputContractException("grounded report first action must reference a procedure action");
        }
        List<FinancialActionPlanData.Action> actions = plan.actions();
        if (actions.isEmpty() || !actions.get(0).actionId().equals(result.firstAction.actionId)) {
            throw new AiOutputContractException("grounded report first action is outside the approved procedure");
        }
        if (!actions.get(0).title().equals(result.firstAction.title)
                || !actions.get(0).description().equals(result.firstAction.description)) {
            throw new AiOutputContractException("grounded report first action differs from the approved procedure");
        }
        if (result.actionSteps == null || result.actionSteps.size() != actions.size()) {
            throw new AiOutputContractException("grounded report action steps must match the approved procedure");
        }
        for (int i = 0; i < actions.size(); i++) {
            FinancialActionPlanData.Action expected = actions.get(i);
            ConsultationReportAiResult.ActionStep actual = result.actionSteps.get(i);
            // Report numbering is a presentation field owned by the
            // backend composition step.  The persisted procedure may carry
            // legacy gaps or duplicate order values, so the public report
            // contract is always the deterministic list position 1..N.
            if (!expected.actionId().equals(actual.actionId)
                    || (i + 1) != actual.order
                    || !expected.title().equals(actual.title)
                    || !expected.description().equals(actual.description)) {
                throw new AiOutputContractException("grounded report action differs from the approved procedure");
            }
        }
        if (result.requiredDocuments == null) {
            throw new AiOutputContractException("grounded report documents are required");
        }
        if (result.requiredDocuments.size() != plan.requiredDocuments().size()) {
            throw new AiOutputContractException("grounded report documents must match the action plan");
        }
        for (int i = 0; i < plan.requiredDocuments().size(); i++) {
            var expected = plan.requiredDocuments().get(i);
            var actual = result.requiredDocuments.get(i);
            if (!expected.documentId().equals(actual.documentId)
                    || !expected.title().equals(actual.name)) {
                throw new AiOutputContractException("grounded report document differs from the action plan");
            }
        }
    }

    private void validateCitations(
            List<GroundedEvidenceCitation> citations,
            AnalysisEvidenceSnapshotData snapshot,
            boolean required
    ) {
        if (citations == null || (required && citations.isEmpty())) {
            throw new AiOutputContractException("grounded output must contain evidence citations");
        }
        Set<String> valid = new HashSet<>(snapshot.evidenceIds());
        valid.addAll(snapshot.lawEvidenceIds());
        Set<String> seen = new HashSet<>();
        for (GroundedEvidenceCitation citation : citations) {
            if (citation == null || citation.evidenceId == null || citation.evidenceId.isBlank()
                    || citation.locator == null || citation.locator.isBlank()
                    || !valid.contains(citation.evidenceId) || !seen.add(citation.evidenceId)) {
                throw new AiOutputContractException("grounded output contains an unknown or duplicate evidence reference");
            }
            boolean locatorMatches = snapshot.sourceEvidence().stream()
                    .filter(item -> item.evidenceId().equals(citation.evidenceId))
                    .map(AnalysisEvidenceSnapshotData.SourceEvidence::locator)
                    .anyMatch(locator -> locator == null || locator.isBlank() || locator.equals(citation.locator));
            if (!locatorMatches) {
                locatorMatches = snapshot.reviewedLawEvidence().stream()
                        .filter(item -> item.evidenceId().equals(citation.evidenceId))
                        .map(AnalysisEvidenceSnapshotData.ReviewedLawEvidence::articleLocator)
                        .anyMatch(citation.locator::equals);
            }
            if (!locatorMatches) {
                throw new AiOutputContractException("grounded output locator does not match evidence");
            }
        }
    }
}
