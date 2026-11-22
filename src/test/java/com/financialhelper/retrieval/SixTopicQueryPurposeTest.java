package com.financialhelper.retrieval;

import com.financialhelper.consultation.FinancialTopic;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SixTopicQueryPurposeTest {
    @Test void clearGeneralQuestionsUseFixedPurposeQueries() {
        assertThat(SixTopicQueryPurpose.resolve(FinancialTopic.CARD_PAYMENTS,
                "카드 분실 시 어디에 신고해야 하나요?").sourceKey()).isEqualTo("fsc-card-use-qa");
        assertThat(SixTopicQueryPurpose.resolve(FinancialTopic.FRAUD_SECURITY,
                "보이스피싱 피해가 발생하면 112에 어떻게 신고하나요?").sourceKey())
                .isEqualTo("police-campaign");
        assertThat(SixTopicQueryPurpose.resolve(FinancialTopic.LOANS_CREDIT,
                "2025년 1월 13일 이후 계약한 대출의 중도상환수수료 적용 조건은 무엇인가요?").sourceKey())
                .isEqualTo("fsc-early-repayment-qa");
        assertThat(SixTopicQueryPurpose.resolve(FinancialTopic.INSURANCE,
                "실손24 청구서류 전송 절차는 어떻게 되나요?").sourceKey())
                .isEqualTo("fsc-silsong24-guide");
        assertThat(SixTopicQueryPurpose.resolve(FinancialTopic.INVESTMENTS_SECURITIES,
                "투자상품의 위험과 수수료 설명의무는 무엇인가요?").sourceKey())
                .isEqualTo("law-financial-consumer-protection-19");
        assertThat(SixTopicQueryPurpose.resolve(FinancialTopic.CONSUMER_RIGHTS_DISPUTES,
                "금융민원과 분쟁조정은 어디서 신청하나요?").sourceKey())
                .isEqualTo("fsc-consumer-complaints");
    }

    @Test void individualOutcomesAndIncompleteScopeStayOutsideApprovedPurpose() {
        assertThat(SixTopicQueryPurpose.resolve(FinancialTopic.CARD_PAYMENTS,
                "제 카드의 부정사용 보상액을 보장해 주세요")).isNull();
        assertThat(SixTopicQueryPurpose.resolve(FinancialTopic.FRAUD_SECURITY,
                "보이스피싱 의심 문자를 받았어요")).isNull();
        assertThat(SixTopicQueryPurpose.resolve(FinancialTopic.LOANS_CREDIT,
                "대출 중도상환수수료는 얼마인가요?")).isNull();
        assertThat(SixTopicQueryPurpose.resolve(FinancialTopic.INSURANCE,
                "실손24에서 제 보험금 지급액을 알려주세요")).isNull();
        assertThat(SixTopicQueryPurpose.resolve(FinancialTopic.INVESTMENTS_SECURITIES,
                "제 투자상품 수수료율이 몇 %인가요?")).isNull();
        assertThat(SixTopicQueryPurpose.resolve(FinancialTopic.CONSUMER_RIGHTS_DISPUTES,
                "금융민원으로 배상을 보장받나요?")).isNull();
    }
}
