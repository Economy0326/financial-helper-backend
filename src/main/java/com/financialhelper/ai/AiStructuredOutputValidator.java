package com.financialhelper.ai;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;

import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.stream.Collectors;

@Component
public class AiStructuredOutputValidator {

    private final Validator validator;

    public AiStructuredOutputValidator(
            Validator validator
    ) {
        this.validator = validator;
    }

    public <T> T validate(
            T output
    ) {

        // 문자열이 비어있는지
        if (output == null) {
            throw new AiOutputContractException(
                    "AI structured output must not be null"
            );
        }

        // 객체의 필드가 유효한지
        Set<ConstraintViolation<T>> violations =
                validator.validate(output);

        if (violations.isEmpty()) {
            return output;
        }

        // 중요 내용은 제외하고 어떤 field가 문제인지 정도만 남김
        String invalidFields =
                violations.stream()
                        .map(
                                violation ->
                                        violation
                                                .getPropertyPath()
                                                .toString()
                        )
                        .distinct()
                        .sorted()
                        .collect(
                                Collectors.joining(", ")
                        );

        throw new AiOutputContractException(
                "AI structured output failed validation"
                        + (
                        invalidFields.isBlank()
                                ? ""
                                : ": " + invalidFields
                )
        );
    }
}