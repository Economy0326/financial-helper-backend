package com.financialhelper.ai.summary;

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
        "/api/v1/consultations/{consultationId}/summary"
)
@ConditionalOnProperty(
        prefix = "app.ai",
        name = "enabled",
        havingValue = "true"
)
public class ConsultationSummaryController {

    private final ConsultationSummaryService
            consultationSummaryService;

    public ConsultationSummaryController(
            ConsultationSummaryService consultationSummaryService
    ) {
        this.consultationSummaryService =
                consultationSummaryService;
    }

    @GetMapping
    public ConsultationSummaryStateResponse getState(
            @PathVariable UUID consultationId,

            @CookieValue(
                    name = GuestSessionCookie.NAME,
                    required = false
            )
            String rawToken
    ) {

        return consultationSummaryService.getState(
                consultationId,
                rawToken
        );
    }

    @PostMapping("/prepare")
    public ConsultationSummaryStateResponse prepare(
            @PathVariable UUID consultationId,

            @CookieValue(
                    name = GuestSessionCookie.NAME,
                    required = false
            )
            String rawToken
    ) {

        return consultationSummaryService.prepare(
                consultationId,
                rawToken
        );
    }

    // consultationId, nextStep을 반환
    @PostMapping("/confirm")
    public ConfirmConsultationSummaryResponse confirm(
            @PathVariable UUID consultationId,

            @CookieValue(
                    name = GuestSessionCookie.NAME,
                    required = false
            )
            String rawToken
    ) {

        return consultationSummaryService.confirm(
                consultationId,
                rawToken
        );
    }
}