package com.financialhelper.account;

import com.financialhelper.ai.report.ConsultationReport;
import com.financialhelper.ai.report.ConsultationReportRepository;
import com.financialhelper.consultation.Consultation;
import com.financialhelper.consultation.ConsultationRepository;
import com.financialhelper.consultation.ConsultationStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import com.financialhelper.emergency.EmergencyHistoryRepository;

@Service
public class AccountOverviewService {
    private final AccountSessionService sessionService;
    private final ConsultationRepository consultationRepository;
    private final ConsultationReportRepository reportRepository;
    private final AccountConsultationQuotaService quotaService;
    private final EmergencyHistoryRepository emergencyHistoryRepository;

    public AccountOverviewService(AccountSessionService sessionService,
                                  ConsultationRepository consultationRepository,
                                  ConsultationReportRepository reportRepository,
                                  AccountConsultationQuotaService quotaService,
                                  EmergencyHistoryRepository emergencyHistoryRepository) {
        this.sessionService = sessionService;
        this.consultationRepository = consultationRepository;
        this.reportRepository = reportRepository;
        this.quotaService = quotaService;
        this.emergencyHistoryRepository = emergencyHistoryRepository;
    }

    @Transactional(readOnly = true)
    public AccountOverviewResponse overview() {
        Account account = requireAccount();
        Consultation active = consultationRepository.findFirstByAccount_IdAndStatusInOrderByUpdatedAtDesc(
                account.getId(), ConsultationStatus.resumableStatuses()).orElse(null);
        AccountConsultationQuotaService.QuotaStatus quota = quotaService.status(account, now());
        return AccountOverviewResponse.from(account, active, quota);
    }

    @Transactional(readOnly = true)
    public Page<AccountConsultationHistoryItem> history(Pageable pageable) {
        Account account = requireAccount();
        return consultationRepository.findHistoryByAccountId(account.getId(), pageable)
                .map(consultation -> {
                    ConsultationReport report = reportRepository.findFirstByConsultation_IdOrderByGeneratedAtDesc(
                            consultation.getId()).orElse(null);
                    return AccountConsultationHistoryItem.from(consultation, report);
                });
    }

    @Transactional(readOnly = true)
    public Page<AccountEmergencyHistoryItem> emergencyHistory(Pageable pageable) {
        Account account = requireAccount();
        return emergencyHistoryRepository.findByAccount_IdOrderByViewedAtDesc(account.getId(), pageable)
                .map(AccountEmergencyHistoryItem::from);
    }

    public Account requireAccount() {
        return sessionService.currentAccount().orElseThrow(AccountAuthenticationException::required);
    }

    private static OffsetDateTime now() { return OffsetDateTime.now(ZoneOffset.UTC); }
}
