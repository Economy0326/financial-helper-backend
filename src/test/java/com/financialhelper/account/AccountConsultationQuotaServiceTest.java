package com.financialhelper.account;

import com.financialhelper.consultation.Consultation;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AccountConsultationQuotaServiceTest {
    private static final OffsetDateTime NOW = OffsetDateTime.of(
            2026, 9, 15, 12, 0, 0, 0, ZoneOffset.UTC);

    @Test
    void records_one_new_consultation_without_consuming_follow_up_steps() {
        AccountConsultationStartRepository starts = mock(AccountConsultationStartRepository.class);
        when(starts.countSince(any(), any())).thenReturn(0L);
        when(starts.existsByConsultation_Id(any())).thenReturn(false);

        AccountConsultationQuotaService service =
                new AccountConsultationQuotaService(starts, properties());
        Account account = mock(Account.class);
        Consultation consultation = new Consultation(null, NOW);

        service.recordNewConsultation(account, consultation, NOW);

        verify(starts).save(any(AccountConsultationStart.class));
    }

    @Test
    void rejects_the_sixth_new_consultation_inside_rolling_window() {
        AccountConsultationStartRepository starts = mock(AccountConsultationStartRepository.class);
        when(starts.countSince(any(), any())).thenReturn(5L);
        when(starts.findOldestSince(any(), any())).thenReturn(Optional.of(NOW.minusDays(2)));

        AccountConsultationQuotaService service =
                new AccountConsultationQuotaService(starts, properties());
        Account account = mock(Account.class);
        Consultation consultation = new Consultation(null, NOW);

        assertThatThrownBy(() -> service.recordNewConsultation(account, consultation, NOW))
                .isInstanceOf(AccountConsultationQuotaExceededException.class)
                .hasMessageContaining("상담 시작 한도");
    }

    @Test
    void retry_for_the_same_consultation_is_idempotent_even_when_quota_is_full() {
        AccountConsultationStartRepository starts = mock(AccountConsultationStartRepository.class);
        when(starts.existsByConsultation_Id(any())).thenReturn(true);

        AccountConsultationQuotaService service =
                new AccountConsultationQuotaService(starts, properties());
        Account account = mock(Account.class);
        Consultation consultation = new Consultation(null, NOW);

        service.recordNewConsultation(account, consultation, NOW);

        verify(starts).existsByConsultation_Id(any());
        verifyNoInteractions(account);
    }

    @Test
    void reports_zero_of_five_and_five_of_five_from_the_backend_limit() {
        AccountConsultationStartRepository starts = mock(AccountConsultationStartRepository.class);
        Account account = mock(Account.class);
        AccountConsultationQuotaService service =
                new AccountConsultationQuotaService(starts, properties());

        when(starts.countSince(any(), any())).thenReturn(0L);
        assertThat(service.status(account, NOW)).extracting(
                AccountConsultationQuotaService.QuotaStatus::used,
                AccountConsultationQuotaService.QuotaStatus::limit
        ).containsExactly(0, 5);

        when(starts.countSince(any(), any())).thenReturn(5L);
        when(starts.findOldestSince(any(), any())).thenReturn(Optional.of(NOW.minusDays(2)));
        assertThat(service.status(account, NOW)).extracting(
                AccountConsultationQuotaService.QuotaStatus::used,
                AccountConsultationQuotaService.QuotaStatus::limit
        ).containsExactly(5, 5);
    }

    @Test
    void default_account_limits_use_five_new_consultations() {
        assertThat(AccountProperties.Limits.defaults().newConsultationLimit())
                .isEqualTo(5);
    }

    private static AccountProperties properties() {
        return new AccountProperties(
                false, Duration.ofHours(12), "account", false, "Lax",
                new AccountProperties.Kakao(false, "", "", "", "",
                        "https://kauth.kakao.com/oauth/authorize",
                        "https://kauth.kakao.com/oauth/token",
                        "https://kapi.kakao.com/v2/user/me", Duration.ofSeconds(5)),
                new AccountProperties.Limits(1000, 64, 12000, 262144, 10,
                        Duration.ofHours(1), 3, 60, Duration.ofMinutes(1), false,
                        false, 1000, 5, 7, 1));
    }
}
