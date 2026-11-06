package com.financialhelper.ai.information;

import com.financialhelper.guest.GuestSessionCookie;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/consultations/{id}/information-report")
@ConditionalOnProperty(prefix = "app.ai", name = "enabled", havingValue = "true")
public class InformationReportController {
    private final InformationReportService reports;

    public InformationReportController(InformationReportService reports) { this.reports = reports; }

    @PostMapping("/prepare")
    public InformationReportResponse prepare(@PathVariable UUID id,
            @CookieValue(name = GuestSessionCookie.NAME, required = false) String token,
            @Valid @RequestBody Request request) {
        return reports.prepare(id, token, request.expectedCaseInputRevision());
    }

    @GetMapping
    public InformationReportResponse get(@PathVariable UUID id,
            @CookieValue(name = GuestSessionCookie.NAME, required = false) String token) {
        return reports.get(id, token);
    }

    public record Request(@NotNull @Min(0) Long expectedCaseInputRevision) {}
}
