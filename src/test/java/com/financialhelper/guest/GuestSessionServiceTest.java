package com.financialhelper.guest;

import com.financialhelper.account.Account;
import com.financialhelper.account.AccountSessionService;
import com.financialhelper.consultation.ConsultationRepository;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GuestSessionServiceTest {

    @Test
    void sessionBootstrapIgnoresGuestCookieOwnedByAnotherAccount() {
        GuestSessionRepository guestSessions = mock(GuestSessionRepository.class);
        ConsultationRepository consultations = mock(ConsultationRepository.class);
        GuestSessionTokenService tokens = mock(GuestSessionTokenService.class);
        AccountSessionService accounts = mock(AccountSessionService.class);

        GuestSessionService service = new GuestSessionService(
                guestSessions,
                consultations,
                tokens,
                Duration.ofHours(1),
                accounts
        );

        GuestSession guest = new GuestSession(
                "guest-hash",
                OffsetDateTime.now(ZoneOffset.UTC),
                OffsetDateTime.now(ZoneOffset.UTC).plusHours(1)
        );
        Account owner = mock(Account.class);
        when(owner.getId()).thenReturn(UUID.randomUUID());
        guest.bindAccount(owner);

        when(tokens.hashToken(anyString())).thenReturn("guest-hash");
        when(guestSessions.findByTokenHashAndExpiresAtAfter(anyString(), any()))
                .thenReturn(Optional.of(guest));
        when(accounts.currentAccount()).thenReturn(Optional.empty());
        when(accounts.isAuthenticated()).thenReturn(false);

        SessionResponse response = service.getSessionState("stale-guest-token");

        assertThat(response.guest()).isTrue();
        assertThat(response.hasActiveConsultation()).isFalse();
        assertThat(response.authenticated()).isFalse();
    }
}
