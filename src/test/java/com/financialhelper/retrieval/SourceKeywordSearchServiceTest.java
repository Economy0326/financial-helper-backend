package com.financialhelper.retrieval;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class SourceKeywordSearchServiceTest {
    @Test void retainsAllSubstantiveTermsWhileRemovingQuestionForm() {
        assertThat(SourceKeywordSearchService.prefixQuery("예금보호 한도는 얼마인가요?"))
                .isEqualTo("예금보호:* & 한도:*");
    }
    @Test void refusesAnUnderspecifiedSingleTerm() {
        assertThat(SourceKeywordSearchService.prefixQuery("기존" )).isNull();
    }
    @Test void doesNotDiscardUnknownProductOrCondition() {
        assertThat(SourceKeywordSearchService.prefixQuery("청년도약계좌 가입 조건은 어떻게 되나요?"))
                .contains("청년도약계좌:*").contains("가입:*").contains("조건:*").contains("되나요:*");
    }
}
