package com.financialhelper.health;

import com.financialhelper.ai.OpenAiProperties;
import com.financialhelper.account.AccountProperties;
import com.financialhelper.law.KoreanLawOpenApiProperties;
import com.financialhelper.retrieval.KureRuntimeProperties;
import com.financialhelper.retrieval.KureRuntimeClient;
import com.financialhelper.retrieval.KureRuntimeCompatibility;
import com.financialhelper.source.ActiveRetrievalGeneration;
import com.financialhelper.source.ActiveRetrievalGenerationRepository;
import com.financialhelper.source.RetrievalGeneration;
import com.financialhelper.source.RetrievalGenerationStatus;
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
    private final KureRuntimeClient kureRuntime;
    private final KureRuntimeCompatibility kureCompatibility;
    private final ActiveRetrievalGenerationRepository activeGenerationRepository;

    public ReadinessService(HealthService healthService, OpenAiProperties openAiProperties,
                            AccountProperties accountProperties,
                            KoreanLawOpenApiProperties lawProperties,
                            KureRuntimeProperties kureProperties,
                            KureRuntimeClient kureRuntime,
                            KureRuntimeCompatibility kureCompatibility,
                            ActiveRetrievalGenerationRepository activeGenerationRepository) {
        this.healthService = healthService;
        this.openAiProperties = openAiProperties;
        this.accountProperties = accountProperties;
        this.lawProperties = lawProperties;
        this.kureProperties = kureProperties;
        this.kureRuntime = kureRuntime;
        this.kureCompatibility = kureCompatibility;
        this.activeGenerationRepository = activeGenerationRepository;
    }

    public ReadinessResult check() {
        Map<String, String> result = new LinkedHashMap<>();
        boolean database = healthService.isDatabaseUp();
        result.put("database", database ? "UP" : "DOWN");
        result.put("openai", openAiProperties.apiKey() == null || openAiProperties.apiKey().isBlank() ? "MISSING" : "CONFIGURED");
        boolean kureReady = true;
        if (kureProperties.enabled()) {
            KureReadiness kure = checkKure();
            result.put("kure", kure.status());
            kureReady = kure.ready();
        } else {
            result.put("kure", "DISABLED");
        }
        result.put("koreanLawOpenApi", lawProperties.enabled()
                ? (lawProperties.lawOc() == null || lawProperties.lawOc().isBlank() ? "MISSING" : "CONFIGURED")
                : "DISABLED");
        boolean kakaoConfigured = !blank(accountProperties.kakao().clientId())
                && !blank(accountProperties.kakao().clientSecret())
                && !blank(accountProperties.kakao().redirectUri());
        result.put("kakao", accountProperties.kakao().enabled()
                ? (kakaoConfigured ? "CONFIGURED" : "MISSING")
                : "DISABLED");
        boolean naverConfigured = !blank(accountProperties.naver().clientId())
                && !blank(accountProperties.naver().clientSecret())
                && !blank(accountProperties.naver().redirectUri());
        result.put("naver", accountProperties.naver().enabled()
                ? (naverConfigured ? "CONFIGURED" : "MISSING")
                : "DISABLED");
        boolean ready = database
                && (!lawProperties.enabled() || !blank(lawProperties.lawOc()))
                && (!kureProperties.enabled() || kureReady)
                && !blank(openAiProperties.apiKey())
                && (!accountProperties.kakao().enabled() || kakaoConfigured)
                && (!accountProperties.naver().enabled() || naverConfigured);
        return new ReadinessResult(ready ? "READY" : "NOT_READY", Map.copyOf(result));
    }

    private static boolean blank(String value) { return value == null || value.isBlank(); }

    private KureReadiness checkKure() {
        try {
            ActiveRetrievalGeneration active = activeGenerationRepository.findSingleton().orElse(null);
            if (active == null) {
                return new KureReadiness("NO_ACTIVE_GENERATION", false);
            }
            RetrievalGeneration generation = active.getRetrievalGeneration();
            if (generation == null || generation.getStatus() != RetrievalGenerationStatus.READY) {
                return new KureReadiness("GENERATION_NOT_READY", false);
            }
            kureCompatibility.requireCompatible(generation, kureRuntime.metadata());
            KureRuntimeClient.KureRuntimeReadiness readiness =
                    kureRuntime.readiness(generation.getId());
            if (!readiness.ready()
                    || !generation.getId().equals(readiness.generationId())
                    || readiness.documentCount() != generation.getReadyChunkCount()) {
                return new KureReadiness("ARTIFACT_MISMATCH", false);
            }
            return new KureReadiness("READY", true);
        } catch (RuntimeException exception) {
            // Readiness must fail closed without exposing external error text.
            return new KureReadiness("UNAVAILABLE", false);
        }
    }

    private record KureReadiness(String status, boolean ready) { }

    public record ReadinessResult(String status, Map<String, String> dependencies) { }
}
