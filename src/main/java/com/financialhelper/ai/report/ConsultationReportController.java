package com.financialhelper.ai.report;

import com.financialhelper.guest.GuestSessionCookie;

import org.springframework.boot.autoconfigure.condition
        .ConditionalOnProperty;

import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.financialhelper.account.AccountSessionService;

import java.util.UUID;

@RestController
@RequestMapping(
        "/api/v1/consultations/{consultationId}/report"
)
@ConditionalOnProperty(
        prefix = "app.ai",
        name = "enabled",
        havingValue = "true"
)
public class ConsultationReportController {

    private final ConsultationReportService
            consultationReportService;
    private final AccountSessionService accountSessionService;

    public ConsultationReportController(
            ConsultationReportService consultationReportService,
            AccountSessionService accountSessionService
    ) {
        this.consultationReportService =
                consultationReportService;
        this.accountSessionService = accountSessionService;
    }

    @PostMapping("/prepare")
    public ConsultationReportStateResponse prepare(
            @PathVariable UUID consultationId,

            @CookieValue(
                    name = GuestSessionCookie.NAME,
                    required = false
            )
            String rawToken
    ) {

        return consultationReportService
                .prepare(
                        consultationId,
                        rawToken
                );
    }

    @GetMapping
    public ConsultationReportStateResponse getState(
            @PathVariable UUID consultationId,

            @CookieValue(
                    name = GuestSessionCookie.NAME,
                    required = false
            )
            String rawToken
    ) {

        if (accountSessionService.isAuthenticated()) {
            return consultationReportService.getStateForAccount(consultationId);
        }
        return consultationReportService.getState(consultationId, rawToken);
    }
}
