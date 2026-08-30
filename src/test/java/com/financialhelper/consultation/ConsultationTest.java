package com.financialhelper.consultation;

import com.financialhelper.guest.GuestSession;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class ConsultationTest {

    // 실제 AI 입력이 변경된 경우에만 Revision 증가
    @Test
    void changesCaseInputRevisionOnlyWhenSourceInputActuallyChanges() {
        GuestSession guestSession =
                mock(GuestSession.class);

        OffsetDateTime now =
                OffsetDateTime.now(
                        ZoneOffset.UTC
                );

        Consultation consultation =
                new Consultation(
                        guestSession,
                        now
                );

        assertThat(
                consultation.getCaseInputRevision()
        ).isZero();

        assertThat(
                consultation.getFollowUpAnswerRevision()
        ).isZero();

        consultation.updateCategory(
                ConsultationCategory.INSURANCE,
                now.plusSeconds(1)
        );

        assertThat(
                consultation.getCaseInputRevision()
        ).isEqualTo(1L);

        consultation.updateSituation(
                "보험 해지환급금이 예상보다 적어요.",
                now.plusSeconds(2)
        );

        assertThat(
                consultation.getCaseInputRevision()
        ).isEqualTo(2L);

        assertThat(
                consultation.getCurrentStep()
        ).isEqualTo(
                ConsultationStep.FOLLOW_UP
        );

        // 같은 내용을 다시 저장하면
        // 실제 입력 변경이 아니므로 Revision 유지
        consultation.updateSituation(
                "보험 해지환급금이 예상보다 적어요.",
                now.plusSeconds(3)
        );

        assertThat(
                consultation.getCaseInputRevision()
        ).isEqualTo(2L);

        // 실제 내용이 바뀌면 Revision 증가
        consultation.updateSituation(
                "보험 해지환급금 산정 기준도 알고 싶어요.",
                now.plusSeconds(4)
        );

        assertThat(
                consultation.getCaseInputRevision()
        ).isEqualTo(3L);

        assertThat(
                consultation.getFollowUpAnswerRevision()
        ).isZero();
    }
}