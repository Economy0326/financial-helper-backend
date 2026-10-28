package com.financialhelper.e2e;

import com.financialhelper.ai.OpenAiStructuredClient;
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
}
