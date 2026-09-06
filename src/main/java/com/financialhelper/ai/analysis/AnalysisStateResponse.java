package com.financialhelper.ai.analysis;

import java.util.List;

public record AnalysisStateResponse(
        String status,
        int attemptCount,
        List<AdditionalInformation>
                additionalInformationNeeded
) {

    public static AnalysisStateResponse
    notStarted() {

        return new AnalysisStateResponse(
                "NOT_STARTED",
                0,
                List.of()
        );
    }

    public static AnalysisStateResponse from(
            AnalysisJob job,
            AnalysisAiResult result
    ) {

        List<AdditionalInformation> information =
                result == null
                        ? List.of()
                        : result
                                .additionalInformationNeeded
                                .stream()
                                .map(
                                        item ->
                                                new AdditionalInformation(
                                                        item.topic,
                                                        item.reason
                                                )
                                )
                                .toList();

        // FE에게 필요한 값만 추려 외부 Contract를 만듦
        return new AnalysisStateResponse(
                job.getStatus().name(),
                job.getAttemptCount(),
                information
        );
    }

    public record AdditionalInformation(
            String topic,
            String reason
    ) {
    }
}