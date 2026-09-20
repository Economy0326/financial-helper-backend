package com.financialhelper.ai.grounded;

import com.financialhelper.ai.analysis.AnalysisAiResult;
import com.financialhelper.ai.summary.ConsultationSummaryAiResult;
import com.financialhelper.procedure.ConditionResult;
import com.financialhelper.procedure.FinancialActionPlanData;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 설명용 AI 호출에 사용하는 목적별 제한 입력이다.
 *
 * <p>저장된 {@link AnalysisEvidenceSnapshotData}는 완전한 audit/replay record로
 * 유지한다. 이 projection은 model과 grounded output contract에 필요한 identity,
 * locator, 조항 단위 문장만 의도적으로 유지한다.</p>
 */
public final class GroundedAiInputProjection {

    private static final int EVIDENCE_TEXT_BUDGET = 5600;
    private static final int MIN_TEXT_PER_EVIDENCE = 240;

    private GroundedAiInputProjection() {
    }

    public static AnalysisInput forAnalysis(
            String category,
            ConsultationSummaryAiResult summary,
            AnalysisEvidenceSnapshotData snapshot
    ) {
        return new AnalysisInput(
                category,
                summary,
                snapshot == null ? null : actionPlan(snapshot.actionPlan()),
                snapshot == null ? List.of() : sourceEvidence(snapshot.sourceEvidence(), EVIDENCE_TEXT_BUDGET / 2),
                snapshot == null ? List.of() : lawEvidence(snapshot.reviewedLawEvidence(), EVIDENCE_TEXT_BUDGET / 2)
        );
    }

    public static ReportInput forReport(
            String category,
            ConsultationSummaryAiResult summary,
            AnalysisAiResult analysis,
            AnalysisEvidenceSnapshotData snapshot
    ) {
        return new ReportInput(
                category,
                summary,
                analysis,
                snapshot == null ? null : actionPlan(snapshot.actionPlan()),
                snapshot == null ? List.of() : sourceEvidence(snapshot.sourceEvidence(), 1600),
                snapshot == null ? List.of() : lawEvidence(snapshot.reviewedLawEvidence(), 1600)
        );
    }

    private static CompactActionPlan actionPlan(FinancialActionPlanData plan) {
        if (plan == null) {
            return new CompactActionPlan(null, 0, List.of(), List.of(), List.of(), List.of(), List.of());
        }
        return new CompactActionPlan(
                plan.scenario(),
                plan.procedureVersion(),
                plan.actions().stream().map(action -> new CompactAction(
                        action.actionId(), action.order(), action.title(), action.description(),
                        action.conditionResult(), action.channelRef())).toList(),
                plan.requiredDocuments().stream().map(document -> new CompactDocument(
                        document.documentId(), document.title(), document.status(),
                        document.conditionResult(), document.evidenceRef())).toList(),
                plan.unresolvedFacts(),
                plan.coverageGaps(),
                plan.warnings()
        );
    }

    private static List<CompactSourceEvidence> sourceEvidence(
            List<AnalysisEvidenceSnapshotData.SourceEvidence> source,
            int budget
    ) {
        return compactSource(source, budget);
    }

    private static List<CompactLawEvidence> lawEvidence(
            List<AnalysisEvidenceSnapshotData.ReviewedLawEvidence> law,
            int budget
    ) {
        return compactLaw(law, budget);
    }

    private static List<CompactSourceEvidence> compactSource(
            List<AnalysisEvidenceSnapshotData.SourceEvidence> values,
            int budget
    ) {
        List<Integer> allocations = allocate(values.stream()
                .map(AnalysisEvidenceSnapshotData.SourceEvidence::body)
                .toList(), budget);
        List<CompactSourceEvidence> result = new ArrayList<>();
        for (int i = 0; i < values.size(); i++) {
            AnalysisEvidenceSnapshotData.SourceEvidence item = values.get(i);
            result.add(new CompactSourceEvidence(
                    item.evidenceId(),
                    item.sourceDocumentId(),
                    item.documentVersion(),
                    item.sourceChunkId(),
                    item.sequence(),
                    item.articleReference(),
                    item.pageReference(),
                    item.locator(),
                    clauseAwareExcerpt(item.body(), allocations.get(i))
            ));
        }
        return List.copyOf(result);
    }

