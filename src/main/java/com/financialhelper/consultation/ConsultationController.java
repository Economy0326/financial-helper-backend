package com.financialhelper.consultation;

import com.financialhelper.guest.GuestSessionCookie;
import com.financialhelper.guest.GuestSessionCookieService;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/consultations")
public class ConsultationController {

    private final ConsultationService consultationService;
    private final GuestSessionCookieService cookieService;

    public ConsultationController(
            ConsultationService consultationService,
            GuestSessionCookieService cookieService
    ) {
        this.consultationService = consultationService;
        this.cookieService = cookieService;
    }

    @PostMapping
    public ResponseEntity<ConsultationCreateResponse>
    startConsultation(
            @CookieValue(
                    name = GuestSessionCookie.NAME,
                    required = false
            )
            String rawToken,
            HttpServletResponse servletResponse
    ) {
        ConsultationStartResult result =
                consultationService
                        .startConsultation(rawToken);

        result.getRawTokenToSet()
                .ifPresent(token ->
                        servletResponse.addHeader(
                                HttpHeaders.SET_COOKIE,
                                cookieService
                                        .create(token)
                                        .toString()
                        )
                );

        return ResponseEntity.ok(
                result.getResponse()
        );
    }

    @GetMapping("/active")
    public ActiveConsultationResponse
    getActiveConsultation(
            @CookieValue(
                    name = GuestSessionCookie.NAME,
                    required = false
            )
            String rawToken
    ) {
        return consultationService
                .getActiveConsultation(rawToken);
    }

    @PutMapping("/{id}/category")
    public UpdateConsultationCategoryResponse updateCategory(
            @PathVariable UUID id,

            @CookieValue(
                    name = GuestSessionCookie.NAME,
                    required = false
            )
            String rawToken,

            @Valid
            @RequestBody
            UpdateConsultationCategoryRequest request
    ) {
        return consultationService.updateCategory(
                id,
                rawToken,
                request
        );
    }

    @PutMapping("/{id}/situation")
    public UpdateConsultationSituationResponse updateSituation(
            @PathVariable UUID id,

            @CookieValue(
                    name = GuestSessionCookie.NAME,
                    required = false
            )
            String rawToken,

            @Valid
            @RequestBody
            UpdateConsultationSituationRequest request
    ) {
        return consultationService.updateSituation(
                id,
                rawToken,
                request
        );
    }
}