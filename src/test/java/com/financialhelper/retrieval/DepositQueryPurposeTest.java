package com.financialhelper.retrieval;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class DepositQueryPurposeTest {
    @Test
    void routesOnlyNarrowSinglePurposesToFixedQueries() {
        assertThat(DepositQueryPurpose.query("예금자보호 한도가 얼마인가요?"))
                .isEqualTo("예금 보호 한도");
        assertThat(DepositQueryPurpose.query("같은 은행에 예금 통장이 여러 개면 합산하나요?"))
                .isEqualTo("동일 금융기관 계좌 합산");
        assertThat(DepositQueryPurpose.query("다른 은행에 있는 예금은 어떻게 계산하나요?"))
                .isEqualTo("금융기관별 보호 한도");
        assertThat(DepositQueryPurpose.query("ISA가 예금자보호 대상인가요?"))
                .isEqualTo("보호대상 상품 운용 조건");
        assertThat(DepositQueryPurpose.query("예금 이자는 어떤 기준으로 보호되나요?"))
                .isEqualTo("원금과 소정의 이자");
        assertThat(DepositQueryPurpose.query("예금 한도와 이자를 같이 알고 싶어요"))
                .isNull();
        assertThat(DepositQueryPurpose.query("점심 메뉴가 무엇인가요?"))
                .isNull();
        assertThat(DepositQueryPurpose.hasHistoricalMarker("2024년 당시 예금보호는?"))
                .isTrue();
        assertThat(DepositQueryPurpose.hasHistoricalMarker("2025년 9월부터 예금보호한도가 올랐나요?"))
                .isFalse();
        assertThat(DepositQueryPurpose.hasHistoricalMarker("2025년 9월부터 발생한 사고에도 적용되나요?"))
                .isTrue();
        assertThat(DepositQueryPurpose.query("2025년 9월부터 예금보호한도가 올랐다고 들었는데 원금과 이자는 합쳐서 보호되나요?"))
                .isEqualTo("원금과 소정의 이자");
        assertThat(DepositQueryPurpose.questionNumbers("통장이 여러 개면 각각 보호되나요?"))
                .containsExactly(8);
        assertThat(DepositQueryPurpose.questionNumbers("같은 금융기관에 통장이 여러 개라면?"))
                .containsExactly(8);
        assertThat(DepositQueryPurpose.questionNumbers("서로 다른 금융기관의 예금은?"))
                .containsExactly(9);
        assertThat(DepositQueryPurpose.questionNumbers("ISA는 무조건 보호되나요?"))
                .containsExactly(2);
        assertThat(DepositQueryPurpose.questionNumbers("원금과 이자는 어떻게 계산하나요?"))
                .containsExactly(6);
        assertThat(DepositQueryPurpose.questionNumbers("퇴직연금에 별도 한도가 있나요?"))
                .containsExactly(10);
        assertThat(DepositQueryPurpose.questionNumbers("개인종합자산관리계좌에서 펀드와 예금의 취급이 같나요?"))
                .containsExactly(2);
        assertThat(DepositQueryPurpose.questionNumbers("보호한도가 인상되기 전에 가입한 적금도 해당되나요?"))
                .containsExactly(5);
        assertThat(DepositQueryPurpose.questionNumbers("별도보호한도 예시가 무엇인가요?"))
                .containsExactly(10);
        assertThat(DepositQueryPurpose.questionNumbers("예금 보호 최대 금액이 궁금합니다."))
                .containsExactly(6);
        assertThat(DepositQueryPurpose.questionNumbers("한도 상향액을 알려주세요."))
                .containsExactly(6);
        assertThat(DepositQueryPurpose.questionNumbers("한 은행에서 적금 두 개를 들면 한도도 두 번인가요?"))
                .containsExactly(8);
        assertThat(DepositQueryPurpose.questionNumbers("같은 금융기관 계좌들을 합쳐서 계산하나요?"))
                .containsExactly(8);
        assertThat(DepositQueryPurpose.questionNumbers("A은행과 B은행에 각각 돈을 맡기면 합산하나요?"))
                .containsExactly(9);
        assertThat(DepositQueryPurpose.questionNumbers("보호되지 않는 금융상품에는 무엇이 있나요?"))
                .containsExactly(3);
        assertThat(DepositQueryPurpose.questionNumbers("새 한도는 언제부터 시행됐나요?"))
                .containsExactly(5);
        assertThat(DepositQueryPurpose.questionNumbers("기존 예금 가입자도 새 기준을 적용받나요?"))
                .containsExactly(5);
        assertThat(DepositQueryPurpose.questionNumbers("한 곳에 통장을 여러 개 만들면 각각 보호되나요?"))
                .isEmpty();
        assertThat(DepositQueryPurpose.questionNumbers("외화예금도 보호되나요?"))
                .isEmpty();
        assertThat(DepositQueryPurpose.questionNumbers("제 펀드가 반드시 보호되나요?"))
                .isEmpty();
        assertThat(DepositQueryPurpose.questionNumbers("제가 가입한 ISA의 실제 보호금액을 알려주세요."))
                .isEmpty();
        assertThat(DepositQueryPurpose.questionNumbers("2025년 9월 이전에 발생한 제 보호사고도 새 한도가 적용되나요?"))
                .isEmpty();
        assertThat(DepositQueryPurpose.questionNumbers("특정 금융기관이 현재 보호대상인지 확정해 주세요."))
                .isEmpty();
        assertThat(DepositQueryPurpose.questionNumbers("공식 근거에 없는 배상·환급을 보장해 주세요."))
                .isEmpty();
    }
}
