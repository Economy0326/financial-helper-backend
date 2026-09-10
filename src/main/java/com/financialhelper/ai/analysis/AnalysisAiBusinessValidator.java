package com.financialhelper.ai.analysis;

import com.financialhelper.ai.AiOutputContractException;

import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.Set;

@Component
public class AnalysisAiBusinessValidator {

    public AnalysisAiResult validate(
            AnalysisAiResult result
    ) {

        if (
                result == null
                || result.outcome == null
                || result.keyIssues == null
                || result.additionalInformationNeeded == null
        ) {
            throw new AiOutputContractException(
                    "Analysis result must not be null"
            );
        }

        validateIssues(result);
        validateAdditionalInformation(result);

        // 분석 완료 상태에서는 추가 정보 요청이 없어야 함
        if (
                result.outcome
                        == AnalysisAiResult.Outcome.READY_FOR_REPORT
                && !result
                        .additionalInformationNeeded
                        .isEmpty()
        ) {
            throw new AiOutputContractException(
                    "READY_FOR_REPORT must not contain additional information requests"
            );
        }

        // NEEDS_MORE_INFO 상태에서는 필요한 추가 정보를 반드시 포함해야 함
        if (
                result.outcome
                        == AnalysisAiResult.Outcome.NEEDS_MORE_INFO
                && result
                        .additionalInformationNeeded
                        .isEmpty()
        ) {
            throw new AiOutputContractException(
                    "NEEDS_MORE_INFO must explain what information is needed"
            );
        }

        return result;
    }

    // Key Issue의 tilte 중복 검증
    private void validateIssues(
            AnalysisAiResult result
    ) {

        Set<String> titles =
                new HashSet<>();

        for (
                AnalysisAiResult.KeyIssue issue
                : result.keyIssues
        ) {

            String normalized =
                    issue.title
                            .trim()
                            .toLowerCase();

            if (!titles.add(normalized)) {
                throw new AiOutputContractException(
                        "Duplicate analysis key issue"
                );
            }
        }
    }

    // 추가 정보 요청의 topic 중복 검증
    private void validateAdditionalInformation(
            AnalysisAiResult result
    ) {

        Set<String> topics =
                new HashSet<>();

        for (
                AnalysisAiResult.AdditionalInformation information
                : result.additionalInformationNeeded
        ) {

            String normalized =
                    information.topic
                            .trim()
                            .toLowerCase();

            if (!topics.add(normalized)) {
                throw new AiOutputContractException(
                        "Duplicate additional information topic"
                );
            }
        }
    }
}