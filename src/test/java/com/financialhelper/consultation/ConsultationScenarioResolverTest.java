package com.financialhelper.consultation;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ConsultationScenarioResolverTest {
    @Test
    void explicitSituationMapsEachBreadthScenarioWithoutCrossScenarioInference() {
        assertThat(ConsultationScenarioResolver.resolve(ConsultationCategory.FINANCIAL_FRAUD,
                "상대방 지시에 속아서 송금했어요."))
                .isEqualTo(ConsultationScenario.VOICE_PHISHING_SUSPICIOUS_TRANSFER);
        assertThat(ConsultationScenarioResolver.resolve(ConsultationCategory.FINANCIAL_FRAUD,
                "내가 하지 않은 계좌이체가 있어요."))
                .isEqualTo(ConsultationScenario.UNAUTHORIZED_ACCOUNT_TRANSFER);
        assertThat(ConsultationScenarioResolver.resolve(ConsultationCategory.FINANCIAL_FRAUD,
                "모르는 송금이 있어요."))
                .isEqualTo(ConsultationScenario.UNAUTHORIZED_ACCOUNT_TRANSFER);
        assertThat(ConsultationScenarioResolver.resolve(ConsultationCategory.FINANCIAL_FRAUD,
                "스미싱 링크를 눌렀어요."))
                .isEqualTo(ConsultationScenario.PERSONAL_INFO_SMISHING_MALICIOUS_APP);
    }
}
