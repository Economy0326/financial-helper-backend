package com.financialhelper.health;

import com.financialhelper.ai.OpenAiProperties;
import com.financialhelper.account.AccountProperties;
import com.financialhelper.law.KoreanLawOpenApiProperties;
import com.financialhelper.retrieval.KureRuntimeProperties;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class ReadinessService {
    private final HealthService healthService;
    private final OpenAiProperties openAiProperties;
    private final AccountProperties accountProperties;
    private final KoreanLawOpenApiProperties lawProperties;
    private final KureRuntimeProperties kureProperties;

    public ReadinessService(HealthService healthService, OpenAiProperties openAiProperties,
                            AccountProperties accountProperties,
                            KoreanLawOpenApiProperties lawProperties,
                            KureRuntimeProperties kureProperties) {
        this.healthService = healthService;
        this.openAiProperties = openAiProperties;
        this.accountProperties = accountProperties;
        this.lawProperties = lawProperties;
        this.kureProperties = kureProperties;
    }

    public ReadinessResult check() {
        Map<String, String> result = new LinkedHashMap<>();
        boolean database = healthService.isDatabaseUp();
        result.put("database", database ? "UP" : "DOWN");
        result.put("openai", openAiProperties.apiKey() == null || openAiProperties.apiKey().isBlank() ? "MISSING" : "CONFIGURED");
        result.put("kure", kureProperties.enabled() ? "CONFIGURED" : "DISABLED");
        result.put("koreanLawOpenApi", lawProperties.enabled()
                ? (lawProperties.lawOc() == null || lawProperties.lawOc().isBlank() ? "MISSING" : "CONFIGURED")
                : "DISABLED");
        boolean kakaoConfigured = !blank(accountProperties.kakao().clientId())
                && !blank(accountProperties.kakao().clientSecret())
                && !blank(accountProperties.kakao().redirectUri());
        result.put("kakao", accountProperties.kakao().enabled()
                ? (kakaoConfigured ? "CONFIGURED" : "MISSING")
                : "DISABLED");
        boolean ready = database
                && (!lawProperties.enabled() || !blank(lawProperties.lawOc()))
                && (!kureProperties.enabled() || !blank(kureProperties.endpoint()))
                && !blank(openAiProperties.apiKey())
                && (!accountProperties.kakao().enabled() || kakaoConfigured);
        return new ReadinessResult(ready ? "READY" : "NOT_READY", Map.copyOf(result));
    }

    private static boolean blank(String value) { return value == null || value.isBlank(); }
    public record ReadinessResult(String status, Map<String, String> dependencies) { }
}
