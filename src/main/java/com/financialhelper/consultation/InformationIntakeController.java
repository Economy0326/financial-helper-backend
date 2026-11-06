package com.financialhelper.consultation;

import com.financialhelper.guest.GuestSessionCookie;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/consultations/{id}/information-intake")
public class InformationIntakeController {
    private final InformationIntakeService service;
    public InformationIntakeController(InformationIntakeService service) { this.service = service; }

    @GetMapping
    public InformationIntakeService.State get(@PathVariable UUID id,
            @CookieValue(name = GuestSessionCookie.NAME, required = false) String token) {
        return service.get(id, token);
    }

    @PutMapping
    public InformationIntakeService.State update(@PathVariable UUID id,
            @CookieValue(name = GuestSessionCookie.NAME, required = false) String token, @Valid @RequestBody Input input) {
        return service.update(id, token, input.situationText(), input.expectedCaseInputRevision());
    }

    @PostMapping("/confirm")
    public InformationIntakeService.State confirm(@PathVariable UUID id,
            @CookieValue(name = GuestSessionCookie.NAME, required = false) String token, @Valid @RequestBody Confirmation input) {
        return service.confirm(id, token, input.scenario(), input.expectedCaseInputRevision());
    }

    public record Input(@NotBlank @Size(max = 1000) String situationText,
                        @NotNull @Min(0) Long expectedCaseInputRevision) {}
    public record Confirmation(@NotNull ConsultationScenario scenario,
                               @NotNull @Min(0) Long expectedCaseInputRevision) {}
}
