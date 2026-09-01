package com.financialhelper.ai.understanding;

import java.util.UUID;

public record CaseUnderstandingResponse(
        UUID consultationId,
        long caseInputRevision,
        int factCount,
        int missingInformationCount
) {

    public static CaseUnderstandingResponse from(
            CaseUnderstandingData.Document document
    ) {

        return new CaseUnderstandingResponse(
                document.consultationId(),
                document.caseInputRevision(),
                document.result().facts.size(),
                document
                        .result()
                        .missingInformation
                        .size()
        );
    }
}