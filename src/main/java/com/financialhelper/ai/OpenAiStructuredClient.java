package com.financialhelper.ai;

import com.openai.client.OpenAIClient;
import com.openai.errors.OpenAIException;
import com.openai.errors.OpenAIInvalidDataException;
import com.openai.models.responses.ResponseCreateParams;
import com.openai.models.responses.StructuredResponse;
import com.openai.models.responses.StructuredResponseCreateParams;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;

@Component
@ConditionalOnProperty(
        prefix = "app.ai",
        name = "enabled",
        havingValue = "true"
)
// 실제로 OpenAI API를 호출하는 역할
public class OpenAiStructuredClient {

    private final OpenAIClient client;
    private final OpenAiProperties properties;
    private final AiStructuredOutputValidator outputValidator;

    public OpenAiStructuredClient(
            OpenAIClient client,
            OpenAiProperties properties,
            AiStructuredOutputValidator outputValidator
    ) {
        this.client = client;
        this.properties = properties;
        this.outputValidator = outputValidator;
    }

    public <T> T generateStructured(
            String instructions,
            String input,
            Class<T> responseType
    ) {

        validateRequestArguments(
                instructions,
                input,
                responseType
        );

        StructuredResponseCreateParams<T> params =
                ResponseCreateParams.builder()
                        // 지시문
                        .instructions(instructions)

                        // 사용자 데이터
                        .input(input)

                        // 답변 데이터 구조
                        .text(responseType)

                        // 사용 모델
                        .model(properties.model())

                        // API 측에 response를 저장하지 않도록 요청
                        // 필요한 영속 상태는 PostgreSQL에서 직접 관리
                        .store(false)
                        .build();

        try {
            StructuredResponse<T> response =
                    client.responses()
                            .create(params);

            List<T> outputs =
                    response.output()
                            .stream()
                            .flatMap(
                                    item ->
                                            item.message()
                                                    .stream()
                            )
                            .flatMap(
                                    message ->
                                            message.content()
                                                    .stream()
                            )
                            .flatMap(
                                    content ->
                                            content.outputText()
                                                    .stream()
                            )
                            .toList();

            if (outputs.size() != 1) {
                throw new AiOutputContractException(
                        "AI response must contain exactly one structured output"
                );
            }

            return outputValidator.validate(
                    outputs.getFirst()
            );

        } catch (OpenAIInvalidDataException exception) {

            /*
             * SDK의 parsing 관련 Exception message에는
             * 실제 AI JSON이 포함될 수 있다.
             *
             * 따라서 원본 exception message나
             * response body를 그대로 다시 노출하지 않는다.
             */
            throw new AiOutputContractException(
                    "AI response could not be interpreted as the expected structure"
            );

        } catch (OpenAIException exception) {

            /*
             * API Key, rate limit, network,
             * provider 5xx 등 Provider 계층 실패.
             *
             * 현재는 외부 HTTP Error Contract로
             * 바로 노출하지 않는다.
             */
            throw new AiProviderException(
                    "AI provider request failed"
            );
        }
    }

    private void validateRequestArguments(
            String instructions,
            String input,
            Class<?> responseType
    ) {

        if (
                instructions == null
                || instructions.isBlank()
        ) {
            throw new IllegalArgumentException(
                    "AI instructions must not be blank"
            );
        }

        if (
                input == null
                || input.isBlank()
        ) {
            throw new IllegalArgumentException(
                    "AI input must not be blank"
            );
        }

        Objects.requireNonNull(
                responseType,
                "AI responseType must not be null"
        );
    }
}