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
@RequestMapping("/api/v1/consultations/{id}/general-information")
public class GeneralTopicInformationController {
    private final GeneralTopicInformationService service;

    public GeneralTopicInformationController(GeneralTopicInformationService service) {
        this.service = service;
    }

    @PostMapping("/search")
    public GeneralTopicInformationResponse search(@PathVariable UUID id,
            @CookieValue(name = GuestSessionCookie.NAME, required = false) String token,
            @Valid @RequestBody GeneralTopicInformationRequest request) {
        return service.search(id, token, request);
    }
}
