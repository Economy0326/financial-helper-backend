package com.financialhelper.ai.analysis;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(
        prefix = "app.ai.analysis"
)
public record AnalysisProperties(
        // 비동기 Analysis Job의 stuck 상태 판단 기준 시간
        Duration staleAfter,
        // 최대 retry 시도
        int maxAttempts
) {

    public AnalysisProperties {

        if (
                staleAfter == null
                || staleAfter.isZero()
                || staleAfter.isNegative()
        ) {
            throw new IllegalArgumentException(
                    "AI analysis stale-after must be positive"
            );
        }

        if (
                maxAttempts < 1
                || maxAttempts > 10
        ) {
            throw new IllegalArgumentException(
                    "AI analysis max-attempts must be between 1 and 10"
            );
        }
    }
}