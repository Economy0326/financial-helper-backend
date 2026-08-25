package com.financialhelper.consultation;

// 사용자의 현재 상담 Flow 위치
// status와 currentStep은 같지 않다
public enum ConsultationStep {
    CATEGORY,
    SITUATION,
    FOLLOW_UP,
    SUMMARY,
    ANALYSIS,
    REPORT
}