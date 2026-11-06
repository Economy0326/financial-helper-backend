package com.financialhelper.ai.information;

import com.financialhelper.ai.OpenAiStructuredClient;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import org.mockito.ArgumentCaptor;

class InformationExplanationServiceTest {
    @Test
    void noReviewedEvidenceDoesNotCallProvider() {
        var client = mock(OpenAiStructuredClient.class);
        var service = new InformationExplanationService(client, JsonMapper.builder().build(),
                new InformationExplanationValidator());
        var snapshot = new InformationEvidenceSnapshot(UUID.randomUUID(), 1, 0, "CARD_LOSS_UNAUTHORIZED_USE",
                null, List.of(), List.of("INSTITUTION"), List.of("NO_APPROVED_CORPUS"), List.of(),
                "NO_APPROVED_EVIDENCE", "NOT_ESTABLISHED", "NOT_DETERMINED");
        assertThat(service.select(snapshot)).isEmpty();
        verifyNoInteractions(client);
    }

    @Test
    void invalidModelSelectionNeverBecomesServerEvidence() {
        var client = mock(OpenAiStructuredClient.class);
        var service = new InformationExplanationService(client, JsonMapper.builder().build(),
                new InformationExplanationValidator());
        UUID support = UUID.randomUUID();
        var evidence = new InformationEvidenceSnapshot.Evidence(UUID.randomUUID(), 1, "a".repeat(64),
                support, UUID.randomUUID(), "COMMON", "합성 기관", "합성 문서", "https://example.invalid/source",
                null, null, "loc", "원문 근거", null, null, null, null, List.of(), List.of());
        var snapshot = new InformationEvidenceSnapshot(UUID.randomUUID(), 1, 0, "CARD_LOSS_UNAUTHORIZED_USE",
                UUID.randomUUID(), List.of(new com.financialhelper.retrieval.ConfirmedCaseSnapshotData.Fact(
                        "TEXT", "accountNumber", "synthetic-sensitive-value", null, "USER_ANSWERED", null)),
                List.of(), List.of(), List.of(evidence),
                "CURRENT_READY_SOURCE_AND_HASH", "V38_SCOPE_REVIEWED", "NOT_DETERMINED");
        when(client.generateStructured(anyString(), anyString(), eq(InformationExplanationAiResult.class)))
                .thenReturn(new InformationExplanationAiResult(List.of(
                        new InformationExplanationAiResult.Selection(support, "가짜 문장", List.of(), List.of()))));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.select(snapshot))
                .hasMessageContaining("differs");
        ArgumentCaptor<String> input = ArgumentCaptor.forClass(String.class);
        verify(client).generateStructured(anyString(), input.capture(), eq(InformationExplanationAiResult.class));
        assertThat(input.getValue()).doesNotContain("confirmedFacts", "situationText", "accountNumber",
                "synthetic-sensitive-value");
    }
}
