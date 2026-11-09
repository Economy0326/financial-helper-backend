package com.financialhelper.consultation;

import com.financialhelper.account.AccountProperties;
import com.financialhelper.guest.GuestSession;
import com.financialhelper.guest.GuestSessionService;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class InformationIntakePrivacyTest {
    @Test
    void rejectedInputDoesNotChangeExistingTextOrRevision() {
        var sessions = mock(GuestSessionService.class);
        var repository = mock(ConsultationRepository.class);
        var guest = mock(GuestSession.class);
        var consultation = new Consultation(guest, OffsetDateTime.now(ZoneOffset.UTC));
        var id = UUID.randomUUID();
        var guestId = UUID.randomUUID();
        when(guest.getId()).thenReturn(guestId);
        when(sessions.requireValidSession("session")).thenReturn(guest);
        when(repository.findForUpdateByIdAndGuestSession_Id(id, guestId))
                .thenReturn(Optional.of(consultation));
        var service = new InformationIntakeService(sessions, repository,
                new AccountProperties(false, null, null, false, null, null, AccountProperties.Limits.defaults()));

        var initial = service.update(id, "session", "카드를 잃어버렸는데 모르는 결제가 있어요", 0);
        assertThatThrownBy(() -> service.update(id, "session", "카드를 잃어버렸고 010-1234-5678로 연락 주세요",
                initial.caseInputRevision())).isInstanceOf(UnsafeConsultationInputException.class);
        assertThat(consultation.getSituationText()).isEqualTo(initial.situationText());
        assertThat(consultation.getCaseInputRevision()).isEqualTo(initial.caseInputRevision());
        assertThat(consultation.getCurrentStep()).isEqualTo(ConsultationStep.CATEGORY);
        verify(repository, never()).save(any());
    }
}
