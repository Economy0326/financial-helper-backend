package com.financialhelper.ai;

import com.financialhelper.ai.analysis.AnalysisAiResult;
import com.financialhelper.ai.summary.ConsultationSummaryAiResult;
import com.financialhelper.ai.understanding.CaseUnderstandingAiResult;
import com.financialhelper.consultation.UnsafeConsultationInputException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AiUserInputPrivacyTest {
    @Test
    void blocksOldSensitiveSummaryBeforeAnotherProviderRequest() {
        var summary = new ConsultationSummaryAiResult();
        summary.summaryText = "카드 분실 후 연락처 010-1234-5678을 남겼어요";
        assertThatThrownBy(() -> AiUserInputPrivacy.requireSafe(summary))
                .isInstanceOf(UnsafeConsultationInputException.class);
    }

    @Test
    void blocksOldSensitiveAnalysisAndUnderstanding() {
        var analysis = new AnalysisAiResult();
        analysis.analysisSummary = "입금계좌 123-456-78901234";
        assertThatThrownBy(() -> AiUserInputPrivacy.requireSafe(analysis))
                .isInstanceOf(UnsafeConsultationInputException.class);

        var understanding = new CaseUnderstandingAiResult();
        var fact = new CaseUnderstandingAiResult.ExtractedFact();
        fact.value = "test.person@example.invalid";
        understanding.facts = java.util.List.of(fact);
        assertThatThrownBy(() -> AiUserInputPrivacy.requireSafe(understanding))
                .isInstanceOf(UnsafeConsultationInputException.class);
    }
}
