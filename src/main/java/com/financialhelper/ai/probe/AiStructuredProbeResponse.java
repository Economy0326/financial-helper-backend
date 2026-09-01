package com.financialhelper.ai.probe;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

// AI 구조화 응답의 스키마를 정의하는 클래스
public class AiStructuredProbeResponse {

    @NotBlank
    @Pattern(
            regexp = "AI_CONNECTION_OK"
    )

    // Schema에 설명을 제공해서 AI에게 각 propery의 의미를 알려줌
    @JsonPropertyDescription(
            "Always return exactly AI_CONNECTION_OK."
    )
    public String status;

    @NotBlank
    @Size(max = 100)
    @JsonPropertyDescription(
            "A short Korean confirmation message."
    )
    public String message;

    public AiStructuredProbeResponse() {
    }
}