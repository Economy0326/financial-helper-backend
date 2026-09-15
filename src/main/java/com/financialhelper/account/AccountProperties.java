package com.financialhelper.account;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

import java.time.Duration;

@ConfigurationProperties(prefix = "app.auth")
public record AccountProperties(
        boolean generalConsultationRequired,
        Duration sessionTtl,
        String cookieName,
        boolean cookieSecure,
        String cookieSameSite,
        Kakao kakao,
        Naver naver,
        Limits limits
) {
    @ConstructorBinding
    public AccountProperties {
        if (limits == null) {
            limits = Limits.defaults();
        }
    }

    /** Compatibility constructor for existing callers that only configure Kakao. */
    public AccountProperties(boolean generalConsultationRequired,
                             Duration sessionTtl,
                             String cookieName,
                             boolean cookieSecure,
                             String cookieSameSite,
                             Kakao kakao,
                             Limits limits) {
        this(generalConsultationRequired, sessionTtl, cookieName, cookieSecure,
                cookieSameSite, kakao, Naver.disabled(), limits);
    }

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

    public record Naver(
            boolean enabled,
            String clientId,
            String clientSecret,
            String redirectUri,
            String frontendSuccessUri,
            String authorizeUrl,
            String tokenUrl,
            String userInfoUrl,
            Duration timeout
    ) {
        public static Naver disabled() {
            return new Naver(false, "", "", "", "",
                    "https://nid.naver.com/oauth2.0/authorize",
                    "https://nid.naver.com/oauth2.0/token",
                    "https://openapi.naver.com/v1/nid/me", Duration.ofSeconds(5));
        }
    }

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
            long globalAiBudgetUnits,
            int newConsultationLimit,
            int consultationWindowDays,
            int maxActiveConsultations
    ) {
        public static Limits defaults() {
            return new Limits(
                    1000,
                    64,
                    12000,
                    262144,
                    10,
                    Duration.ofHours(1),
                    3,
                    60,
                    Duration.ofMinutes(1),
                    true,
                    false,
                    1000,
                    3,
                    7,
                    1
            );
        }

        /** Compatibility constructor for the Work 7 AI guard settings. */
        public Limits(int maxSituationCharacters, int maxFollowUpCharacters,
                      int maxAiInputCharacters, int maxRequestBytes,
                      int aiAttemptsPerWindow, Duration aiQuotaWindow,
                      int consultationAiAttempts, int rateLimitRequests,
                      Duration rateLimitWindow, boolean rateLimitEnabled,
                      boolean globalAiBudgetEnabled, long globalAiBudgetUnits) {
            this(maxSituationCharacters, maxFollowUpCharacters, maxAiInputCharacters,
                    maxRequestBytes, aiAttemptsPerWindow, aiQuotaWindow,
                    consultationAiAttempts, rateLimitRequests, rateLimitWindow,
                    rateLimitEnabled, globalAiBudgetEnabled, globalAiBudgetUnits,
                    3, 7, 1);
        }
    }
}