    private static List<CompactLawEvidence> compactLaw(
            List<AnalysisEvidenceSnapshotData.ReviewedLawEvidence> values,
            int budget
    ) {
        List<Integer> allocations = allocate(values.stream()
                .map(AnalysisEvidenceSnapshotData.ReviewedLawEvidence::text)
                .toList(), budget);
        List<CompactLawEvidence> result = new ArrayList<>();
        for (int i = 0; i < values.size(); i++) {
            AnalysisEvidenceSnapshotData.ReviewedLawEvidence item = values.get(i);
            result.add(new CompactLawEvidence(
                    item.evidenceId(),
                    item.statuteName(),
                    item.lawIdentifier(),
                    item.mst(),
                    item.articleLocator(),
                    item.effectiveDate(),
                    clauseAwareExcerpt(item.text(), allocations.get(i))
            ));
        }
        return List.copyOf(result);
    }

    /** Evidence identity를 누락하지 않고 제한된 문장 budget을 할당한다. */
    private static List<Integer> allocate(List<String> texts, int budget) {
        if (texts.isEmpty()) {
            return List.of();
        }
        int minimum = Math.min(MIN_TEXT_PER_EVIDENCE, budget / texts.size());
        int minimumTotal = minimum * texts.size();
        int remaining = Math.max(0, budget - minimumTotal);
        int total = texts.stream().mapToInt(value -> value == null ? 0 : value.length()).sum();
        List<Integer> result = new ArrayList<>();
        for (String text : texts) {
            int length = text == null ? 0 : text.length();
            int extra = total == 0 ? 0 : (int) ((long) remaining * length / total);
            result.add(Math.min(length, minimum + extra));
        }
        int used = result.stream().mapToInt(Integer::intValue).sum();
        for (int i = 0; used < budget && i < result.size(); i++) {
            int length = texts.get(i) == null ? 0 : texts.get(i).length();
            if (result.get(i) < length) {
                result.set(i, result.get(i) + 1);
                used++;
            }
        }
        return List.copyOf(result);
    }

    /**
     * 완전한 문장/문단 단위를 유지한다. 본문이 할당량보다 크면 condition/exception
     * 단위를 우선하고 나머지는 source 순서로 가져온다. 임의 문자 prefix slice는 사용하지 않는다.
     */
    private static String clauseAwareExcerpt(String text, int limit) {
        if (text == null || text.isBlank() || text.length() <= limit) {
            return text;
        }
        String[] units = text.split("(?<=[.!?。！？])\\s+|\\R+");
        List<String> prioritized = new ArrayList<>();
        List<String> ordinary = new ArrayList<>();
        for (String unit : units) {
            if (unit.isBlank()) {
                continue;
            }
            if (unit.matches(".*(조건|예외|단서|다만|제외|아닌 경우|하지 아니|책임).*")) {
                prioritized.add(unit.trim());
            } else {
                ordinary.add(unit.trim());
            }
        }
        List<String> selected = new ArrayList<>();
        int used = 0;
        for (String unit : prioritized) {
            if (used + unit.length() + 1 <= limit) {
                selected.add(unit);
                used += unit.length() + 1;
            }
        }
        for (String unit : ordinary) {
            if (used + unit.length() + 1 <= limit) {
                selected.add(unit);
                used += unit.length() + 1;
            }
        }
        if (selected.isEmpty()) {
        // 나눌 수 없는 단일 조항은 법적 의미를 자르지 않고 전체를 유지한다.
            return units[0].trim();
        }
        return String.join(" ", selected);
    }

    public record AnalysisInput(
            String consultationCategory,
            ConsultationSummaryAiResult confirmedSummary,
            CompactActionPlan actionPlan,
            List<CompactSourceEvidence> sourceEvidence,
            List<CompactLawEvidence> lawEvidence
    ) {
    }

    public record ReportInput(
            String consultationCategory,
            ConsultationSummaryAiResult confirmedSummary,
            AnalysisAiResult analysisResult,
            CompactActionPlan actionPlan,
            List<CompactSourceEvidence> sourceEvidence,
            List<CompactLawEvidence> lawEvidence
    ) {
    }

    public record CompactActionPlan(
            String scenario,
            int procedureVersion,
            List<CompactAction> actions,
            List<CompactDocument> requiredDocuments,
            List<String> unresolvedFacts,
            List<String> coverageGaps,
            List<String> warnings
    ) {
    }

    public record CompactAction(
            String actionId,
            int order,
            String title,
            String description,
            ConditionResult conditionResult,
            String channelRef
    ) {
    }

    public record CompactDocument(
            String documentId,
            String title,
            String status,
            ConditionResult conditionResult,
            String evidenceRef
    ) {
    }

    public record CompactSourceEvidence(
            String evidenceId,
            UUID sourceDocumentId,
            int documentVersion,
            UUID sourceChunkId,
            int sequence,
            String articleReference,
            String pageReference,
            String locator,
            String excerpt
    ) {
    }

    public record CompactLawEvidence(
            String evidenceId,
            String statuteName,
            String lawIdentifier,
            String mst,
            String articleLocator,
            String effectiveDate,
            String excerpt
    ) {
    }
}
