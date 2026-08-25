package com.financialhelper.consultation;

// 상담 전체의 Business 상태
// status와 currentStep은 같지 않다
public enum ConsultationStatus {
    IN_PROGRESS,
    ANALYZING,
    NEEDS_MORE_INFO,
    COMPLETED,
    FAILED
}