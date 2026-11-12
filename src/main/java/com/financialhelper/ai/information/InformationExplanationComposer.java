package com.financialhelper.ai.information;

import com.financialhelper.ai.AiOutputContractException;
import com.financialhelper.ai.AiProviderException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;

/** AI selects exact evidence only. The user-facing wording is a deterministic, source-bound template. */
@Component
@ConditionalOnExpression("${app.ai.enabled:false} or ${app.information-report.enabled:false}")
public class InformationExplanationComposer {
    private final InformationExplanationService selector;

    public InformationExplanationComposer(InformationExplanationService selector) {
        this.selector = selector;
    }

    @Autowired
    public InformationExplanationComposer(ObjectProvider<InformationExplanationService> selector) {
        this.selector = selector.getIfAvailable();
    }

    public Result compose(InformationEvidenceSnapshot snapshot) {
        if (snapshot.evidence().isEmpty()) return new Result(List.of(), List.of());
        if (!"CURRENT_READY_SOURCE_AND_HASH".equals(snapshot.sourceIntegrityStatus())
                || !"V38_SCOPE_REVIEWED".equals(snapshot.humanScopeReviewStatus())) {
            throw new IllegalStateException("information evidence is not reviewed");
        }
        if (selector == null) {
            var selected = snapshot.evidence();
            return new Result(selected, render(selected, "EXTRACTIVE_FALLBACK"));
        }
        try {
            var selected = selector.select(snapshot);
            if (selected == null || selected.isEmpty() || !snapshot.evidence().containsAll(selected)) {
                throw new AiOutputContractException("information selection is outside reviewed evidence");
            }
            return new Result(selected, render(selected, "AI_SELECTED_TEMPLATE"));
        } catch (AiProviderException | AiOutputContractException exception) {
            // Selection failed. Only the already checked, server-owned snapshot can be used.
            var selected = snapshot.evidence();
            return new Result(selected, render(selected, "EXTRACTIVE_FALLBACK"));
        }
    }

    public List<InformationExplanation> render(List<InformationEvidenceSnapshot.Evidence> selected, String mode) {
        if (!"AI_SELECTED_TEMPLATE".equals(mode) && !"EXTRACTIVE_FALLBACK".equals(mode)
                && !"LEGACY_EXTRACTIVE".equals(mode)) {
            throw new IllegalStateException("unknown information explanation mode");
        }
        return selected.stream().map(item -> new InformationExplanation(item.supportChunkId(), mode,
                prefix(item) + item.supportText() + related(" 이 내용의 조건: ", item.conditions())
                        + related(" 예외: ", item.exceptions())
                        + " 이 자료가 내 상황에 적용되는지는 아직 확인되지 않았습니다.",
                item.conditions(), item.exceptions())).toList();
    }

    private static String related(String label, List<InformationEvidenceSnapshot.RelatedText> text) {
        if (text.isEmpty()) return "";
        return label + text.stream().map(InformationEvidenceSnapshot.RelatedText::text)
                .reduce((left, right) -> left + " / " + right).orElseThrow();
    }

    private static String prefix(InformationEvidenceSnapshot.Evidence item) {
        if ("COMMON".equals(item.scopeKind())) return "공식 자료에서 확인한 내용(공통 정보): ";
        if ("INSTITUTION_SPECIFIC".equals(item.scopeKind()) && item.applicableInstitution() != null
                && !item.applicableInstitution().isBlank()) {
            return item.applicableInstitution() + " 자료에서 확인한 내용(해당 기관 전용): ";
        }
        throw new IllegalStateException("information evidence scope is incomplete");
    }

    public record Result(List<InformationEvidenceSnapshot.Evidence> selected,
            List<InformationExplanation> explanations) {}
}
