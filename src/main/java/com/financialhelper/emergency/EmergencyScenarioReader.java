package com.financialhelper.emergency;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;

import org.springframework.stereotype.Component;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Component
public class EmergencyScenarioReader {

    private final JsonMapper jsonMapper;
    private final Validator validator;

    public EmergencyScenarioReader(
            JsonMapper jsonMapper,
            Validator validator
    ) {
        this.jsonMapper = jsonMapper;
        this.validator = validator;
    }

    public EmergencyScenarioDocument read(
            EmergencyScenario scenario
    ) {
        EmergencyScenarioDocument document;

        try {
            document = jsonMapper.readValue(
                    scenario.getPayloadJson(),
                    EmergencyScenarioDocument.class
            );
        } catch (JacksonException exception) {
            throw new EmergencyScenarioDataException();
        }

        Set<ConstraintViolation<EmergencyScenarioDocument>>
                violations = validator.validate(document);

        if (!violations.isEmpty()) {
            throw new EmergencyScenarioDataException();
        }

        validateSourceReferences(document);

        return document;
    }

    private void validateSourceReferences(
            EmergencyScenarioDocument document
    ) {
        Set<String> declaredSourceIds =
                new HashSet<>();

        for (EmergencyScenarioDocument.Source source
                : document.sources()) {

            if (!declaredSourceIds.add(source.id())) {
                throw new EmergencyScenarioDataException();
            }
        }

        for (EmergencyScenarioDocument.ActionItem item
                : document.actionsToDo()) {
            validateSourceIds(
                    item.sourceIds(),
                    declaredSourceIds
            );
        }

        for (EmergencyScenarioDocument.ActionItem item
                : document.actionsToAvoid()) {
            validateSourceIds(
                    item.sourceIds(),
                    declaredSourceIds
            );
        }

        for (EmergencyScenarioDocument.Contact contact
                : document.contacts()) {
            validateSourceIds(
                    contact.sourceIds(),
                    declaredSourceIds
            );
        }

        for (EmergencyScenarioDocument.Evidence evidence
                : document.evidence()) {
            validateSourceIds(
                    evidence.sourceIds(),
                    declaredSourceIds
            );
        }
    }

    private void validateSourceIds(
            List<String> sourceIds,
            Set<String> declaredSourceIds
    ) {
        boolean hasUnknownSource =
                sourceIds.stream()
                        .anyMatch(
                                sourceId ->
                                        !declaredSourceIds
                                                // 시나리오에 '포함'되어있는지 판단
                                                .contains(sourceId)
                        );

        if (hasUnknownSource) {
            throw new EmergencyScenarioDataException();
        }
    }
}