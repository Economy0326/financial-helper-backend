package com.financialhelper.ai;

import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)

// AI_ENABLED가 true로 설정되어 있을 때만 OpenAiConfig를 활성화하도록 설정
@ConditionalOnProperty(
        prefix = "app.ai",
        name = "enabled",
        havingValue = "true"
)

@EnableConfigurationProperties(
        OpenAiProperties.class
)

public class OpenAiConfig {

    // @Bean 만들어진 OpenAIClient를 Spring이 관리
    @Bean(destroyMethod = "close")
    // OpenAIClient -> OpenAI API를 호출할 때 사용하는 인터페이스
    public OpenAIClient openAIClient(
            OpenAiProperties properties
    ) {

        // OpenAIOkHttpClient -> 실제 HTTP 통신
        return OpenAIOkHttpClient.builder()
                .apiKey(
                        properties.apiKey()
                )
                .timeout(
                        properties.timeout()
                )
                .build();
    }
}