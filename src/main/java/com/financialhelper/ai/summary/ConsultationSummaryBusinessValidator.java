package com.financialhelper.ai.summary;

import com.financialhelper.ai.AiOutputContractException;

import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.Set;

@Component
public class ConsultationSummaryBusinessValidator {

    public ConsultationSummaryAiResult validate(
            ConsultationSummaryAiResult result
    ) {

        if (
                result == null
                || result.keyPoints == null
        ) {
            throw new AiOutputContractException(
                    "Summary result must not be null"
            );
        }

        // key point는 중복 x
        Set<String> uniqueKeyPoints =
                new HashSet<>();

        for (
                ConsultationSummaryAiResult.KeyPoint keyPoint
                : result.keyPoints
        ) {

            String normalized =
                    keyPoint.text
                            .trim()
                            .toLowerCase();

            if (
                    !uniqueKeyPoints.add(
                            normalized
                    )
            ) {
                throw new AiOutputContractException(
                        "Duplicate summary key point"
                );
            }
        }

        return result;
    }
}