package com.financialhelper.emergency;

// 클라이언트가 요청한 type에 대한 내용 서버가 반환
public record EmergencySelectionResponse(
        EmergencyType selectedType,
        String scenarioKey
) {
}