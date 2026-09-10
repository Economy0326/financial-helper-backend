package com.financialhelper.ai.report;

import com.financialhelper.ai.analysis.AnalysisAiResult;
import com.financialhelper.ai.analysis.AnalysisJob;
import com.financialhelper.ai.analysis.AnalysisJobRepository;
import com.financialhelper.ai.analysis.AnalysisJobStatus;

import com.financialhelper.ai.summary.ConsultationSummary;
import com.financialhelper.ai.summary.ConsultationSummaryAiResult;
import com.financialhelper.ai.summary.ConsultationSummaryNotFoundException;
import com.financialhelper.ai.summary.ConsultationSummaryRepository;

import com.financialhelper.consultation.Consultation;
import com.financialhelper.consultation.ConsultationNotFoundException;
import com.financialhelper.consultation.ConsultationRepository;
import com.financialhelper.consultation.ConsultationStatus;
import com.financialhelper.consultation.ConsultationStep;
import com.financialhelper.consultation.InvalidConsultationStateException;

import com.financialhelper.guest.GuestSession;
import com.financialhelper.guest.GuestSessionService;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

@Service
public class ConsultationReportPersistenceService {

    private final ConsultationRepository
            consultationRepository;

    private final ConsultationSummaryRepository
            summaryRepository;

    private final AnalysisJobRepository
            analysisJobRepository;

    private final ConsultationReportRepository
            reportRepository;

    private final GuestSessionService
            guestSessionService;

    private final JsonMapper jsonMapper;

    public ConsultationReportPersistenceService(
            ConsultationRepository consultationRepository,
            ConsultationSummaryRepository summaryRepository,
            AnalysisJobRepository analysisJobRepository,
            ConsultationReportRepository reportRepository,
            GuestSessionService guestSessionService,
            JsonMapper jsonMapper
    ) {
        this.consultationRepository =
                consultationRepository;

        this.summaryRepository =
                summaryRepository;

        this.analysisJobRepository =
                analysisJobRepository;

        this.reportRepository =
                reportRepository;

        this.guestSessionService =
                guestSessionService;

        this.jsonMapper =
                jsonMapper;
    }

    // 같은 revision의 Report가 이미 저장되어 있는지 조회
    @Transactional(readOnly = true)
    public Optional<ConsultationReportData.Document>
    findExisting(
            UUID consultationId,
            String rawToken
    ) {

        Consultation consultation =
                findOwnedConsultation(
                        consultationId,
                        rawToken
                );

        return reportRepository
                .findByConsultation_IdAndCaseInputRevisionAndFollowUpAnswerRevision(
                        consultation.getId(),
                        consultation.getCaseInputRevision(),
                        consultation.getFollowUpAnswerRevision()
                )
                .map(this::toDocument);
    }

    // Report 생성에 필요한 확정 Summary/Analysis를 검증하고 Snapshot으로 묶음
    @Transactional(readOnly = true)
    public ConsultationReportData.Snapshot
    loadGenerationSnapshot(
            UUID consultationId,
            String rawToken
    ) {

        GuestSession guestSession =
                guestSessionService
                        .requireValidSession(
                                rawToken
                        );

        Consultation consultation =
                consultationRepository
                        .findByIdAndGuestSession_Id(
                                consultationId,
                                guestSession.getId()
                        )
                        .orElseThrow(
                                ConsultationNotFoundException::new
                        );

        if (
                consultation.getCurrentStep()
                        != ConsultationStep.ANALYSIS
                || consultation.getStatus()
                        != ConsultationStatus.IN_PROGRESS
        ) {
            throw new InvalidConsultationStateException();
        }

        AnalysisJob analysisJob =
                analysisJobRepository
                        .findByConsultation_IdAndCaseInputRevisionAndFollowUpAnswerRevision(
                                consultation.getId(),
                                consultation.getCaseInputRevision(),
                                consultation.getFollowUpAnswerRevision()
                        )
                        .orElseThrow(
                                InvalidConsultationStateException::new
                        );

        if (
                analysisJob.getStatus()
                        != AnalysisJobStatus.COMPLETED
                || analysisJob.getResultJson()
                        == null
        ) {
            throw new InvalidConsultationStateException();
        }

        ConsultationSummary summary =
                summaryRepository
                        .findByConsultation_IdAndCaseInputRevisionAndFollowUpAnswerRevision(
                                consultation.getId(),
                                consultation.getCaseInputRevision(),
                                consultation.getFollowUpAnswerRevision()
                        )
                        .orElseThrow(
                                ConsultationSummaryNotFoundException::new
                        );

        if (
                summary.getConfirmedAt()
                        == null
        ) {
            throw new InvalidConsultationStateException();
        }

        return new ConsultationReportData.Snapshot(
                consultation.getId(),
                guestSession.getId(),
                analysisJob.getId(),
                consultation.getCategory(),
                consultation.getCaseInputRevision(),
                consultation.getFollowUpAnswerRevision(),

                deserializeSummary(
                        summary.getResultJson()
                ),

                deserializeAnalysis(
                        analysisJob.getResultJson()
                )
        );
    }

