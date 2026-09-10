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
                        List.of()
                ),

                aiV1evidence()
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
            List<Object> citations
    ) {
    }

    public record FirstAction(
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
}