package com.financialhelper.ai.report;

import com.financialhelper.ai.AiOutputContractException;

import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.Set;

@Component
public class ConsultationReportBusinessValidator {

    public ConsultationReportAiResult validate(
            ConsultationReportAiResult result
    ) {

        if (
                result == null
                || result.keyIssues == null
                || result.actionSteps == null
                || result.requiredDocuments == null
                || result.terms == null
        ) {
            throw new AiOutputContractException(
                    "Report result must not be null"
            );
        }

        validateActionSteps(result);
        validateUniqueIssues(result);
        validateUniqueDocuments(result);
        validateUniqueTerms(result);

        return result;
    }

    // 순서에 맞는 ActionStep인지 검증
    private void validateActionSteps(
            ConsultationReportAiResult result
    ) {

        for (
                int index = 0;
                index < result.actionSteps.size();
                index++
        ) {

            int expectedOrder =
                    index + 1;

            if (
                    result
                            .actionSteps
                            .get(index)
                            .order
                            != expectedOrder
            ) {
                throw new AiOutputContractException(
                        "Report action step order must be sequential"
                );
            }
        }
    }

    // 이슈 중복 검증
    private void validateUniqueIssues(
            ConsultationReportAiResult result
    ) {

        Set<String> values =
                new HashSet<>();

        for (
                ConsultationReportAiResult.KeyIssue issue
                : result.keyIssues
        ) {

            String normalized =
                    issue.title
                            .trim()
                            .toLowerCase();

            if (!values.add(normalized)) {
                throw new AiOutputContractException(
                        "Duplicate report key issue"
                );
            }
        }
    }

    // 문서 중복 검증
    private void validateUniqueDocuments(
            ConsultationReportAiResult result
    ) {

        Set<String> values =
                new HashSet<>();

        for (
                ConsultationReportAiResult.RequiredDocument document
                : result.requiredDocuments
        ) {

            String normalized =
                    document.name
                            .trim()
                            .toLowerCase();

            if (!values.add(normalized)) {
                throw new AiOutputContractException(
                        "Duplicate required document"
                );
            }
        }
    }

    // 용어 중복 검증
    private void validateUniqueTerms(
            ConsultationReportAiResult result
    ) {

        Set<String> values =
                new HashSet<>();

        for (
                ConsultationReportAiResult.FinancialTerm term
                : result.terms
        ) {

            String normalized =
                    term.term
                            .trim()
                            .toLowerCase();

            if (!values.add(normalized)) {
                throw new AiOutputContractException(
                        "Duplicate financial term"
                );
            }
        }
    }
}