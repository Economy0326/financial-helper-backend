package com.financialhelper.consultation;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class InformationIntakeClassifierTest {
    @Test void requiresCombinedSignalsAndKeepsMixedCandidates() {
        assertThat(InformationIntakeClassifier.candidates("카드를 잃어버렸는데 모르는 결제가 있어요"))
                .containsExactly(ConsultationScenario.CARD_LOSS_UNAUTHORIZED_USE);
        assertThat(InformationIntakeClassifier.candidates("보이스피싱인 것 같아서 송금했어요"))
                .containsExactly(ConsultationScenario.VOICE_PHISHING_SUSPICIOUS_TRANSFER);
        assertThat(InformationIntakeClassifier.candidates("제 계좌에서 제가 하지 않은 이체가 발생했어요"))
                .containsExactly(ConsultationScenario.UNAUTHORIZED_ACCOUNT_TRANSFER);
        assertThat(InformationIntakeClassifier.candidates("이상한 문자 링크를 눌렀어요"))
                .containsExactly(ConsultationScenario.PERSONAL_INFO_SMISHING_MALICIOUS_APP);
        assertThat(InformationIntakeClassifier.candidates("문자 링크를 눌렀고 보이스피싱으로 송금했어요"))
                .containsExactly(ConsultationScenario.VOICE_PHISHING_SUSPICIOUS_TRANSFER,
                        ConsultationScenario.PERSONAL_INFO_SMISHING_MALICIOUS_APP);
        assertThat(InformationIntakeClassifier.candidates("카드" )).isEmpty();
        assertThat(InformationIntakeClassifier.candidates("점심 메뉴 알려줘" )).isEmpty();
        assertThat(InformationIntakeClassifier.candidates("카드를 잃어버리지 않았어요")).isEmpty();
        assertThat(InformationIntakeClassifier.candidates("보이스피싱 같았지만 송금하지 않았어요")).isEmpty();
    }
}
