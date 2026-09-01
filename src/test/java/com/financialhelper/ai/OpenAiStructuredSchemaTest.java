package com.financialhelper.ai;

import com.financialhelper.ai.probe.AiStructuredProbeResponse;

import com.openai.models.responses.ResponseCreateParams;
import com.openai.models.responses.StructuredResponseCreateParams;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OpenAiStructuredSchemaTest {

    // API 호출 없이 Structured Output Schema 생성 가능 여부 확인
    @Test
    void buildsStructuredOutputSchemaWithoutNetworkCall() {

        StructuredResponseCreateParams<AiStructuredProbeResponse> params =
                ResponseCreateParams.builder()
                        .instructions(
                                "Return the requested structured response."
                        )
                        .input(
                                "Connectivity test."
                        )
                        .text(
                                AiStructuredProbeResponse.class
                        )
                        .model(
                                "gpt-5.6-terra"
                        )
                        .store(false)
                        .build();

        assertThat(params)
                .isNotNull();
    }
}