package com.financialhelper.e2e;

import com.financialhelper.ai.OpenAiStructuredClient;
import com.financialhelper.ai.AiProviderException;
import com.financialhelper.ai.information.InformationExplanationAiResult;
import com.financialhelper.retrieval.KureRuntimeClient;
import com.financialhelper.ai.summary.ConsultationSummaryAiResult;
import com.financialhelper.ai.understanding.CaseUnderstandingAiResult;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.util.List;

/** Only loaded by FollowUpBrowserE2EHarness from the test runtime classpath. */
@TestConfiguration(proxyBeanMethods = false)
public class BrowserE2EAiStubConfig {
    @Bean
    @Primary
    OpenAiStructuredClient browserE2eAiStub() {
        return new OpenAiStructuredClient(null, null, null) {
            @Override
            public <T> T generateStructured(String instructions, String input, Class<T> responseType) {
                if (responseType == InformationExplanationAiResult.class) {
                    throw new AiProviderException("synthetic browser E2E outage");
                }
                if (input == null || !input.contains("E2E_SYNTHETIC_")) {
                    throw new IllegalStateException("Only synthetic browser E2E input is accepted");
                }
                if (responseType == CaseUnderstandingAiResult.class) {
                    CaseUnderstandingAiResult result = new CaseUnderstandingAiResult();
                    result.facts = List.of();
                    result.missingInformation = List.of();
                    return responseType.cast(result);
                }
                if (responseType == ConsultationSummaryAiResult.class) {
                    ConsultationSummaryAiResult result = new ConsultationSummaryAiResult();
                    result.headline = "브라우저 E2E 테스트 상담";
                    result.summaryText = "E2E_SYNTHETIC_ 상담 입력을 확인하는 테스트용 요약입니다. 금융 행동을 제안하지 않습니다.";
                    ConsultationSummaryAiResult.KeyPoint first = new ConsultationSummaryAiResult.KeyPoint();
                    first.text = "테스트용 상담 입력이 저장되었습니다.";
                    ConsultationSummaryAiResult.KeyPoint second = new ConsultationSummaryAiResult.KeyPoint();
                    second.text = "추가 질문 답변이 기록되었습니다.";
                    result.keyPoints = List.of(first, second);
                    return responseType.cast(result);
                }
                throw new IllegalStateException("Unexpected AI output type in browser E2E harness");
            }
        };
    }

    /** Deterministic query-only external boundary; never contacts a KURE runtime. */
    @Bean
    @Primary
    KureRuntimeClient browserE2eKureStub() {
        return new KureRuntimeClient() {
            @Override public KureRuntimeMetadata metadata() { throw new IllegalStateException("E2E_KURE_METADATA_UNSUPPORTED"); }
            @Override public KureRuntimeBuildResult build(KureRuntimeBuildRequest request) { throw new IllegalStateException("E2E_KURE_BUILD_UNSUPPORTED"); }
            @Override public KureRuntimeReadiness readiness(java.util.UUID id) { throw new IllegalStateException("E2E_KURE_READINESS_UNSUPPORTED"); }
            @Override public KureRuntimeQueryResult query(KureRuntimeQueryRequest request) {
                var ids = request.allowedDocumentIds().stream().sorted().limit(request.topK()).toList();
                var hits = new java.util.ArrayList<KureRuntimeQueryResult.Hit>();
                for (int i = 0; i < ids.size(); i++) {
                    hits.add(new KureRuntimeQueryResult.Hit(ids.get(i), i + 1, 1.0, request.generationId()));
                }
                return new KureRuntimeQueryResult(request.generationId(), hits);
            }
        };
    }
}
