package com.financialhelper.ai.followup;

import com.financialhelper.guest.GuestSessionCookie;

import jakarta.validation.Valid;

import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/consultations/{consultationId}/procedure-follow-up")
public class ProcedureFollowUpController {
    private final ProcedureFollowUpService service;

    public ProcedureFollowUpController(ProcedureFollowUpService service) {
        this.service = service;
    }

    @PostMapping("/prepare")
    public StructuredFollowUpStateResponse prepare(
            @PathVariable UUID consultationId,
            @CookieValue(name = GuestSessionCookie.NAME, required = false) String rawToken
    ) {
        return service.prepare(consultationId, rawToken);
    }

    @GetMapping
    public StructuredFollowUpStateResponse getState(
            @PathVariable UUID consultationId,
            @CookieValue(name = GuestSessionCookie.NAME, required = false) String rawToken
    ) {
        return service.getState(consultationId, rawToken);
    }

    @PutMapping("/questions/{questionId}/answer")
    public StructuredFollowUpStateResponse answer(
            @PathVariable UUID consultationId,
            @PathVariable UUID questionId,
            @CookieValue(name = GuestSessionCookie.NAME, required = false) String rawToken,
            @Valid @RequestBody UpdateFollowUpAnswerRequest request
    ) {
        return service.answer(consultationId, questionId, rawToken, request);
    }
}
