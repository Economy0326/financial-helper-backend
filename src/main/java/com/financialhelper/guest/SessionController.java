package com.financialhelper.guest;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;

@RestController
@RequestMapping("/api/v1/session")
public class SessionController {

    private static final String CSRF_COOKIE_NAME = "XSRF-TOKEN";
    private static final String LEGACY_CSRF_COOKIE_PATH = "/api/v1";

    private final GuestSessionService guestSessionService;
    private final CsrfTokenRepository csrfTokenRepository;

    public SessionController(
            GuestSessionService guestSessionService,
            CsrfTokenRepository csrfTokenRepository
    ) {
        this.guestSessionService = guestSessionService;
        this.csrfTokenRepository = csrfTokenRepository;
    }

    @GetMapping
    public ResponseEntity<SessionResponse> getSession(
            @CookieValue(
                    name = GuestSessionCookie.NAME,
                    required = false
            )
            String rawToken,
            HttpServletRequest request,
            HttpServletResponse servletResponse
    ) {
        // Materialize the token through the exact repository used by the
        // Security filter chain. This writes the standard Path=/ cookie for
        // both anonymous and authenticated session bootstrap requests.
        CsrfToken csrfToken = csrfTokenRepository
                .loadDeferredToken(request, servletResponse)
                .get();
        String csrfTokenValue = csrfToken.getToken();

        SessionResponse response =
                guestSessionService
                        .getSessionState(rawToken);

        servletResponse.addHeader(
                HttpHeaders.SET_COOKIE,
                ResponseCookie
                        .from(CSRF_COOKIE_NAME, "")
                        .path(LEGACY_CSRF_COOKIE_PATH)
                        .maxAge(Duration.ZERO)
                        .build()
                        .toString()
        );

        return ResponseEntity
                .ok()
                .header(
                        csrfToken.getHeaderName(),
                        csrfTokenValue
                )
                .body(response);
    }
}
