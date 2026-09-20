package com.financialhelper.common.config;

import com.financialhelper.account.AccountSessionAuthenticationFilter;
import com.financialhelper.account.RateLimitFilter;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import org.springframework.http.HttpMethod;

import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web
        .builders.HttpSecurity;

import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.security.web.csrf.CsrfException;
import org.springframework.security.web.csrf
        .CookieCsrfTokenRepository;
import org.springframework.security.web.csrf
        .CsrfTokenRequestAttributeHandler;

@Configuration
public class SecurityConfig {

    private static final Logger log =
            LoggerFactory.getLogger(SecurityConfig.class);

    @Bean
    public CookieCsrfTokenRepository csrfTokenRepository() {
        CookieCsrfTokenRepository repository =
                CookieCsrfTokenRepository.withHttpOnlyFalse();
        repository.setCookieName("XSRF-TOKEN");
        repository.setCookiePath("/");
        repository.setHeaderName("X-XSRF-TOKEN");
        return repository;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            AccountSessionAuthenticationFilter accountSessionAuthenticationFilter,
            RateLimitFilter rateLimitFilter,
            CookieCsrfTokenRepository csrfTokenRepository
    ) throws Exception {

        // browser client가 double-submit token을 읽어 X-XSRF-TOKEN에 담는다.
        // 인증 credential이 아니며 설정된 cookie/header contract를 위해 읽을 수 있어야 한다.
        CsrfTokenRequestAttributeHandler csrfRequestHandler =
                new CsrfTokenRequestAttributeHandler();
        csrfRequestHandler.setCsrfRequestAttributeName("_csrf");

        http
                .cors(Customizer.withDefaults())

                .csrf(csrf -> csrf
                        .csrfTokenRepository(
                                csrfTokenRepository
                        )
                        .csrfTokenRequestHandler(csrfRequestHandler)
                )

                .exceptionHandling(exceptions -> exceptions
                        .accessDeniedHandler((request, response, exception) -> {
                            if (exception instanceof CsrfException) {
                                log.warn(
                                        "CSRF rejected request method={} path={} headerPresent={} cookiePresent={} authenticated={}",
                                        request.getMethod(),
                                        request.getRequestURI(),
                                        request.getHeader("X-XSRF-TOKEN") != null,
                                        hasCookie(request, "XSRF-TOKEN"),
                                        request.getUserPrincipal() != null
                                );
                            }
                            response.sendError(HttpServletResponse.SC_FORBIDDEN);
                        })
                )

                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.STATELESS)
                )

                .addFilterBefore(rateLimitFilter, AnonymousAuthenticationFilter.class)
                .addFilterBefore(accountSessionAuthenticationFilter, RateLimitFilter.class)

                .authorizeHttpRequests(authorize -> authorize

                    .requestMatchers(
                            "/health", "/health/readiness", "/ready"
                    )
                    .permitAll()

                    .requestMatchers("/api/v1/auth/**")
                    .permitAll()

                    .requestMatchers("/api/v1/account/**")
                    .authenticated()

                    .requestMatchers(
                            HttpMethod.GET,
                            "/api/v1/session"
                    )
                    .permitAll()

                    .requestMatchers(
                            HttpMethod.POST,
                            "/api/v1/consultations"
                    )
                    .permitAll()

                    .requestMatchers(
                            HttpMethod.GET,
                            "/api/v1/consultations/active"
                    )
                    .permitAll()

                    .requestMatchers(
                            HttpMethod.GET,
                            "/api/v1/consultations/*"
                    )
                    .permitAll()

                    .requestMatchers(
                            HttpMethod.PUT,
                            "/api/v1/consultations/*/category",
                            "/api/v1/consultations/*/situation"
                    )
                    .permitAll()

                    .requestMatchers(
                            HttpMethod.POST,
                            "/api/v1/consultations/*/understanding",
                            "/api/v1/consultations/*/follow-up/prepare",
                            "/api/v1/consultations/*/procedure-follow-up/prepare",
                            "/api/v1/consultations/*/financial-action-plan"
                    )
                    .permitAll()

                    .requestMatchers(
                            HttpMethod.GET,
                            "/api/v1/consultations/*/procedure-follow-up",
                            "/api/v1/consultations/*/financial-action-plan"
                    )
                    .permitAll()

                    .requestMatchers(
                            HttpMethod.GET,
                            "/api/v1/consultations/*/follow-up"
                    )
                    .permitAll()

                    .requestMatchers(
                            HttpMethod.PUT,
                            "/api/v1/consultations/*/follow-up/questions/*/answer"
                    )
                    .permitAll()

                    .requestMatchers(
                            HttpMethod.PUT,
                            "/api/v1/consultations/*/procedure-follow-up/questions/*/answer"
                    )
                    .permitAll()

                    .requestMatchers(
                            HttpMethod.GET,
                            "/api/v1/consultations/*/summary"
                    )
                    .permitAll()

                    .requestMatchers(
                            HttpMethod.POST,
                            "/api/v1/consultations/*/summary/prepare",
                            "/api/v1/consultations/*/summary/confirm"
                    )
                    .permitAll()

                    .requestMatchers(
                            HttpMethod.GET,
                            "/api/v1/consultations/*/analysis"
                    )
                    .permitAll()

                    .requestMatchers(
                            HttpMethod.POST,
                            "/api/v1/consultations/*/analysis/start",
                            "/api/v1/consultations/*/analysis/retry",
                            "/api/v1/consultations/*/analysis/reopen"
                    )
                    .permitAll()

                    .requestMatchers(
                            HttpMethod.GET,
                            "/api/v1/consultations/*/analysis",
                            "/api/v1/consultations/*/analysis/supplement-context"
                    )
                    .permitAll()

                    .requestMatchers(
                            HttpMethod.GET,
                            "/api/v1/consultations/*/report"
                    )
                    .permitAll()

                    .requestMatchers(
                            HttpMethod.POST,
                            "/api/v1/consultations/*/report/prepare"
                    )
                    .permitAll()

                    .requestMatchers(
                            HttpMethod.GET,
                            "/api/v1/emergency/types",
                            "/api/v1/emergency/scenario"
                    )
                    .permitAll()

                    .requestMatchers(
                            HttpMethod.PUT,
                            "/api/v1/emergency/selection"
                    )
                    .permitAll()

                    // 정의되지 않은 나머지 요청은 모두 차단
                    .anyRequest()
                    .denyAll()
                );

        return http.build();
    }

    private static boolean hasCookie(
            HttpServletRequest request,
            String name
    ) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return false;
        }
        for (Cookie cookie : cookies) {
            if (name.equals(cookie.getName())) {
                return true;
            }
        }
        return false;
    }
}
