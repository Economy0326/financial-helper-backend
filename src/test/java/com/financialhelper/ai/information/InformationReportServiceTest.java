package com.financialhelper.ai.information;

import com.financialhelper.retrieval.ConfirmedCaseSnapshotData;
import com.financialhelper.retrieval.ConsultationOfficialInformationResponse;
import com.financialhelper.retrieval.ConsultationOfficialInformationService;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class InformationReportServiceTest {
    private final ConsultationOfficialInformationService search = mock(ConsultationOfficialInformationService.class);
    private final InformationConfirmedFactsService confirmed = mock(InformationConfirmedFactsService.class);
    private final InformationEvidenceSnapshotService snapshots = mock(InformationEvidenceSnapshotService.class);
    private final InformationExplanationService ai = mock(InformationExplanationService.class);
    private final InformationReportPersistenceService persistence = mock(InformationReportPersistenceService.class);
    private final JsonMapper mapper = JsonMapper.builder().build();
    private final InformationReportService service = new InformationReportService(
            search, confirmed, snapshots, ai, persistence, mapper);
    private final UUID id = UUID.randomUUID();

    @Test
    void retrievalFailureDoesNotCallAiOrPersist() {
        when(confirmed.current(id, "session")).thenReturn(facts());
        when(search.searchUsingConfirmed(any(), any(), any(), any())).thenReturn(response("TEMPORARILY_UNAVAILABLE"));
        var result = service.prepare(id, "session", 2);
        assertThat(result.status()).isEqualTo("TEMPORARILY_UNAVAILABLE");
        verifyNoInteractions(ai, persistence);
    }

    @Test
    void staleSearchDoesNotCallAiOrPersist() {
        when(confirmed.current(id, "session")).thenReturn(facts());
        when(search.searchUsingConfirmed(any(), any(), any(), any())).thenReturn(response("STALE"));
        var result = service.prepare(id, "session", 2);
        assertThat(result.status()).isEqualTo("STALE");
        verifyNoInteractions(ai, persistence);
    }

    @Test
    void aiFailureDoesNotPersistAReport() {
        var facts = facts();
        var found = response("FOUND");
        var snapshot = new InformationEvidenceSnapshot(id, 2, 3, "CARD_LOSS_UNAUTHORIZED_USE",
                UUID.randomUUID(), List.of(), List.of(), List.of(), List.of(evidence()),
                "CURRENT_READY_SOURCE_AND_HASH", "V38_SCOPE_REVIEWED", "NOT_DETERMINED");
        when(confirmed.current(id, "session")).thenReturn(facts);
        when(search.searchUsingConfirmed(any(), any(), any(), any())).thenReturn(found);
        when(snapshots.assemble(id, "session", found, facts)).thenReturn(snapshot);
        when(ai.select(snapshot)).thenThrow(new com.financialhelper.ai.AiProviderException("synthetic outage"));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.prepare(id, "session", 2))
                .isInstanceOf(com.financialhelper.ai.AiProviderException.class);
        verifyNoInteractions(persistence);
    }

    @Test
    void noApprovedEvidenceReturnsGapWithoutAiOrStorage() {
        var facts = facts();
        var found = response("NO_MATCH");
        var snapshot = new InformationEvidenceSnapshot(id, 2, 3, "CARD_LOSS_UNAUTHORIZED_USE",
                null, List.of(), List.of("INSTITUTION"), List.of("NO_APPROVED_CORPUS"), List.of(),
                "NO_APPROVED_EVIDENCE", "NOT_ESTABLISHED", "NOT_DETERMINED");
        when(confirmed.current(id, "session")).thenReturn(facts);
        when(search.searchUsingConfirmed(any(), any(), any(), any())).thenReturn(found);
        when(snapshots.assemble(id, "session", found, facts)).thenReturn(snapshot);
        var result = service.prepare(id, "session", 2);
        assertThat(result.status()).isEqualTo("INSUFFICIENT_EVIDENCE");
        assertThat(result.snapshot().coverageGaps()).containsExactly("NO_APPROVED_CORPUS");
        verifyNoInteractions(ai, persistence);
    }

    @Test
    void staleResultIsNeverExposed() throws Exception {
        var row = mock(InformationReport.class);
        var old = new InformationEvidenceSnapshot(id, 2, 3, "CARD_LOSS_UNAUTHORIZED_USE",
                UUID.randomUUID(), List.of(), List.of(), List.of(), List.of(),
                "NO_APPROVED_EVIDENCE", "NOT_ESTABLISHED", "NOT_DETERMINED");
        when(persistence.latest(id, "session")).thenReturn(Optional.of(row));
        when(row.getSnapshotJson()).thenReturn(mapper.writeValueAsString(old));
        assertThat(service.get(id, "session").status()).isEqualTo("STALE");
        verifyNoInteractions(ai);
    }

    @Test
    void changedEvidenceOnReloadReturnsStaleWithoutStoredContent() throws Exception {
        var row = mock(InformationReport.class);
        var evidence = new InformationEvidenceSnapshot.Evidence(UUID.randomUUID(), 1, "a".repeat(64),
                UUID.randomUUID(), UUID.randomUUID(), "COMMON", "합성 기관", "합성 문서",
                "https://example.invalid/source", null, null, "loc", "원문 근거", null, null,
                null, null, List.of(), List.of());
        var old = new InformationEvidenceSnapshot(id, 2, 3, "CARD_LOSS_UNAUTHORIZED_USE",
                UUID.randomUUID(), List.of(), List.of(), List.of(), List.of(evidence),
                "CURRENT_READY_SOURCE_AND_HASH", "V38_SCOPE_REVIEWED", "NOT_DETERMINED");
        var facts = new ConfirmedCaseSnapshotData(UUID.randomUUID(), id, 2, 3,
                List.of(), List.of(), null);
        when(persistence.latest(id, "session")).thenReturn(Optional.of(row));
        when(row.getSnapshotJson()).thenReturn(mapper.writeValueAsString(old));
        when(row.getCandidatesJson()).thenReturn(mapper.writeValueAsString(response("FOUND")));
        when(row.getSelectedEvidenceJson()).thenReturn(mapper.writeValueAsString(List.of(evidence)));
        when(confirmed.current(id, "session")).thenReturn(facts);
        when(snapshots.assemble(any(), any(), any(), any()))
                .thenThrow(new IllegalStateException("review changed"));
        var result = service.get(id, "session");
        assertThat(result.status()).isEqualTo("STALE");
        assertThat(result.snapshot()).isNull();
        assertThat(result.selectedEvidence()).isEmpty();
    }

    private ConsultationOfficialInformationResponse response(String status) {
        return new ConsultationOfficialInformationResponse(id, 2, 3, "CARD_LOSS_UNAUTHORIZED_USE",
                null, status, false, List.of(), List.of(), List.of(), List.of());
    }

    private ConfirmedCaseSnapshotData facts() {
        return new ConfirmedCaseSnapshotData(null, id, 2, 3, List.of(), List.of(), null);
    }

    private InformationEvidenceSnapshot.Evidence evidence() {
        return new InformationEvidenceSnapshot.Evidence(UUID.randomUUID(), 1, "a".repeat(64),
                UUID.randomUUID(), UUID.randomUUID(), "COMMON", "합성 기관", "합성 문서",
                "https://example.invalid/source", null, null, "loc", "원문 근거", null, null,
                null, null, List.of(), List.of());
    }
}
