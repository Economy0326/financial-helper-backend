package com.financialhelper.account;

import com.financialhelper.guest.GuestSessionTokenService;
import com.financialhelper.guest.GuestSessionCookieService;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

@RestController
@RequestMapping("/api/v1/auth")
public class AccountAuthController {
    private static final String STATE_COOKIE = "financial_helper_oauth_state";
    private final AccountProperties properties;
    private final OAuthLoginStateRepository stateRepository;
    private final AccountSessionService sessionService;
    private final GuestSessionTokenService tokenService;
    private final GuestSessionCookieService guestSessionCookieService;
    private final KakaoOAuthClient kakaoClient;
    private final NaverOAuthClient naverClient;

    public AccountAuthController(AccountProperties properties, OAuthLoginStateRepository stateRepository,
                                 AccountSessionService sessionService, GuestSessionTokenService tokenService,
                                 GuestSessionCookieService guestSessionCookieService,
                                 KakaoOAuthClient kakaoClient, NaverOAuthClient naverClient) {
        this.properties = properties;
        this.stateRepository = stateRepository;
        this.sessionService = sessionService;
        this.tokenService = tokenService;
        this.guestSessionCookieService = guestSessionCookieService;
        this.kakaoClient = kakaoClient;
        this.naverClient = naverClient;
    }

    @GetMapping("/kakao/start")
    public ResponseEntity<Void> start(HttpServletResponse response) {
        AccountProperties.Kakao kakao = properties.kakao();
        if (!kakao.enabled() || kakao.clientId() == null || kakao.clientId().isBlank()) {
            throw AccountAuthenticationException.unavailable();
        }
        String state = tokenService.generateRawToken();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        stateRepository.save(new OAuthLoginState(AccountProvider.KAKAO, tokenService.hashToken(state), now, now.plusMinutes(10)));
        response.addHeader(HttpHeaders.SET_COOKIE, stateCookie(state, false).toString());
        String location = kakao.authorizeUrl()
                + "?response_type=code&client_id=" + encode(kakao.clientId())
                + "&redirect_uri=" + encode(kakao.redirectUri())
                + "&state=" + encode(state);
        return ResponseEntity.status(302).location(URI.create(location)).build();
    }

    @GetMapping("/kakao/callback")
    public ResponseEntity<Void> callback(@RequestParam(required = false) String code,
                                         @RequestParam(required = false) String state,
                                         @RequestParam(required = false) String error,
                                         @CookieValue(name = STATE_COOKIE, required = false) String stateCookie,
                                         HttpServletResponse response) {
        if (error != null || code == null || state == null || stateCookie == null || !state.equals(stateCookie)) {
            throw AccountAuthenticationException.invalidCallback();
        }
        String stateHash = tokenService.hashToken(state);
        OAuthLoginState loginState = stateRepository.findByStateHashAndExpiresAtAfter(
                        stateHash, OffsetDateTime.now(ZoneOffset.UTC))
                .filter(value -> value.getProvider() == AccountProvider.KAKAO)
                .orElseThrow(AccountAuthenticationException::invalidCallback);
        stateRepository.delete(loginState);
        KakaoOAuthClient.KakaoIdentity identity = kakaoClient.authenticate(code);
        AccountSessionService.LoginSession login = sessionService.createSession(
                AccountProvider.KAKAO, identity.subject(), identity.displayName());
        response.addHeader(HttpHeaders.SET_COOKIE, sessionCookie(login.rawToken(), false).toString());
        response.addHeader(HttpHeaders.SET_COOKIE, stateCookie("", true).toString());
        return ResponseEntity.status(302).location(URI.create(properties.kakao().frontendSuccessUri())).build();
    }

