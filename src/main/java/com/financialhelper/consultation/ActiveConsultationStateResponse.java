package com.financialhelper.consultation;

/** 진행 중인 상담이 없는 결과는 누락된 resource가 아니라 정상 진입 상태다. */
public record ActiveConsultationStateResponse(
        boolean active,
        ActiveConsultationResponse consultation
) {
    public static ActiveConsultationStateResponse active(Consultation consultation) {
        return new ActiveConsultationStateResponse(true, ActiveConsultationResponse.from(consultation));
    }

    public static ActiveConsultationStateResponse none() {
        return new ActiveConsultationStateResponse(false, null);
    }
}
