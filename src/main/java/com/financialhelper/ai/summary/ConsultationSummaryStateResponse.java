package com.financialhelper.ai.summary;

import java.util.List;

public record ConsultationSummaryStateResponse(
        String kind,
        Summary summary
) {

    public static ConsultationSummaryStateResponse notPrepared() {
        return new ConsultationSummaryStateResponse(
                "not-prepared",
                null
        );
    }

    public static ConsultationSummaryStateResponse ready(
            ConsultationSummaryData.Document document
    ) {

        return new ConsultationSummaryStateResponse(
                "ready",
                new Summary(
                        document.result().headline,
                        document.result().summaryText,
                        document.result()
                                .keyPoints
                                .stream()
                                .map(keyPoint -> keyPoint.text)
                                .toList()
                )
        );
    }

    public record Summary(
            String headline,
            String summaryText,
            List<String> keyPoints
    ) {
    }
}