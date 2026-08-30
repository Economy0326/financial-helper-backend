package com.financialhelper.guest;

import org.springframework.http.ResponseEntity;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/session")
public class SessionController {

    private final GuestSessionService guestSessionService;

    public SessionController(
            GuestSessionService guestSessionService
    ) {
        this.guestSessionService = guestSessionService;
    }

    @GetMapping
    public ResponseEntity<SessionResponse> getSession(
            @CookieValue(
                    name = GuestSessionCookie.NAME,
                    required = false
            )
            String rawToken,
            CsrfToken csrfToken
    ) {
        SessionResponse response =
                guestSessionService
                        .getSessionState(rawToken);

        return ResponseEntity
                .ok()
                .header(
                        csrfToken.getHeaderName(),
                        csrfToken.getToken()
                )
                .body(response);
    }
}