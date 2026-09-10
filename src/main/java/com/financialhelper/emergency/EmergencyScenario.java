package com.financialhelper.emergency;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "emergency_scenario")
public class EmergencyScenario {

    @Id
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(
            name = "emergency_type",
            nullable = false,
            unique = true,
            length = 32
    )
    private EmergencyType emergencyType;

    // 사람이 읽을 수 있는 Scenario의 안정적인 내부 이름
    @Column(
            name = "scenario_key",
            nullable = false,
            unique = true,
            length = 64
    )
    private String scenarioKey;

    @Column(
            name = "display_order",
            nullable = false
    )
    private int displayOrder;

    @Column(
            name = "type_title",
            nullable = false,
            length = 100
    )
    private String typeTitle;

    @Column(
            name = "type_description",
            nullable = false,
            length = 300
    )
    private String typeDescription;

    @Column(
            name = "type_icon",
            nullable = false,
            length = 32
    )
    private String typeIcon;

    @Column(
            name = "payload_json",
            nullable = false,
            columnDefinition = "TEXT"
    )
    private String payloadJson;

    @Column(name = "source_checked_at")
    private LocalDate sourceCheckedAt;

    protected EmergencyScenario() {
    }

    public UUID getId() {
        return id;
    }

    public EmergencyType getEmergencyType() {
        return emergencyType;
    }

    public String getScenarioKey() {
        return scenarioKey;
    }

    public int getDisplayOrder() {
        return displayOrder;
    }

    public String getTypeTitle() {
        return typeTitle;
    }

    public String getTypeDescription() {
        return typeDescription;
    }

    public String getTypeIcon() {
        return typeIcon;
    }

    public String getPayloadJson() {
        return payloadJson;
    }

    public LocalDate getSourceCheckedAt() {
        return sourceCheckedAt;
    }
}