package com.financialhelper.ai.analysis;

import com.financialhelper.guest.GuestSessionCookie;

import org.springframework.boot.autoconfigure.condition
        .ConditionalOnProperty;

import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping(
        "/api/v1/consultations/{consultationId}/analysis"
)
@ConditionalOnProperty(
        prefix = "app.ai",
        name = "enabled",
        havingValue = "true"
)
public class AnalysisController {

    private final AnalysisService
            analysisService;

    public AnalysisController(
            AnalysisService analysisService
    ) {
        this.analysisService =
                analysisService;
    }

    @PostMapping("/start")
    public AnalysisStateResponse start(
            @PathVariable UUID consultationId,

            @CookieValue(
                    name = GuestSessionCookie.NAME,
                    required = false
            )
            String rawToken
    ) {

        return analysisService.start(
                consultationId,
                rawToken
        );
    }

    @GetMapping
    public AnalysisStateResponse getState(
            @PathVariable UUID consultationId,

            @CookieValue(
                    name = GuestSessionCookie.NAME,
                    required = false
            )
            String rawToken
    ) {

        return analysisService.getState(
                consultationId,
                rawToken
        );
    }

    @PostMapping("/retry")
    public AnalysisStateResponse retry(
            @PathVariable UUID consultationId,

            @CookieValue(
                    name = GuestSessionCookie.NAME,
                    required = false
            )
            String rawToken
    ) {

        return analysisService.retry(
                consultationId,
                rawToken
        );
    }

    @PostMapping("/reopen")
    public ReopenAnalysisResponse reopen(
            @PathVariable UUID consultationId,

            @CookieValue(
                    name = GuestSessionCookie.NAME,
                    required = false
            )
            String rawToken
    ) {

        return analysisService
                .reopenForMoreInfo(
                        consultationId,
                        rawToken
                );
    }

    @GetMapping("/supplement-context")
    public InformationSupplementContextResponse
    getSupplementContext(
            @PathVariable UUID consultationId,

            @CookieValue(
                    name = GuestSessionCookie.NAME,
                    required = false
            )
            String rawToken
    ) {

        return analysisService
                .getSupplementContext(
                        consultationId,
                        rawToken
                );
    }
}