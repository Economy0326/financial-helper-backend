package com.financialhelper.emergency;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

import java.time.LocalDate;
import java.util.List;

// emergency_scenario.payload_json 구조를 표현하는 DTO
public record EmergencyScenarioDocument(
        @NotBlank String title,
        @NotBlank String description,
        @Valid @NotEmpty List<ActionItem> actionsToDo,
        @Valid @NotEmpty List<ActionItem> actionsToAvoid,
        @Valid @NotEmpty List<Contact> contacts,
        @Valid @NotEmpty List<Evidence> evidence,
        @Valid @NotEmpty List<Source> sources
) {

    public record ActionItem(
            @NotBlank String id,
            @NotBlank String title,
            @NotBlank String description,
            @NotEmpty List<@NotBlank String> sourceIds
    ) {
    }

    public record Contact(
            @NotBlank String id,
            @NotBlank String name,
            @NotBlank String description,
            String phoneLabel,
            String phoneHref,
            @NotBlank String icon,
            @NotEmpty List<@NotBlank String> sourceIds
    ) {
    }

    public record Evidence(
            @NotBlank String id,
            @NotBlank String label,
            @NotEmpty List<@NotBlank String> sourceIds
    ) {
    }

    public record Source(
            @NotBlank String id,
            @NotBlank String organization,
            @NotBlank String title,
            @NotBlank String reference,
            LocalDate publishedAt
    ) {
    }
}