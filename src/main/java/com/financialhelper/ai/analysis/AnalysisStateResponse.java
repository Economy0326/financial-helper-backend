package com.financialhelper.ai.analysis;

import java.util.List;

public record AnalysisStateResponse(
        String status,
        int attemptCount,
        int informationSupplementCount,
        boolean canSupplementInformation,
        List<AdditionalInformation>
                additionalInformationNeeded,
        List<SafeAction> safeActions
) {

    public static AnalysisStateResponse notStarted(
            int informationSupplementCount
    ) {

        return new AnalysisStateResponse(
                "NOT_STARTED",
                0,
                informationSupplementCount,
                informationSupplementCount < 1,
                List.of(),
                List.of()
        );
    }

    public static AnalysisStateResponse from(
            AnalysisJob job,
            AnalysisAiResult result,
            int informationSupplementCount,
            List<SafeAction> safeActions
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

        // FE에 필요한 내용만 추려서 반환
        return new AnalysisStateResponse(
                job.getStatus().name(),
                job.getAttemptCount(),
                informationSupplementCount,
                informationSupplementCount < 1,
                information,
                safeActions == null ? List.of() : safeActions
        );
    }

    public record AdditionalInformation(
            String topic,
            String reason
    ) {
    }

    public record SafeAction(
            String actionId,
            String title,
            String description
    ) {
    }
}
