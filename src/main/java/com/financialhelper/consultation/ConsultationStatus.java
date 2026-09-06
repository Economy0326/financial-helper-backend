package com.financialhelper.consultation;

import java.util.Set;

// 상담 전체의 Business 상태
// status와 currentStep은 같지 않다
public enum ConsultationStatus {

    IN_PROGRESS,
    ANALYZING,
    NEEDS_MORE_INFO,
    COMPLETED,
    FAILED;

    private static final Set<ConsultationStatus> ACTIVE_STATUSES =
            Set.of(
                    IN_PROGRESS,
                    ANALYZING,
                    NEEDS_MORE_INFO,
                    // 분석 시도가 실패해도 아직 사용자가 이어서 처리할 수 있음
                    FAILED
            );

    public static Set<ConsultationStatus> activeStatuses() {
        return ACTIVE_STATUSES;
    }
}