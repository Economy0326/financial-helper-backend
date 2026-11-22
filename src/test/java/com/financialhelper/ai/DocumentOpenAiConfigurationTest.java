package com.financialhelper.ai;

import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class DocumentOpenAiConfigurationTest {
    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withUserConfiguration(OpenAiConfig.class, OpenAiStructuredClient.class, AiStructuredOutputValidator.class)
            .withBean(Validator.class, () -> mock(Validator.class))
            .withPropertyValues("app.ai.openai.api-key=synthetic", "app.ai.openai.model=synthetic", "app.ai.openai.timeout=1s");

    @Test void documentProviderCanBeEnabledWithoutLegacyAi() {
        context.withPropertyValues("app.ai.enabled=false", "app.ai.document-explanations-enabled=true", "app.ai.document-semantic-verification-enabled=true")
                .run(c -> {
                    assertThat(c).hasSingleBean(OpenAiStructuredClient.class);
                });
    }
    @Test void verifierIsRequiredForDocumentProvider() {
        context.withPropertyValues("app.ai.enabled=false", "app.ai.document-explanations-enabled=true", "app.ai.document-semantic-verification-enabled=false")
                .run(c -> assertThat(c).doesNotHaveBean(OpenAiStructuredClient.class));
    }
}
