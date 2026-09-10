package com.financialhelper.emergency;

import java.util.List;
import java.util.UUID;

public record EmergencyScenarioStateResponse(
        String kind,
        Scenario scenario
) {

    public static EmergencyScenarioStateResponse notSelected() {
        return new EmergencyScenarioStateResponse(
                "not-selected",
                null
        );
    }

    public static EmergencyScenarioStateResponse ready(
            EmergencyScenario scenario,
            EmergencyScenarioDocument document
    ) {
        return new EmergencyScenarioStateResponse(
                "ready",
                new Scenario(
                        scenario.getId(),
                        scenario.getEmergencyType(),
                        scenario.getScenarioKey(),
                        document.title(),
                        document.description(),
                        document.actionsToDo()
                                .stream()
                                .map(item ->
                                        new ActionItem(
                                                item.id(),
                                                item.title(),
                                                item.description()
                                        )
                                )
                                .toList(),
                        document.actionsToAvoid()
                                .stream()
                                .map(item ->
                                        new ActionItem(
                                                item.id(),
                                                item.title(),
                                                item.description()
                                        )
                                )
                                .toList(),
                        document.contacts()
                                .stream()
                                .map(contact ->
                                        new Contact(
                                                contact.id(),
                                                contact.name(),
                                                contact.description(),
                                                contact.phoneLabel(),
                                                contact.phoneHref(),
                                                contact.icon()
                                        )
                                )
                                .toList(),
                        document.evidence()
                                .stream()
                                .map(evidence ->
                                        new Evidence(
                                                evidence.id(),
                                                evidence.label()
                                        )
                                )
                                .toList()
                )
        );
    }

    public record Scenario(
            UUID scenarioId,
            EmergencyType type,
            String scenarioKey,
            String title,
            String description,
            List<ActionItem> actionsToDo,
            List<ActionItem> actionsToAvoid,
            List<Contact> contacts,
            List<Evidence> evidence
    ) {
    }

    public record ActionItem(
            String id,
            String title,
            String description
    ) {
    }

    public record Contact(
            String id,
            String name,
            String description,
            String phoneLabel,
            String phoneHref,
            String icon
    ) {
    }

    public record Evidence(
            String id,
            String label
    ) {
    }
}