package com.financialhelper.ai;

import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;

import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)

// 문서 설명은 기존 상담 AI와 별도로 켤 수 있으며 의미 검증도 함께 필요하다.
@ConditionalOnExpression("${app.ai.enabled:false} or (${app.ai.document-explanations-enabled:false} and ${app.ai.document-semantic-verification-enabled:false})")

@EnableConfigurationProperties(
        OpenAiProperties.class
)

// 실제 OpenAIClient를 만들어 Spring에게 등록하는 역할
public class OpenAiConfig {

    // @Bean 만들어진 OpenAIClient를 Spring이 관리
    @Bean(destroyMethod = "close")
    // OpenAIClient -> OpenAI API를 호출할 때 사용하는 인터페이스
    public OpenAIClient openAIClient(
            OpenAiProperties properties
    ) {

        if (properties.apiKey() == null || properties.apiKey().isBlank()) {
            throw new IllegalArgumentException("OPENAI_API_KEY is required when AI is enabled");
        }

        // OpenAIOkHttpClient -> 실제 HTTP 통신
        return OpenAIOkHttpClient.builder()
                .apiKey(
                        properties.apiKey()
                )
                .timeout(
                        properties.timeout()
                )
                .maxRetries(0)
                .build();
    }
}
