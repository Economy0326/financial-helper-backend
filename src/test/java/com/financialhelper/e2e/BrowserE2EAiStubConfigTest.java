package com.financialhelper.e2e;

import com.financialhelper.ai.OpenAiStructuredClient;
import com.financialhelper.ai.summary.ConsultationSummaryAiResult;
import com.financialhelper.ai.understanding.CaseUnderstandingAiResult;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BrowserE2EAiStubConfigTest {
    @Test
    void injectsDeterministicStubAndRejectsOtherAiOutputTypes() {
        try (var context = new AnnotationConfigApplicationContext(BrowserE2EAiStubConfig.class)) {
            OpenAiStructuredClient client = context.getBean(OpenAiStructuredClient.class);
            CaseUnderstandingAiResult understanding = client.generateStructured(
                    "test", "E2E_SYNTHETIC_CASE", CaseUnderstandingAiResult.class);
            ConsultationSummaryAiResult summary = client.generateStructured(
                    "test", "E2E_SYNTHETIC_CASE", ConsultationSummaryAiResult.class);
            assertThat(understanding.facts).isEmpty();
            assertThat(understanding.missingInformation).isEmpty();
            assertThat(summary.keyPoints).hasSize(2);
            assertThatThrownBy(() -> client.generateStructured("test", "E2E_SYNTHETIC_CASE", String.class))
                    .isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> client.generateStructured("test", "real consultation", ConsultationSummaryAiResult.class))
                    .isInstanceOf(IllegalStateException.class);
        }
    }
}
