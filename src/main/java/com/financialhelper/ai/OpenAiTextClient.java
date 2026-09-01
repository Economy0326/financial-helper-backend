package com.financialhelper.ai;

import com.openai.client.OpenAIClient;
import com.openai.models.responses.Response;
import com.openai.models.responses.ResponseCreateParams;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.stream.Collectors;

@Component
@ConditionalOnProperty(
        prefix = "app.ai",
        name = "enabled",
        havingValue = "true"
)
public class OpenAiTextClient {

    // OpenAIClient -> 실제 OpenAI API 호출 담당
    private final OpenAIClient client;
    // OpenAiProperties -> OpenAI API 설정값 보관
    private final OpenAiProperties properties;

    public OpenAiTextClient(
            OpenAIClient client,
            OpenAiProperties properties
    ) {
        this.client = client;
        this.properties = properties;
    }

    // 입력을 보내고 String 결과를 받음
    public String generateText(
            String input
    ) {

        // 요청 객체(params) 만들어서
        ResponseCreateParams params =
                ResponseCreateParams.builder()
                        .model(
                                properties.model()
                        )
                        .input(
                                input
                        )
                        .build();

        // 실제 API 호출
        Response response =
                client.responses()
                        .create(params);

        String text =
                response.output()
                        // stream => 여러 개를 하나씩 처리하기 시작
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
                        .map(
                                outputText ->
                                        // 최종 문자열
                                        outputText.text()
                        )
                        .collect(
                                // 최종 문자열들을 합쳐서 하나의 String으로 만듦
                                Collectors.joining("\n")
                        )
                        .trim();

        if (text.isBlank()) {
            throw new IllegalStateException(
                    "OpenAI returned no text output"
            );
        }

        return text;
    }
}