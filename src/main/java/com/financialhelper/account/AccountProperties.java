package com.financialhelper.account;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "app.auth")
public record AccountProperties(
        boolean generalConsultationRequired,
        Duration sessionTtl,
        String cookieName,
        boolean cookieSecure,
        String cookieSameSite,
        Kakao kakao,
        Limits limits
) {
    public record Kakao(
            boolean enabled,
            String clientId,
            String clientSecret,
            String redirectUri,
            String frontendSuccessUri,
            String authorizeUrl,
            String tokenUrl,
            String userInfoUrl,
            Duration timeout
    ) { }

    public record Limits(
            int maxSituationCharacters,
            int maxFollowUpCharacters,
            int maxAiInputCharacters,
            int maxRequestBytes,
            int aiAttemptsPerWindow,
            Duration aiQuotaWindow,
            int consultationAiAttempts,
            int rateLimitRequests,
            Duration rateLimitWindow,
            boolean rateLimitEnabled,
            boolean globalAiBudgetEnabled,
            long globalAiBudgetUnits
    ) { }
}
