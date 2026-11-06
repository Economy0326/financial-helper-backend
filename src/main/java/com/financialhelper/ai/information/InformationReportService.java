package com.financialhelper.ai.information;

import com.financialhelper.retrieval.ConsultationOfficialInformationRequest;
import com.financialhelper.retrieval.ConsultationOfficialInformationResponse;
import com.financialhelper.retrieval.ConsultationOfficialInformationService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

@Service
@ConditionalOnProperty(prefix = "app.ai", name = "enabled", havingValue = "true")
public class InformationReportService {
    private final ConsultationOfficialInformationService search;
    private final InformationConfirmedFactsService confirmed;
    private final InformationEvidenceSnapshotService snapshots;
    private final InformationExplanationComposer explanation;
    private final InformationReportPersistenceService persistence;
    private final JsonMapper mapper;

    public InformationReportService(ConsultationOfficialInformationService search,
            InformationConfirmedFactsService confirmed, InformationEvidenceSnapshotService snapshots,
            InformationExplanationComposer explanation, InformationReportPersistenceService persistence,
            JsonMapper mapper) {
        this.search = search;
        this.confirmed = confirmed;
        this.snapshots = snapshots;
        this.explanation = explanation;
        this.persistence = persistence;
        this.mapper = mapper;
    }

    public InformationReportResponse prepare(UUID id, String token, long expectedCaseInputRevision) {
        // Capture explicit input in a short read-only transaction before remote retrieval.
        var currentFacts = confirmed.current(id, token);
        ConsultationOfficialInformationResponse found = search.searchUsingConfirmed(id, token,
                new ConsultationOfficialInformationRequest(expectedCaseInputRevision), currentFacts);
        if ("STALE".equals(found.status())) return unavailable(id, "STALE", "FACT_REVISION_CHANGED");
        if ("TEMPORARILY_UNAVAILABLE".equals(found.status())) {
            return unavailable(id, "TEMPORARILY_UNAVAILABLE", "RETRIEVAL_UNAVAILABLE");
        }
        InformationEvidenceSnapshot snapshot = snapshots.assemble(id, token, found, currentFacts);
        if (snapshot.evidence().isEmpty()) {
            return new InformationReportResponse(id, "INSUFFICIENT_EVIDENCE", "NO_APPROVED_EVIDENCE",
                    null, snapshot, List.of(), List.of());
        }
        var result = explanation.compose(snapshot);
        // Revalidation and the insert occur in one short DB transaction, after search and AI finish.
        InformationReport saved = persistence.save(id, token, snapshot, found, currentFacts,
                result.selected(), result.explanations());
        return response(saved, snapshot, result.selected(), result.explanations());
    }

    public InformationReportResponse get(UUID id, String token) {
        var stored = persistence.latest(id, token);
        if (stored.isEmpty()) return unavailable(id, "NOT_FOUND", "REPORT_MISSING");
        InformationReport row = stored.get();
        try {
            var old = mapper.readValue(row.getSnapshotJson(), InformationEvidenceSnapshot.class);
            // A missing-result report has no reviewed source identity to prove continuing absence.
            if (old.evidence().isEmpty()) return unavailable(id, "STALE", "RECHECK_REQUIRED");
            var candidates = mapper.readValue(row.getCandidatesJson(), ConsultationOfficialInformationResponse.class);
            var selected = Arrays.asList(mapper.readValue(row.getSelectedEvidenceJson(),
                    InformationEvidenceSnapshot.Evidence[].class));
            if (selected.isEmpty()) return unavailable(id, "STALE", "EVIDENCE_CHANGED");
            List<InformationExplanation> explanations;
            if (row.getExplanationsJson() == null) {
                // Reports created before V40 remain readable, with a verified extractive presentation.
                explanations = explanation.render(selected, "LEGACY_EXTRACTIVE");
            } else {
                explanations = Arrays.asList(mapper.readValue(row.getExplanationsJson(),
                        InformationExplanation[].class));
                if (explanations.size() != selected.size() || explanations.isEmpty()
                        || !explanations.equals(explanation.render(selected, explanations.getFirst().mode()))) {
                    return unavailable(id, "STALE", "EVIDENCE_CHANGED");
                }
            }
            var currentFacts = confirmed.current(id, token);
            var current = snapshots.assemble(id, token, candidates, currentFacts);
            if (!old.equals(current) || !current.evidence().containsAll(selected)) {
                return unavailable(id, "STALE", "EVIDENCE_CHANGED");
            }
            return response(row, old, selected, explanations);
        } catch (JacksonException | IllegalStateException exception) {
            return unavailable(id, "STALE", "EVIDENCE_CHANGED");
        }
    }

    private static InformationReportResponse response(InformationReport row,
            InformationEvidenceSnapshot snapshot, List<InformationEvidenceSnapshot.Evidence> selected,
            List<InformationExplanation> explanations) {
        String status = snapshot.evidence().isEmpty() ? "INSUFFICIENT_EVIDENCE" : "READY_CANDIDATES";
        return new InformationReportResponse(snapshot.consultationId(), status,
                snapshot.evidence().isEmpty() ? "NO_APPROVED_EVIDENCE" : "DOCUMENT_CANDIDATE_ONLY",
                row.getGeneratedAt(), snapshot, selected, explanations);
    }

    private static InformationReportResponse unavailable(UUID id, String status, String reason) {
        return new InformationReportResponse(id, status, reason, null, null, List.of(), List.of());
    }
}
