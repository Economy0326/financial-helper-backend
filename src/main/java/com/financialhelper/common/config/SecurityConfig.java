package com.financialhelper.common.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;

import org.springframework.security.config.Customizer;

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
                        ).permitAll()
                        .requestMatchers(
                                HttpMethod.GET,
                                "/api/v1/session"
                        ).permitAll()
                        .requestMatchers(
                                HttpMethod.POST,
                                "/api/v1/consultations"
                        ).permitAll()
                        .requestMatchers(
                                HttpMethod.GET,
                                "/api/v1/consultations/active"
                        ).permitAll()
                        .requestMatchers(
                                HttpMethod.PUT,
                                "/api/v1/consultations/*/category",
                                "/api/v1/consultations/*/situation"
                        ).permitAll()
                        .anyRequest().denyAll()
                );

        return http.build();
    }
}