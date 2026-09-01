package com.financialhelper.ai;

import com.financialhelper.ai.probe.AiStructuredProbeResponse;

import jakarta.validation.Validation;
import jakarta.validation.Validator;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// AI 구조화 응답이 정한 Contract에 맞는지 검증
class AiStructuredOutputValidatorTest {

    private final Validator beanValidator =
            Validation.buildDefaultValidatorFactory()
                    .getValidator();

    private final AiStructuredOutputValidator validator =
            new AiStructuredOutputValidator(
                    beanValidator
            );

    // 정상 응답은 그대로 통과하는지
    @Test
    void returnsOutputWhenStructuredResultIsValid() {

        AiStructuredProbeResponse output =
                new AiStructuredProbeResponse();

        output.status =
                "AI_CONNECTION_OK";

        output.message =
                "구조화 응답 연결이 정상입니다.";

        AiStructuredProbeResponse result =
                validator.validate(output);

        assertThat(result)
                .isSameAs(output);
    }

    // status가 잘못됐을 때 예외 발생
    @Test
    void rejectsOutputWhenBusinessValidationFails() {

        AiStructuredProbeResponse output =
                new AiStructuredProbeResponse();

        output.status =
                "WRONG_STATUS";

        output.message =
                "구조화 응답 연결이 정상입니다.";

        assertThatThrownBy(
                () ->
                        validator.validate(output)
        )
                .isInstanceOf(
                        AiOutputContractException.class
                )
                .hasMessageContaining(
                        "status"
                );
    }

    // 응답 자체가 null일 때 예외 발생
    @Test
    void rejectsNullOutput() {

        assertThatThrownBy(
                () ->
                        validator.validate(null)
        )
                .isInstanceOf(
                        AiOutputContractException.class
                );
    }
}