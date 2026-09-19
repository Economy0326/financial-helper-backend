package com.financialhelper.consultation;

/** A no-active result is a normal entry state, not a missing resource. */
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
