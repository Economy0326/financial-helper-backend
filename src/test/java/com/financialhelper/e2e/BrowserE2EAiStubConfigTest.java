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

    @Test
    void queryStubKeepsGenerationAndOnlyReturnsAllowedChunks() {
        try (var context = new AnnotationConfigApplicationContext(BrowserE2EAiStubConfig.class)) {
            var client = context.getBean(com.financialhelper.retrieval.KureRuntimeClient.class);
            var generation = java.util.UUID.randomUUID();
            var first = java.util.UUID.randomUUID();
            var second = java.util.UUID.randomUUID();
            var request = new com.financialhelper.retrieval.KureRuntimeClient.KureRuntimeQueryRequest(
                    generation, "synthetic query", 1, java.util.Set.of(first, second));
            var result = client.query(request);
            assertThat(result.generationId()).isEqualTo(generation);
            assertThat(result.results()).hasSize(1);
            assertThat(result.results().getFirst().sourceChunkId()).isIn(first, second);
            assertThat(result.results().getFirst().generationId()).isEqualTo(generation);
            assertThat(result.results().getFirst().rank()).isEqualTo(1);
            assertThat(client.query(request)).isEqualTo(result);
            assertThatThrownBy(client::metadata).isInstanceOf(IllegalStateException.class);
            var ai = context.getBean(OpenAiStructuredClient.class);
            assertThatThrownBy(() -> ai.generateStructured("test", "{}", com.financialhelper.ai.information.InformationExplanationAiResult.class))
                    .isInstanceOf(com.financialhelper.ai.AiProviderException.class);
        }
    }}
