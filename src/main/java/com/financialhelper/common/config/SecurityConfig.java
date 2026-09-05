package com.financialhelper.common.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import org.springframework.http.HttpMethod;

import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web
        .builders.HttpSecurity;

import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf
        .CookieCsrfTokenRepository;

@Configuration
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http
    ) throws Exception {

        CookieCsrfTokenRepository csrfTokenRepository =
                new CookieCsrfTokenRepository();

        csrfTokenRepository.setCookiePath("/");

        http
                .cors(Customizer.withDefaults())

                .csrf(csrf -> csrf
                        .csrfTokenRepository(
                                csrfTokenRepository
                        )
                )

                .authorizeHttpRequests(authorize -> authorize

                    .requestMatchers(
                            "/health"
                    )
                    .permitAll()

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
                            "/api/v1/consultations/*/follow-up/prepare"
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

                    // 정의되지 않은 나머지 요청은 모두 차단
                    .anyRequest()
                    .denyAll()
                );

        return http.build();
    }
}