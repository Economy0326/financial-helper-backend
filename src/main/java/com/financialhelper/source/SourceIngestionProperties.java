package com.financialhelper.source;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

// Properties => 환경변수의 설정값들을 Java 객체로 묶어 놓는 클래스
@ConfigurationProperties(
        prefix = "app.source-ingestion"
)
public record SourceIngestionProperties(
        
        // 서버 시작 시 외부 기관 자동 호출
        boolean runOnStartup,

        Duration connectTimeout,
        Duration requestTimeout,
        long maxResponseBytes,
        int maxRedirects,
        String userAgent
) {

    // validation도 함께 진행
    public SourceIngestionProperties {

        if (
                connectTimeout == null
                        || connectTimeout.isNegative()
                        || connectTimeout.isZero()
        ) {
            throw new IllegalArgumentException(
                    "source ingestion connectTimeout must be positive"
            );
        }

        if (
                requestTimeout == null
                        || requestTimeout.isNegative()
                        || requestTimeout.isZero()
        ) {
            throw new IllegalArgumentException(
                    "source ingestion requestTimeout must be positive"
            );
        }

        if (maxResponseBytes <= 0) {
            throw new IllegalArgumentException(
                    "source ingestion maxResponseBytes must be positive"
            );
        }

        if (
                maxRedirects < 0
                        || maxRedirects > 10
        ) {
            throw new IllegalArgumentException(
                    "source ingestion maxRedirects must be between 0 and 10"
            );
        }

        if (
                userAgent == null
                        || userAgent.isBlank()
        ) {
            throw new IllegalArgumentException(
                    "source ingestion userAgent is required"
            );
        }
    }
}