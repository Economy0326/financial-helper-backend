package com.financialhelper.ai.information;

import com.financialhelper.retrieval.ConsultationOfficialInformationRequest;
import com.financialhelper.retrieval.ConsultationOfficialInformationResponse;
import com.financialhelper.retrieval.ConsultationOfficialInformationService;
import com.financialhelper.retrieval.GeneralTopicInformationService;
import com.financialhelper.retrieval.GeneralTopicInformationRequest;
import com.financialhelper.retrieval.GeneralTopicInformationResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.time.LocalDate;

@Service
@ConditionalOnExpression("${app.ai.enabled:false} or ${app.information-report.enabled:false}")
public class InformationReportService {
    private final ConsultationOfficialInformationService search;
    private final InformationConfirmedFactsService confirmed;
    private final InformationEvidenceSnapshotService snapshots;
    private final InformationExplanationComposer explanation;
    private final InformationReportPersistenceService persistence;
    private final GeneralTopicInformationService topicSearch;
    private final GeneralTopicEvidenceSnapshotService topicSnapshots;
    private final JsonMapper mapper;

    public InformationReportService(ConsultationOfficialInformationService search,
            InformationConfirmedFactsService confirmed, InformationEvidenceSnapshotService snapshots,
            InformationExplanationComposer explanation, InformationReportPersistenceService persistence,
            JsonMapper mapper) {
        this(search, confirmed, snapshots, explanation, persistence, mapper, null, null);
    }

    @Autowired
    public InformationReportService(ConsultationOfficialInformationService search,
            InformationConfirmedFactsService confirmed, InformationEvidenceSnapshotService snapshots,
            InformationExplanationComposer explanation, InformationReportPersistenceService persistence,
            JsonMapper mapper, GeneralTopicInformationService topicSearch,
            GeneralTopicEvidenceSnapshotService topicSnapshots) {
        this.search = search;
        this.confirmed = confirmed;
        this.snapshots = snapshots;
        this.explanation = explanation;
        this.persistence = persistence;
        this.mapper = mapper;
        this.topicSearch = topicSearch;
        this.topicSnapshots = topicSnapshots;
    }

    public InformationReportResponse prepare(UUID id, String token, long expectedCaseInputRevision) {
        return prepare(id, token, expectedCaseInputRevision, null, null);
    }

    public InformationReportResponse prepare(UUID id, String token, long expectedCaseInputRevision,
            GeneralTopicInformationRequest.TimeScope timeScope, LocalDate asOfDate) {
        if (topicSearch != null && topicSearch.confirmedTopic(id, token) != null) {
            return prepareTopic(id, token, expectedCaseInputRevision, timeScope, asOfDate);
        }
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

    private InformationReportResponse prepareTopic(UUID id, String token, long expectedCaseInputRevision,
            GeneralTopicInformationRequest.TimeScope timeScope, LocalDate asOfDate) {
        if (timeScope == null) return unavailable(id, "NEEDS_CLARIFICATION", "REFERENCE_DATE_SCOPE_REQUIRED");
        if (timeScope == GeneralTopicInformationRequest.TimeScope.HISTORICAL) {
            return unavailable(id, "NEEDS_CLARIFICATION", "HISTORICAL_APPLICABILITY_REVIEW_REQUIRED");
        }
        GeneralTopicInformationResponse found = topicSearch.search(id, token,
                new GeneralTopicInformationRequest(expectedCaseInputRevision, timeScope, asOfDate));
        if (!"READY_CANDIDATES".equals(found.status())) {
            return unavailable(id, found.status(), found.coverageGaps().isEmpty()
                    ? "NO_APPROVED_TOPIC_EVIDENCE" : found.coverageGaps().getFirst());
        }
        try {
            InformationEvidenceSnapshot snapshot = topicSnapshots.assemble(id, token, found);
            var result = explanation.compose(snapshot);
            InformationReport saved = persistence.saveTopic(id, token, snapshot, found,
                    result.selected(), result.explanations());
            return response(saved, snapshot, result.selected(), result.explanations());
        } catch (IllegalStateException exception) {
            return unavailable(id, "STALE", "EVIDENCE_CHANGED");
        }
    }

    public InformationReportResponse get(UUID id, String token) {
        var stored = persistence.latest(id, token);
        if (stored.isEmpty()) return unavailable(id, "NOT_FOUND", "REPORT_MISSING");
        InformationReport row = stored.get();
        try {
            var old = mapper.readValue(row.getSnapshotJson(), InformationEvidenceSnapshot.class);
            // A missing-result report has no reviewed source identity to prove continuing absence.
            if (old.evidence().isEmpty()) return unavailable(id, "STALE", "RECHECK_REQUIRED");
            if (old.financialTopic() != null) return getTopic(id, token, row, old);
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

    private InformationReportResponse getTopic(UUID id, String token, InformationReport row,
            InformationEvidenceSnapshot old) throws JacksonException {
        if (topicSnapshots == null) return unavailable(id, "STALE", "EVIDENCE_CHANGED");
        var candidates = mapper.readValue(row.getCandidatesJson(), GeneralTopicInformationResponse.class);
        var selected = Arrays.asList(mapper.readValue(row.getSelectedEvidenceJson(),
                InformationEvidenceSnapshot.Evidence[].class));
        if (selected.isEmpty() || row.getExplanationsJson() == null)
            return unavailable(id, "STALE", "EVIDENCE_CHANGED");
        var explanations = Arrays.asList(mapper.readValue(row.getExplanationsJson(),
                InformationExplanation[].class));
        if (explanations.isEmpty() || explanations.size() != selected.size() || !explanations.equals(
                explanation.render(selected, explanations.getFirst().mode())))
            return unavailable(id, "STALE", "EVIDENCE_CHANGED");
        var current = topicSnapshots.assemble(id, token, candidates);
        if (!old.equals(current) || !current.evidence().containsAll(selected))
            return unavailable(id, "STALE", "EVIDENCE_CHANGED");
        return response(row, old, selected, explanations);
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
