package com.financialhelper.ai.followup;

import com.financialhelper.guest.GuestSessionCookie;

import jakarta.validation.Valid;

import org.springframework.boot.autoconfigure.condition
        .ConditionalOnProperty;

import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping(
        "/api/v1/consultations/{consultationId}/follow-up"
)
@ConditionalOnProperty(
        prefix = "app.ai",
        name = "enabled",
        havingValue = "true"
)
public class FollowUpController {

    private final FollowUpService
            followUpService;

    public FollowUpController(
            FollowUpService followUpService
    ) {
        this.followUpService =
                followUpService;
    }

    @PostMapping("/prepare")
    public FollowUpStateResponse prepare(
            @PathVariable UUID consultationId,

            @CookieValue(
                    name = GuestSessionCookie.NAME,
                    required = false
            )
            String rawToken
    ) {

        return followUpService.prepare(
                consultationId,
                rawToken
        );
    }

    @GetMapping
    public FollowUpStateResponse getState(
            @PathVariable UUID consultationId,

            // questionNumber -> null or 1~5
            @RequestParam(
                    required = false
            )
            Integer questionNumber,

            @CookieValue(
                    name = GuestSessionCookie.NAME,
                    required = false
            )
            String rawToken
    ) {

        return followUpService.getState(
                consultationId,
                rawToken,
                questionNumber
        );
    }

    @PutMapping(
            "/questions/{questionId}/answer"
    )
    public FollowUpStateResponse answer(
            @PathVariable UUID consultationId,

            @PathVariable UUID questionId,

            @CookieValue(
                    name = GuestSessionCookie.NAME,
                    required = false
            )
            String rawToken,

            @Valid
            @RequestBody
            UpdateFollowUpAnswerRequest request
    ) {

        return followUpService.answer(
                consultationId,
                questionId,
                rawToken,
                request
        );
    }
}