    @GetMapping("/naver/start")
    public ResponseEntity<Void> startNaver(HttpServletResponse response) {
        AccountProperties.Naver naver = properties.naver();
        if (!naver.enabled() || naver.clientId() == null || naver.clientId().isBlank()) {
            throw AccountAuthenticationException.unavailable();
        }
        String state = tokenService.generateRawToken();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        stateRepository.save(new OAuthLoginState(AccountProvider.NAVER, tokenService.hashToken(state), now, now.plusMinutes(10)));
        response.addHeader(HttpHeaders.SET_COOKIE, stateCookie(state, false, "/api/v1/auth/naver").toString());
        String location = naver.authorizeUrl()
                + "?response_type=code&client_id=" + encode(naver.clientId())
                + "&redirect_uri=" + encode(naver.redirectUri())
                + "&state=" + encode(state);
        return ResponseEntity.status(302).location(URI.create(location)).build();
    }

    @GetMapping("/naver/callback")
    public ResponseEntity<Void> callbackNaver(@RequestParam(required = false) String code,
                                              @RequestParam(required = false) String state,
                                              @RequestParam(required = false) String error,
                                              @CookieValue(name = STATE_COOKIE, required = false) String stateCookie,
                                              HttpServletResponse response) {
        if (error != null || code == null || state == null || stateCookie == null || !state.equals(stateCookie)) {
            throw AccountAuthenticationException.invalidCallback();
        }
        String stateHash = tokenService.hashToken(state);
        OAuthLoginState loginState = stateRepository.findByStateHashAndExpiresAtAfter(
                        stateHash, OffsetDateTime.now(ZoneOffset.UTC))
                .filter(value -> value.getProvider() == AccountProvider.NAVER)
                .orElseThrow(AccountAuthenticationException::invalidCallback);
        stateRepository.delete(loginState);
        NaverOAuthClient.NaverIdentity identity = naverClient.authenticate(code, state);
        AccountSessionService.LoginSession login = sessionService.createSession(
                AccountProvider.NAVER, identity.subject(), identity.displayName());
        response.addHeader(HttpHeaders.SET_COOKIE, sessionCookie(login.rawToken(), false).toString());
        response.addHeader(HttpHeaders.SET_COOKIE, stateCookie("", true, "/api/v1/auth/naver").toString());
        return ResponseEntity.status(302).location(URI.create(properties.naver().frontendSuccessUri())).build();
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(HttpServletRequest request,
                                       HttpServletResponse response) {
        String token = cookie(request, properties.cookieName());
        sessionService.revoke(token);
        response.addHeader(HttpHeaders.SET_COOKIE, sessionCookie("", true).toString());
        // guest session이 방금 logout한 account에 묶여 있을 수 있다.
        // 다음 account가 새로운 browser session으로 시작하도록 삭제한다.
        response.addHeader(HttpHeaders.SET_COOKIE, guestSessionCookieService.clear().toString());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/me")
    public AccountResponse me() {
        return sessionService.currentAccount().map(account ->
                new AccountResponse(true, account.getId(), account.getProvider().name(), account.getDisplayName())
        ).orElseGet(() -> new AccountResponse(false, null, null, null));
    }

    private ResponseCookie sessionCookie(String value, boolean clear) {
        java.time.Duration maxAge = clear ? java.time.Duration.ZERO : properties.sessionTtl();
        return ResponseCookie.from(properties.cookieName(), value)
                .httpOnly(true).secure(properties.cookieSecure())
                .sameSite(properties.cookieSameSite()).path("/")
                .maxAge(maxAge).build();
    }

    private ResponseCookie stateCookie(String value, boolean clear) {
        return stateCookie(value, clear, "/api/v1/auth/kakao");
    }

    private ResponseCookie stateCookie(String value, boolean clear, String path) {
        java.time.Duration maxAge = clear ? java.time.Duration.ZERO : java.time.Duration.ofMinutes(10);
        return ResponseCookie.from(STATE_COOKIE, value)
                .httpOnly(true).secure(properties.cookieSecure())
                .sameSite(properties.cookieSameSite()).path(path)
                .maxAge(maxAge).build();
    }

    private static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }

    private static String cookie(HttpServletRequest request, String name) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) return null;
        for (Cookie cookie : cookies) if (name.equals(cookie.getName())) return cookie.getValue();
        return null;
    }

    public record AccountResponse(boolean authenticated, java.util.UUID accountId,
                                  String provider, String displayName) { }
}
