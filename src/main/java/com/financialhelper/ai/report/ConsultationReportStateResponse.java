package com.financialhelper.ai.report;

import java.util.List;

public record ConsultationReportStateResponse(
        String kind,
        Report report,
        Evidence evidence
) {

    public static ConsultationReportStateResponse
    notPrepared() {

        return new ConsultationReportStateResponse(
                "not-prepared",
                null,
                aiV1evidence()
        );
    }

    public static ConsultationReportStateResponse ready(
            ConsultationReportData.Document document
    ) {

        ConsultationReportAiResult result =
                document.result();

        // FE가 볼 API 응답 형태로 반환
        return new ConsultationReportStateResponse(
                "ready",

                new Report(
                        result.headline,
                        result.caseSummary,

                        new FirstAction(
                                result.firstAction.actionId,
                                result.firstAction.title,
                                result.firstAction.description
                        ),

                        result.keyIssues
                                .stream()
                                .map(
                                        item ->
                                                new KeyIssue(
                                                        item.title,
                                                        item.explanation
                                                )
                                )
                                .toList(),

                        result.actionSteps
                                .stream()
                                .map(
                                        item ->
                                                new ActionStep(
                                                        item.actionId,
                                                        item.order,
                                                        item.title,
                                                        item.description
                                                )
                                )
                                .toList(),

                        result.actionConsequences
                                .stream()
                                .map(
                                        item ->
                                                new ActionConsequence(
                                                        item.action,
                                                        item.consequence
                                                )
                                )
                                .toList(),

                        result.requiredDocuments
                                .stream()
                                .map(
                                        item ->
                                                new RequiredDocument(
                                                        item.documentId,
                                                        item.name,
                                                        item.reason
                                                )
                                )
                                .toList(),

                        result.terms
                                .stream()
                                .map(
                                        item ->
                                                new FinancialTerm(
                                                        item.term,
                                                        item.explanation
                                                )
                                )
                                .toList(),

                        new ComplaintDraft(
                                result.complaintDraft.subject,
                                result.complaintDraft.body
                        ),

                        List.of(),
                        result.evidenceCitations == null
                                ? List.of()
                                : result.evidenceCitations.stream()
                                .map(item -> new Citation(
                                        item.evidenceId,
                                        item.locator,
                                        item.label))
                                .toList()
                ),

                result.evidenceCitations != null && !result.evidenceCitations.isEmpty()
                        ? groundedEvidence(document.scenario())
                                : aiV1evidence()
        );
    }

    private static Evidence aiV1evidence() {

        return new Evidence(
                "NOT_AVAILABLE_IN_AI_V1",
                "현재 AI V1 리포트에는 검증된 공식 근거 인용이 아직 연결되지 않았습니다."
        );
    }

    public record Report(
            String headline,
            String caseSummary,
            FirstAction firstAction,
            List<KeyIssue> keyIssues,
            List<ActionStep> actionSteps,
            List<ActionConsequence> actionConsequences,
            List<RequiredDocument> requiredDocuments,
            List<FinancialTerm> terms,
            ComplaintDraft complaintDraft,

            /*
             * AI V2/V3에서 실제 데이터 연결 예정.
             */
            List<Object> similarCases,
            List<Citation> citations
    ) {
    }

    public record FirstAction(
            String actionId,
            String title,
            String description
    ) {
    }

    public record KeyIssue(
            String title,
            String explanation
    ) {
    }

    public record ActionStep(
            String actionId,
            int order,
            String title,
            String description
    ) {
    }

    public record ActionConsequence(
            String action,
            String consequence
    ) {
    }

    public record RequiredDocument(
            String documentId,
            String name,
            String reason
    ) {
    }

    public record FinancialTerm(
            String term,
            String explanation
    ) {
    }

    public record ComplaintDraft(
            String subject,
            String body
    ) {
    }

    public record Evidence(
            String status,
            String message
    ) {
    }

    private static Evidence groundedEvidence(
            com.financialhelper.consultation.ConsultationScenario scenario
    ) {
        String status = scenario
                == com.financialhelper.consultation.ConsultationScenario.CARD_LOSS_UNAUTHORIZED_USE
                || scenario == null
                ? "GROUNDED_CARD" : "GROUNDED_FINANCIAL_FRAUD";
        return new Evidence(
                status,
                scenario == com.financialhelper.consultation.ConsultationScenario.CARD_LOSS_UNAUTHORIZED_USE
                        || scenario == null
                        ? "승인된 CARD 공식 자료와 검토된 법령 근거를 기준으로 작성된 리포트입니다."
                        : "승인된 공식 자료를 기준으로 작성된 금융소비자 보호 안내입니다."
        );
    }

    public record Citation(
            String evidenceId,
            String locator,
            String label
    ) {
    }
}
