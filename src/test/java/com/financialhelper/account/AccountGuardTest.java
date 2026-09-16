package com.financialhelper.account;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.time.Duration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AccountGuardTest {

    @Test
    void global_budget_blocks_after_configured_units() {
        GlobalAiBudgetGuard guard = new GlobalAiBudgetGuard(properties(true, 1));

        guard.reserveRequestUnit();

        assertThatThrownBy(guard::reserveRequestUnit)
                .isInstanceOf(AiQuotaExceededException.class)
                .hasMessageContaining("AI 요청 한도");
    }

    @Test
    void rate_limit_is_scoped_to_ip_for_unauthenticated_api_requests() throws Exception {
        AccountSessionService sessions = mock(AccountSessionService.class);
        when(sessions.currentAccountId()).thenReturn(Optional.empty());
        RateLimitFilter filter = new RateLimitFilter(sessions, properties(false, 0));
        FilterChain chain = mock(FilterChain.class);

        MockHttpServletRequest first = request();
        MockHttpServletResponse firstResponse = new MockHttpServletResponse();
        filter.doFilter(first, firstResponse, chain);
        verify(chain).doFilter(first, firstResponse);

        MockHttpServletResponse secondResponse = new MockHttpServletResponse();
        filter.doFilter(request(), secondResponse, chain);
        assertThat(secondResponse.getStatus()).isEqualTo(429);
    }

    private static MockHttpServletRequest request() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setMethod("POST");
        request.setRequestURI("/api/v1/consultations");
        request.setRemoteAddr("127.0.0.1");
        request.addHeader("Content-Length", "4");
        return request;
    }

    private static AccountProperties properties(boolean budgetEnabled, long budgetUnits) {
        return new AccountProperties(
                false, Duration.ofHours(12), "account", false, "Lax",
                new AccountProperties.Kakao(false, "", "", "", "", "https://kauth.kakao.com/oauth/authorize",
                        "https://kauth.kakao.com/oauth/token", "https://kapi.kakao.com/v2/user/me", Duration.ofSeconds(5)),
                new AccountProperties.Limits(1000, 64, 12000, 262144, 10, Duration.ofHours(1),
                        3, 1, Duration.ofMinutes(1), true, budgetEnabled, budgetUnits));
    }
}
