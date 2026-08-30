package com.financialhelper.guest;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Service;

import java.time.Duration;

@Service
public class GuestSessionCookieService {

    private final Duration sessionTtl;
    private final boolean secure;
    private final String sameSite;

    public GuestSessionCookieService(
            @Value("${app.guest-session.ttl}")
            Duration sessionTtl,

            @Value("${app.guest-session.cookie.secure}")
            boolean secure,

            @Value("${app.guest-session.cookie.same-site}")
            String sameSite
    ) {
        this.sessionTtl = sessionTtl;
        this.secure = secure;
        this.sameSite = sameSite;
    }

    public ResponseCookie create(String rawToken) {
        return ResponseCookie
                .from(
                        GuestSessionCookie.NAME,
                        rawToken
                )
                // JS 접근 차단
                .httpOnly(true)

                // HTTPS에서만 전송
                .secure(secure)

                // Cross-site 요청 제한
                .sameSite(sameSite)

                // 전체 경로 사용
                .path("/")

                .maxAge(sessionTtl)
                .build();
    }
}