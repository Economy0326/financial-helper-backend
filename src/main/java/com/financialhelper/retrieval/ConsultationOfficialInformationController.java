package com.financialhelper.retrieval;

import com.financialhelper.guest.GuestSessionCookie;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/consultations")
public class ConsultationOfficialInformationController {
    private final ConsultationOfficialInformationService service;

    public ConsultationOfficialInformationController(ConsultationOfficialInformationService service) {
        this.service = service;
    }

    @PostMapping("/{id}/official-information/search")
    public ConsultationOfficialInformationResponse search(
            @PathVariable UUID id,
            @CookieValue(name = GuestSessionCookie.NAME, required = false) String rawToken,
            @Valid @RequestBody ConsultationOfficialInformationRequest request
    ) {
        return service.search(id, rawToken, request);
    }
}
