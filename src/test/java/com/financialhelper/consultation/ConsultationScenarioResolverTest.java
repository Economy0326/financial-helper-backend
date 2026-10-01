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
                "제가 하지 않은 국내 계좌이체가 있어요."))
                .isEqualTo(ConsultationScenario.UNAUTHORIZED_ACCOUNT_TRANSFER);
        assertThat(ConsultationScenarioResolver.resolve(ConsultationCategory.FINANCIAL_FRAUD,
                "모르는 송금이 있어요."))
                .isEqualTo(ConsultationScenario.UNAUTHORIZED_ACCOUNT_TRANSFER);
        assertThat(ConsultationScenarioResolver.resolve(ConsultationCategory.FINANCIAL_FRAUD,
                "스미싱 링크를 눌렀어요."))
                .isEqualTo(ConsultationScenario.PERSONAL_INFO_SMISHING_MALICIOUS_APP);
    }

    @Test
    void keepsCardPaymentWordingInCardScenario() {
        assertThat(ConsultationScenarioResolver.resolve(
                ConsultationCategory.CARD,
                "카드는 가지고 있는데 본인이 하지 않은 카드 결제가 있어요."))
                .isEqualTo(ConsultationScenario.CARD_LOSS_UNAUTHORIZED_USE);
    }

    @Test
    void keeps_explicit_account_transfer_out_of_card_payment_branch() {
        assertThat(ConsultationScenarioResolver.resolve(
                ConsultationCategory.CARD,
                "카드도 있지만 제가 하지 않은 계좌이체가 있어요."))
                .isEqualTo(ConsultationScenario.UNAUTHORIZED_ACCOUNT_TRANSFER);
    }

    @Test
    void phoneDirectedTransferIsVoicePhishingRatherThanUnsupportedCardScope() {
        assertThat(ConsultationScenarioResolver.resolve(ConsultationCategory.CARD,
                "모르는 사람이 전화해서 시키는 대로 계좌이체했어요."))
                .isEqualTo(ConsultationScenario.VOICE_PHISHING_SUSPICIOUS_TRANSFER);
    }

    @Test
    void explicitCoercionOutranksGenericTransferTermsButNotAnUnauthorizedConflict() {
        assertThat(ConsultationScenarioResolver.resolve(ConsultationCategory.FINANCIAL_FRAUD,
                "상대방 지시에 따라 돈을 송금했어요."))
                .isEqualTo(ConsultationScenario.VOICE_PHISHING_SUSPICIOUS_TRANSFER);
        assertThat(ConsultationScenarioResolver.resolve(ConsultationCategory.FINANCIAL_FRAUD,
                "통장에서 모르는 출금이 발견됐어요."))
                .isEqualTo(ConsultationScenario.UNAUTHORIZED_ACCOUNT_TRANSFER);
        assertThat(ConsultationScenarioResolver.resolve(ConsultationCategory.FINANCIAL_FRAUD,
                "보이스피싱인지 모르겠지만 제가 하지 않은 계좌이체가 있어요."))
                .isEqualTo(ConsultationScenario.UNKNOWN);
    }

    @Test
    void domesticModifierDoesNotTurnAnExplicitUnauthorizedTransferIntoAmbiguity() {
        assertThat(ConsultationScenarioResolver.resolve(ConsultationCategory.FINANCIAL_FRAUD,
                "제가 하지 않은 국내 계좌이체가 있어요."))
                .isEqualTo(ConsultationScenario.UNAUTHORIZED_ACCOUNT_TRANSFER);
        assertThat(ConsultationScenarioResolver.resolve(ConsultationCategory.FINANCIAL_FRAUD,
                "모르는 사람이 전화로 시키는 대로 계좌이체했어요."))
                .isEqualTo(ConsultationScenario.VOICE_PHISHING_SUSPICIOUS_TRANSFER);
    }
}
