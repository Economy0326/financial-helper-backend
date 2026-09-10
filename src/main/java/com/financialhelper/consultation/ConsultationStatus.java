package com.financialhelper.consultation;

import java.util.Set;

// 상담 전체의 Business 상태
// status와 currentStep은 같지 않다
public enum ConsultationStatus {

    IN_PROGRESS,
    ANALYZING,
    NEEDS_MORE_INFO,

    // 사용자에게 1회 추가정보 보완 기회를 제공한 뒤에도
    // 신뢰할 수 있는 분석이 어려운 경우
    INSUFFICIENT_INFORMATION,

    COMPLETED,
    FAILED;

    // 실제로 계속 진행하거나 Retry 할 수 있는 상담 상태
    private static final Set<ConsultationStatus> ACTIVE_STATUSES =
            Set.of(
                    IN_PROGRESS,
                    ANALYZING,
                    NEEDS_MORE_INFO,
                    // 분석 시도가 실패해도 아직 사용자가 이어서 처리할 수 있음
                    FAILED
            );

    // Home에서 다시 접근할 수 있는 상담 상태 (재분석은 불가능)
    private static final Set<ConsultationStatus> RESUMABLE_STATUSES =
            Set.of(
                    IN_PROGRESS,
                    ANALYZING,
                    NEEDS_MORE_INFO,
                    INSUFFICIENT_INFORMATION,
                    FAILED
            );

    public static Set<ConsultationStatus> activeStatuses() {
        return ACTIVE_STATUSES;
    }

    public static Set<ConsultationStatus> resumableStatuses() {
        return RESUMABLE_STATUSES;
    }
}