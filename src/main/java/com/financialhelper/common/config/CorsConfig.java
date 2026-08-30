package com.financialhelper.common.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

@Configuration
public class CorsConfig {

    private final String allowedOrigin;

    public CorsConfig(
            @Value("${app.cors.allowed-origin}")
            String allowedOrigin
    ) {
        this.allowedOrigin = allowedOrigin;
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration =
                new CorsConfiguration();

        configuration.setAllowedOrigins(
                List.of(allowedOrigin)
        );

        configuration.setAllowedMethods(
                List.of(
                        "GET",
                        "POST",
                        "PUT",
                        "OPTIONS"
                )
        );

        // FE -> BE로 보내도 되는 Requeest Header
        configuration.setAllowedHeaders(
                List.of(
                        "Content-Type",
                        "X-XSRF-TOKEN"
                )
        );

        // BE -> FE 응답 중 JS가 읽어도 되는 Response Header
        configuration.setExposedHeaders(
                List.of(
                        "X-XSRF-TOKEN"
                )
        );

        // cookie를 사용하므로 필요함
        configuration.setAllowCredentials(true);

        UrlBasedCorsConfigurationSource source =
                new UrlBasedCorsConfigurationSource();

        source.registerCorsConfiguration(
                "/api/**",
                configuration
        );

        return source;
    }
}