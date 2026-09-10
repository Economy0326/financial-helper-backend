package com.financialhelper.emergency;

// 긴급대응 첫 화면의 피해 유형 선택지
public record EmergencyTypeOptionResponse(
        EmergencyType value,
        String title,
        String description,
        String icon
) {
    public static EmergencyTypeOptionResponse from(
            EmergencyScenario scenario
    ) {
        return new EmergencyTypeOptionResponse(
                scenario.getEmergencyType(),
                scenario.getTypeTitle(),
                scenario.getTypeDescription(),
                scenario.getTypeIcon()
        );
    }
}