package com.financialhelper.ai.grounded;

import com.financialhelper.ai.summary.ConsultationSummaryAiResult;
import com.financialhelper.ai.analysis.AnalysisAiResult;
import com.financialhelper.procedure.ConditionResult;
import com.financialhelper.procedure.FinancialActionPlanData;
import com.financialhelper.procedure.PlanStatus;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.json.JsonMapper;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class GroundedAiInputProjectionTest {

    @Test
    void keepsSnapshotSeparateAndBoundsAnalysisProjection() throws Exception {
        AnalysisEvidenceSnapshotData snapshot = snapshot();
        JsonMapper mapper = JsonMapper.builder().build();

        String fullSnapshot = mapper.writeValueAsString(snapshot);
        String compact = mapper.writeValueAsString(
                GroundedAiInputProjection.forAnalysis("CARD", summary(), snapshot));
        AnalysisAiResult analysis = new AnalysisAiResult();
        analysis.outcome = AnalysisAiResult.Outcome.READY_FOR_REPORT;
        analysis.analysisSummary = "현재 확인된 정보로 정리할 수 있는 내용입니다.";
        analysis.keyIssues = List.of();
        analysis.additionalInformationNeeded = List.of();
        String reportCompact = mapper.writeValueAsString(
                GroundedAiInputProjection.forReport("CARD", summary(), analysis, snapshot));

        assertThat(fullSnapshot).contains("raw-sha", "generation-corpus");
        assertThat(compact).contains("source:chunk-0", "source-locator-0", "law:000536:277267:제16조");
        assertThat(compact).doesNotContain("raw-sha", "generation-corpus");
        assertThat(compact.length()).isLessThan(12_000);
        assertThat(compact.length()).isLessThan(fullSnapshot.length());
        assertThat(reportCompact.length()).isLessThan(12_000);
    }

    private AnalysisEvidenceSnapshotData snapshot() {
        FinancialActionPlanData plan = new FinancialActionPlanData(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "CARD_LOSS_UNAUTHORIZED_USE", 1, 1, 1, PlanStatus.READY,
                List.of(new FinancialActionPlanData.Action(
                        "report-loss", 1, "분실·도난 신고", "카드사 공식 채널에 신고하세요.",
                        ConditionResult.TRUE, "kb-card-loss")),
                List.of(new FinancialActionPlanData.Document(
                        "incident-record", "거래 내역", "CONDITIONAL", ConditionResult.TRUE, "kb-form")),
                List.of(), List.of(), List.of(), List.of());
        UUID documentId = UUID.randomUUID();
        return new AnalysisEvidenceSnapshotData(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 1, 1,
                plan.procedureVersionId(), plan.id(), UUID.randomUUID(), "card-generation",
                "kure", "kure-revision", "kure-tokenizer", "kure-tokenizer-revision",
                "{\"encoding\":\"full\"}", "{\"index\":\"plaid\"}", "generation-corpus",
                plan,
                java.util.stream.IntStream.range(0, 7)
                        .mapToObj(i -> new AnalysisEvidenceSnapshotData.SourceEvidence(
                                "source:chunk-" + i, documentId, 1, "raw-sha-" + i, "content-sha-" + i,
                                "publisher", "official.example", "https://official.example/source", null,
                                "Document", UUID.randomUUID(), i, "제40조", "p." + (i + 1),
                                "source-locator-" + i, "공식 원문 단락입니다. 조건과 예외를 확인합니다.".repeat(30)))
                        .toList(),
                java.util.stream.IntStream.range(0, 5)
                        .mapToObj(i -> new AnalysisEvidenceSnapshotData.ReviewedLawEvidence(
                                i == 0 ? "law:000536:277267:제16조" : "law:000536:277267:제16조의" + i,
                                "여신전문금융업법", "000536", "277267", "제16조", "2026-01-01",
                                null, null, null, "법령 원문입니다. 책임과 예외를 확인합니다.".repeat(30),
                                "law-open-api", null, null, "DIRECT_KOREAN_LAW_OPEN_API", "VALIDATED",
                                OffsetDateTime.now(ZoneOffset.UTC)))
                        .toList(),
                1, AnalysisEvidenceSnapshotStatus.READY, OffsetDateTime.now(ZoneOffset.UTC));
    }

    private ConsultationSummaryAiResult summary() {
        ConsultationSummaryAiResult summary = new ConsultationSummaryAiResult();
        summary.headline = "카드 분실과 모르는 결제";
        summary.summaryText = "카드를 잃어버렸고 본인이 하지 않은 결제가 확인되었습니다.";
        ConsultationSummaryAiResult.KeyPoint point = new ConsultationSummaryAiResult.KeyPoint();
        point.text = "국내 신용판매 거래";
        summary.keyPoints = List.of(point, point);
        return summary;
    }
}
