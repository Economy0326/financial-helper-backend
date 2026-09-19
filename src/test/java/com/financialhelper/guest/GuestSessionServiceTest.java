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
import static org.mockito.Mockito.verify;

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

    @Test
    void startingAfterAccountSwitchCreatesFreshGuestSessionInsteadOfOwnershipFailure() {
        GuestSessionRepository guestSessions = mock(GuestSessionRepository.class);
        ConsultationRepository consultations = mock(ConsultationRepository.class);
        GuestSessionTokenService tokens = mock(GuestSessionTokenService.class);
        AccountSessionService accounts = mock(AccountSessionService.class);

        GuestSessionService service = new GuestSessionService(
                guestSessions, consultations, tokens, Duration.ofHours(1), accounts);

        GuestSession stale = new GuestSession(
                "stale-hash",
                OffsetDateTime.now(ZoneOffset.UTC),
                OffsetDateTime.now(ZoneOffset.UTC).plusHours(1));
        Account previousOwner = mock(Account.class);
        Account currentOwner = mock(Account.class);
        when(previousOwner.getId()).thenReturn(UUID.randomUUID());
        when(currentOwner.getId()).thenReturn(UUID.randomUUID());
        stale.bindAccount(previousOwner);

        when(tokens.hashToken("stale-token")).thenReturn("stale-hash");
        when(tokens.generateRawToken()).thenReturn("fresh-token");
        when(tokens.hashToken("fresh-token")).thenReturn("fresh-hash");
        when(guestSessions.findByTokenHashAndExpiresAtAfter(anyString(), any()))
                .thenReturn(Optional.of(stale));
        when(guestSessions.save(any(GuestSession.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(accounts.currentAccount()).thenReturn(Optional.of(currentOwner));

        GuestSessionResolution result = service.resolveOrCreate("stale-token");

        assertThat(result.getRawTokenToSet()).contains("fresh-token");
        assertThat(result.getGuestSession()).isNotSameAs(stale);
        verify(guestSessions).save(any(GuestSession.class));
    }
}
