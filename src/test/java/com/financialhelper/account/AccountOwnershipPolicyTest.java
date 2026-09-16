package com.financialhelper.account;

import com.financialhelper.consultation.ConsultationRepository;
import com.financialhelper.consultation.ConsultationService;
import com.financialhelper.guest.GuestSessionService;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AccountOwnershipPolicyTest {
    @Test
    void general_consultation_requires_authenticated_account() {
        AccountSessionService accountSession = mock(AccountSessionService.class);
        when(accountSession.currentAccount()).thenReturn(Optional.empty());
        AccountProperties properties = new AccountProperties(
                true, Duration.ofHours(12), "account", false, "Lax",
                new AccountProperties.Kakao(false, "", "", "", "", "https://kauth.kakao.com/oauth/authorize",
                        "https://kauth.kakao.com/oauth/token", "https://kapi.kakao.com/v2/user/me", Duration.ofSeconds(5)),
                new AccountProperties.Limits(1000, 64, 12000, 262144, 10, Duration.ofHours(1),
                        3, 60, Duration.ofMinutes(1), false, false, 1000));
        ConsultationService service = new ConsultationService(
                mock(ConsultationRepository.class), mock(GuestSessionService.class), accountSession, properties);

        assertThatThrownBy(() -> service.startConsultation(null))
                .isInstanceOf(AccountAuthenticationException.class)
                .hasMessageContaining("로그인");
    }
}
