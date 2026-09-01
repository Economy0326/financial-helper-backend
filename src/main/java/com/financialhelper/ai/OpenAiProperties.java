package com.financialhelper.ai;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

// application.yml을 Java 객체로 매핑하기 위해 사용되는 클래스
@ConfigurationProperties(prefix = "app.ai.openai")
// 환경변수/설정값을 읽어오는 역할
public record OpenAiProperties(
        String apiKey,
        String model,
        Duration timeout
) {

    public OpenAiProperties {

        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalArgumentException(
                    "OPENAI_API_KEY is required when AI is enabled"
            );
        }

        if (model == null || model.isBlank()) {
            throw new IllegalArgumentException(
                    "OPENAI_MODEL must not be blank"
            );
        }

        if (
                timeout == null
                || timeout.isZero()
                || timeout.isNegative()
        ) {
            throw new IllegalArgumentException(
                    "OPENAI_TIMEOUT must be positive"
            );
        }
    }
}