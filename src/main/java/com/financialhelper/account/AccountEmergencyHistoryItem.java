package com.financialhelper.account;

import com.financialhelper.emergency.EmergencyHistory;
import com.financialhelper.emergency.EmergencyType;

import java.time.OffsetDateTime;
import java.util.UUID;

public record AccountEmergencyHistoryItem(UUID id, EmergencyType emergencyType,
                                          String scenarioVersion, OffsetDateTime viewedAt) {
    public static AccountEmergencyHistoryItem from(EmergencyHistory history) {
        return new AccountEmergencyHistoryItem(history.getId(), history.getEmergencyType(),
                history.getScenarioVersion(), history.getViewedAt());
    }
}
