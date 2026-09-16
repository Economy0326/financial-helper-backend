package com.financialhelper.procedure;

import com.financialhelper.guest.GuestSessionCookie;

import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/consultations/{consultationId}/financial-action-plan")
public class FinancialActionPlanController {
    private final FinancialActionPlanService service;

    public FinancialActionPlanController(FinancialActionPlanService service) {
        this.service = service;
    }

    @PostMapping
    public FinancialActionPlanData build(
            @PathVariable UUID consultationId,
            @CookieValue(name = GuestSessionCookie.NAME, required = false) String rawToken
    ) {
        return service.build(consultationId, rawToken);
    }

    @GetMapping
    public FinancialActionPlanData getLatest(
            @PathVariable UUID consultationId,
            @CookieValue(name = GuestSessionCookie.NAME, required = false) String rawToken
    ) {
        return service.getLatest(consultationId, rawToken);
    }
}
