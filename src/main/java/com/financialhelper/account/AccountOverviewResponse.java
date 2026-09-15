package com.financialhelper.account;

import com.financialhelper.consultation.Consultation;
import com.financialhelper.consultation.ConsultationCategory;
import com.financialhelper.consultation.ConsultationStatus;
import com.financialhelper.consultation.ConsultationStep;

import java.time.OffsetDateTime;
import java.util.UUID;

public record AccountOverviewResponse(
        UUID accountId,
        String provider,
        String displayName,
        ActiveSummary activeConsultation,
        ConsultationQuota quota
) {
    public static AccountOverviewResponse from(Account account, Consultation active,
                                               AccountConsultationQuotaService.QuotaStatus quota) {
        return new AccountOverviewResponse(account.getId(), account.getProvider().name(), account.getDisplayName(),
                active == null ? null : new ActiveSummary(active.getId(), active.getCategory(), active.getStatus(),
                        active.getCurrentStep(), active.getUpdatedAt()),
                new ConsultationQuota(quota.used(), quota.limit(), quota.nextAvailableAt()));
    }

    public record ActiveSummary(UUID consultationId, ConsultationCategory category,
                                ConsultationStatus status, ConsultationStep currentStep,
                                OffsetDateTime updatedAt) { }
    public record ConsultationQuota(int used, int limit, OffsetDateTime nextAvailableAt) { }
}
