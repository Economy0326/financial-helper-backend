package com.financialhelper.ai.followup;

import com.financialhelper.ai.AiOutputContractException;

import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.Set;

@Component
public class FollowUpQuestionBusinessValidator {

    public FollowUpQuestionAiResult validate(
            FollowUpQuestionAiResult result
    ) {

        if (
                result == null
                || result.questions == null
        ) {
            throw new AiOutputContractException(
                    "Follow-up result must not be null"
            );
        }

        Set<String> questionTexts =
                new HashSet<>();

        for (
                FollowUpQuestionAiResult.Question question
                : result.questions
        ) {

            String normalizedQuestion =
                    question.question
                            .trim()
                            .toLowerCase();

            // 질문이 제대로 생성되었는지 확인
            if (
                    !questionTexts.add(
                            normalizedQuestion
                    )
            ) {
                throw new AiOutputContractException(
                        "Duplicate follow-up question"
                );
            }

            Set<String> optionValues =
                    new HashSet<>();

            boolean hasUnknown = false;

            for (
                    FollowUpQuestionAiResult.Option option
                    : question.options
            ) {

                // 옵션이 제대로 생성되었는지 확인
                if (
                        !optionValues.add(
                                option.value
                        )
                ) {
                    throw new AiOutputContractException(
                            "Duplicate follow-up option"
                    );
                }

                // UNKNOWN 옵션이 있는지 확인
                if (
                        "UNKNOWN".equals(
                                option.value
                        )
                ) {
                    hasUnknown = true;
                }
            }

            if (!hasUnknown) {
                throw new AiOutputContractException(
                        "Follow-up question must contain UNKNOWN option"
                );
            }
        }

        return result;
    }
}