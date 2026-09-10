package com.financialhelper.emergency;

import jakarta.validation.constraints.NotNull;

// 사용자가 선택한 유형 서버에 저장
public record SelectEmergencyTypeRequest(
        @NotNull EmergencyType type
) {
}