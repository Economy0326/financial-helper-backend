package com.financialhelper.ai.understanding;

import com.financialhelper.guest.GuestSessionCookie;

import org.springframework.boot.autoconfigure.condition
        .ConditionalOnProperty;

import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping(
        "/api/v1/consultations/{id}/understanding"
)
@ConditionalOnProperty(
        prefix = "app.ai",
        name = "enabled",
        havingValue = "true"
)
public class CaseUnderstandingController {

    private final CaseUnderstandingService
            caseUnderstandingService;

    public CaseUnderstandingController(
            CaseUnderstandingService caseUnderstandingService
    ) {
        this.caseUnderstandingService =
                caseUnderstandingService;
    }

    @PostMapping
    public CaseUnderstandingResponse generate(
            @PathVariable UUID id,

            @CookieValue(
                    name = GuestSessionCookie.NAME,
                    required = false
            )
            String rawToken
    ) {

        return CaseUnderstandingResponse.from(
                caseUnderstandingService
                        .generateOrGet(
                                id,
                                rawToken
                        )
        );
    }
}