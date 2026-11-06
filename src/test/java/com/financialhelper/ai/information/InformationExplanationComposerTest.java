package com.financialhelper.ai.information;

import com.financialhelper.ai.AiOutputContractException;
import com.financialhelper.ai.AiProviderException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

class InformationExplanationComposerTest {
    private final InformationExplanationService selector = mock(InformationExplanationService.class);
    private final InformationExplanationComposer composer = new InformationExplanationComposer(selector);

    @Test
    void reviewedInstitutionalEvidenceKeepsConditionsExceptionsAndScope() {
        var evidence = evidence("INSTITUTION_SPECIFIC", "합성 카드사");
        var snapshot = snapshot(evidence);
        when(selector.select(snapshot)).thenReturn(List.of(evidence));
        var result = composer.compose(snapshot);
        assertThat(result.explanations().getFirst().easyText())
                .contains("합성 카드사 자료에서 확인한 내용", "지원 문장", "이 내용의 조건: 조건 문장",
                        "예외: 예외 문장", "내 상황에 적용되는지는 아직 확인되지 않았습니다");
        assertThat(result.explanations().getFirst().conditions()).containsExactlyElementsOf(evidence.conditions());
        assertThat(result.explanations().getFirst().exceptions()).containsExactlyElementsOf(evidence.exceptions());
        assertThat(result.explanations().getFirst().mode()).isEqualTo("AI_SELECTED_TEMPLATE");
    }

    @Test
    void providerAndInvalidOutputUseOnlyCapturedEvidence() {
        var snapshot = snapshot(evidence("COMMON", null));
        when(selector.select(snapshot)).thenThrow(new AiProviderException("synthetic timeout"));
        assertThat(composer.compose(snapshot).explanations().getFirst().mode()).isEqualTo("EXTRACTIVE_FALLBACK");
        doThrow(new AiOutputContractException("synthetic forged source")).when(selector).select(snapshot);
        assertThat(composer.compose(snapshot).selected()).containsExactlyElementsOf(snapshot.evidence());
    }

    @Test
    void unreviewedSnapshotCannotUseFallback() {
        var good = snapshot(evidence("COMMON", null));
        var bad = new InformationEvidenceSnapshot(good.consultationId(), 1, 0, good.scenario(),
                good.retrievalGenerationId(), List.of(), List.of(), List.of(), good.evidence(),
                "NO_APPROVED_EVIDENCE", "NOT_ESTABLISHED", "NOT_DETERMINED");
        assertThatThrownBy(() -> composer.compose(bad)).isInstanceOf(IllegalStateException.class);
    }

    private static InformationEvidenceSnapshot snapshot(InformationEvidenceSnapshot.Evidence evidence) {
        return new InformationEvidenceSnapshot(UUID.randomUUID(), 1, 0, "CARD_LOSS_UNAUTHORIZED_USE",
                UUID.randomUUID(), List.of(), List.of(), List.of(), List.of(evidence),
                "CURRENT_READY_SOURCE_AND_HASH", "V38_SCOPE_REVIEWED", "NOT_DETERMINED");
    }

    private static InformationEvidenceSnapshot.Evidence evidence(String kind, String institution) {
        return new InformationEvidenceSnapshot.Evidence(UUID.randomUUID(), 1, "a".repeat(64),
                UUID.randomUUID(), UUID.randomUUID(), kind, "합성 발행기관", "합성 문서",
                "https://example.invalid/source", null, null, "문단 1", "지원 문장", institution,
                null, null, null,
                List.of(new InformationEvidenceSnapshot.RelatedText(UUID.randomUUID(), "문단 2", "조건 문장")),
                List.of(new InformationEvidenceSnapshot.RelatedText(UUID.randomUUID(), "문단 3", "예외 문장")));
    }
}
