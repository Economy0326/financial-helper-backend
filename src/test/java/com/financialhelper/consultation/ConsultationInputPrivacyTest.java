package com.financialhelper.consultation;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConsultationInputPrivacyTest {
    @Test void rejectionUsesStableSafeApiCode() {
        var exception = new UnsafeConsultationInputException();
        assertThat(exception.getCode()).isEqualTo("CONSULTATION_SENSITIVE_INPUT");
        assertThat(exception.getStatus().value()).isEqualTo(400);
        assertThat(exception.getMessage()).doesNotContain("123456", "010-");
    }
    @Test void rejectsSyntheticIdentifiersAndMixedFinancialQuestions() {
        for (String value : new String[] {
                "테스트 주민번호 900101-1 234567이 들어 있어요",
                "카드를 잃어버렸고 카드번호 4111 1111 1111 1111입니다",
                "입금계좌 123-456-78901234로 송금했어요",
                "보이스피싱 문자의 인증번호는 123456입니다",
                "비밀번호: TestOnly77!",
                "Bearer syntheticTokenValue123",
                "연락처 010-1234-5678로 연락이 왔어요",
                "집 전화 02-123-4567로 전화가 왔어요",
                "이메일 test.person@example.invalid로 받았어요",
                "집주소 서울시 테스트로 12에서 문자를 받았어요"
        }) {
            assertThatThrownBy(() -> ConsultationInputPrivacy.requireSafe(value))
                    .isInstanceOf(UnsafeConsultationInputException.class)
                    .hasMessageNotContaining(value);
        }
    }

    @Test void permitsOrdinaryFinancialFacts() {
        for (String value : new String[] {
                "카드를 잃어버렸는데 12만 원 결제가 있어요",
                "2026-11-09에 3개월 할부 결제가 있었어요",
                "KB국민카드 신용카드로 결제했어요",
                "제 계좌에서 제가 하지 않은 이체가 발생했어요"
        }) {
            assertThatCode(() -> ConsultationInputPrivacy.requireSafe(value)).doesNotThrowAnyException();
        }
    }
}
