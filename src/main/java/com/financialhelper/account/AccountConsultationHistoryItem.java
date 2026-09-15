package com.financialhelper.account;

import com.financialhelper.ai.report.ConsultationReport;
import com.financialhelper.consultation.Consultation;
import com.financialhelper.consultation.ConsultationCategory;
import com.financialhelper.consultation.ConsultationStatus;

import java.time.OffsetDateTime;
import java.util.UUID;

public record AccountConsultationHistoryItem(
        UUID consultationId,
        ConsultationCategory category,
        ConsultationStatus status,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        UUID reportId,
        OffsetDateTime reportGeneratedAt
) {
    public static AccountConsultationHistoryItem from(Consultation consultation, ConsultationReport report) {
        return new AccountConsultationHistoryItem(consultation.getId(), consultation.getCategory(), consultation.getStatus(),
                consultation.getCreatedAt(), consultation.getUpdatedAt(), report == null ? null : report.getId(),
                report == null ? null : report.getGeneratedAt());
    }
}