    // AI 결과가 아직 현재 revision에 유효한지 재검증한 후 Report를 저장
    @Transactional
    public ConsultationReportData.Document
    saveIfCurrent(
            ConsultationReportData.Snapshot snapshot,
            ConsultationReportAiResult result,
            String model
    ) {

        Consultation consultation =
                consultationRepository
                        .findForUpdateByIdAndGuestSession_Id(
                                snapshot.consultationId(),
                                snapshot.guestSessionId()
                        )
                        .orElseThrow(
                                ConsultationNotFoundException::new
                        );

        if (
                consultation.getCaseInputRevision()
                        != snapshot.caseInputRevision()
                || consultation
                        .getFollowUpAnswerRevision()
                        != snapshot.followUpAnswerRevision()
        ) {
            throw new InvalidConsultationStateException();
        }

        Optional<ConsultationReport> existing =
                reportRepository
                        .findByConsultation_IdAndCaseInputRevisionAndFollowUpAnswerRevision(
                                consultation.getId(),
                                consultation.getCaseInputRevision(),
                                consultation.getFollowUpAnswerRevision()
                        );

        if (existing.isPresent()) {
            return toDocument(
                    existing.get()
            );
        }

        AnalysisJob analysisJob =
                analysisJobRepository
                        .findById(
                                snapshot.analysisJobId()
                        )
                        .orElseThrow(
                                InvalidConsultationStateException::new
                        );

        if (
                analysisJob.getStatus()
                        != AnalysisJobStatus.COMPLETED
        ) {
            throw new InvalidConsultationStateException();
        }

        OffsetDateTime now =
                OffsetDateTime.now(
                        ZoneOffset.UTC
                );

        ConsultationReport report =
                reportRepository.save(
                        new ConsultationReport(
                                consultation,
                                analysisJob,
                                consultation.getCaseInputRevision(),
                                consultation.getFollowUpAnswerRevision(),
                                model,
                                serialize(result),
                                now
                        )
                );

        consultation.moveToReport(now);

        return toDocument(report);
    }

    // 현재 revision의 저장된 Report 상태를 조회
    @Transactional(readOnly = true)
    public ConsultationReportStateResponse getState(
            UUID consultationId,
            String rawToken
    ) {

        Consultation consultation =
                findOwnedConsultation(
                        consultationId,
                        rawToken
                );

        Optional<ConsultationReport> report =
                reportRepository
                        .findByConsultation_IdAndCaseInputRevisionAndFollowUpAnswerRevision(
                                consultation.getId(),
                                consultation.getCaseInputRevision(),
                                consultation.getFollowUpAnswerRevision()
                        );

        return report
                .map(this::toDocument)
                .map(
                        ConsultationReportStateResponse::ready
                )
                .orElseGet(
                        ConsultationReportStateResponse::notPrepared
                );
    }

    private Consultation findOwnedConsultation(
            UUID consultationId,
            String rawToken
    ) {

        GuestSession guestSession =
                guestSessionService
                        .requireValidSession(
                                rawToken
                        );

        return consultationRepository
                .findByIdAndGuestSession_Id(
                        consultationId,
                        guestSession.getId()
                )
                .orElseThrow(
                        ConsultationNotFoundException::new
                );
    }

    private String serialize(
            ConsultationReportAiResult result
    ) {

        try {
            return jsonMapper
                    .writeValueAsString(
                            result
                    );

        } catch (JacksonException exception) {

            throw new IllegalStateException(
                    "Failed to serialize consultation report"
            );
        }
    }

    private ConsultationSummaryAiResult deserializeSummary(
            String json
    ) {

        try {
            return jsonMapper.readValue(
                    json,
                    ConsultationSummaryAiResult.class
            );

        } catch (JacksonException exception) {

            throw new IllegalStateException(
                    "Failed to deserialize consultation summary"
            );
        }
    }

    private AnalysisAiResult deserializeAnalysis(
            String json
    ) {

        try {
            return jsonMapper.readValue(
                    json,
                    AnalysisAiResult.class
            );

        } catch (JacksonException exception) {

            throw new IllegalStateException(
                    "Failed to deserialize analysis result"
            );
        }
    }

    private ConsultationReportAiResult deserializeReport(
            String json
    ) {

        try {
            return jsonMapper.readValue(
                    json,
                    ConsultationReportAiResult.class
            );

        } catch (JacksonException exception) {

            throw new IllegalStateException(
                    "Failed to deserialize consultation report"
            );
        }
    }

    private ConsultationReportData.Document toDocument(
            ConsultationReport report
    ) {

        return new ConsultationReportData.Document(
                report.getConsultation().getId(),
                report.getCaseInputRevision(),
                report.getFollowUpAnswerRevision(),
                report.getModel(),
                deserializeReport(
                        report.getResultJson()
                ),
                report.getGeneratedAt()
        );
    }
}