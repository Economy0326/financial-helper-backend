package com.financialhelper.ai.analysis;

import com.financialhelper.ai.OpenAiProperties;

import com.financialhelper.ai.summary
        .ConsultationSummary;
import com.financialhelper.ai.summary
        .ConsultationSummaryAiResult;
import com.financialhelper.ai.summary
        .ConsultationSummaryNotFoundException;
import com.financialhelper.ai.summary
        .ConsultationSummaryRepository;

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
public class AnalysisPersistenceService {

    private final ConsultationRepository
            consultationRepository;

    private final ConsultationSummaryRepository
            summaryRepository;

    private final AnalysisJobRepository
            analysisJobRepository;

    private final GuestSessionService
            guestSessionService;

    private final OpenAiProperties
            openAiProperties;

    private final JsonMapper jsonMapper;

    public AnalysisPersistenceService(
            ConsultationRepository consultationRepository,
            ConsultationSummaryRepository summaryRepository,
            AnalysisJobRepository analysisJobRepository,
            GuestSessionService guestSessionService,
            OpenAiProperties openAiProperties,
            JsonMapper jsonMapper
    ) {
        this.consultationRepository =
                consultationRepository;

        this.summaryRepository =
                summaryRepository;

        this.analysisJobRepository =
                analysisJobRepository;

        this.guestSessionService =
                guestSessionService;

        this.openAiProperties =
                openAiProperties;

        this.jsonMapper =
                jsonMapper;
    }

    @Transactional
    public AnalysisData.Reservation reserveStart(
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
                        .findForUpdateByIdAndGuestSession_Id(
                                consultationId,
                                guestSession.getId()
                        )
                        .orElseThrow(
                                ConsultationNotFoundException::new
                        );

        ensureAnalysisStep(
                consultation
        );

        ConsultationSummary summary =
                requireConfirmedSummary(
                        consultation
                );

        Optional<AnalysisJob> existing =
                currentJob(
                        consultation
                );

        if (existing.isPresent()) {

            AnalysisJob job =
                    existing.get();

            return new AnalysisData.Reservation(
                    job.getId(),
                    false,
                    toState(
                        job,
                        consultation
                    )
            );
        }

        if (
                consultation.getStatus()
                        != ConsultationStatus.IN_PROGRESS
        ) {
            throw new InvalidConsultationStateException();
        }

        OffsetDateTime now =
                OffsetDateTime.now(
                        ZoneOffset.UTC
                );

        AnalysisJob job =
                analysisJobRepository.save(
                        new AnalysisJob(
                                consultation,
                                consultation
                                        .getCaseInputRevision(),
                                consultation
                                        .getFollowUpAnswerRevision(),
                                openAiProperties.model(),
                                now
                        )
                );

        // Summary가 Confirm인 것은 이미 확인 완료
        if (summary.getConfirmedAt() == null) {
            throw new InvalidConsultationStateException();
        }

        consultation.startAnalysis(now);

        return new AnalysisData.Reservation(
                job.getId(),
                // AI 분석 시작 가능
                true,
                toState(
                        job,
                        consultation
                )
        );
    }

    @Transactional
    public AnalysisData.Reservation reserveRetry(
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
                        .findForUpdateByIdAndGuestSession_Id(
                                consultationId,
                                guestSession.getId()
                        )
                        .orElseThrow(
                                ConsultationNotFoundException::new
                        );

        ensureAnalysisStep(
                consultation
        );

        requireConfirmedSummary(
                consultation
        );

        AnalysisJob job =
                currentJob(
                        consultation
                )
                        .orElseThrow(
                                InvalidConsultationStateException::new
                        );

        // 같은 Retry 요청이 거의 동시에 들어왔다면 첫 요청이 이미 QUEUED/PROCESSING으로 바꿨을 가능성이 높음
        // 따라서 이 경우에는 새 Worker를 또 예약하지 않는다.
        if (
                job.getStatus()
                        == AnalysisJobStatus.QUEUED
                || job.getStatus()
                        == AnalysisJobStatus.PROCESSING
        ) {

            return new AnalysisData.Reservation(
                    job.getId(),
                    // AI 분석 시작 불가능
                    false,
                    toState(
                        job,
                        consultation
                    )
            );
        }

        // FAILED 상태에만 RETRY
        if (
                job.getStatus()
                        != AnalysisJobStatus.FAILED
        ) {
            throw new InvalidConsultationStateException();
        }

        OffsetDateTime now =
                OffsetDateTime.now(
                        ZoneOffset.UTC
                );

        job.queueRetry(
                openAiProperties.model(),
                now
        );

        consultation.startAnalysis(now);

        // RETRY 검증 후 Job을 다시 QUEUED 상태로 변경
        return new AnalysisData.Reservation(
                job.getId(),
                true,
                toState(
                        job,
                        consultation
                )
        );
    }

    @Transactional
    public Optional<AnalysisData.Snapshot>
    beginProcessing(
            UUID jobId
    ) {

        AnalysisJob job =
                analysisJobRepository
                        .findForUpdateById(
                                jobId
                        )
                        .orElseThrow();

        // 실수로 같은 jobId가 Worker에 두 번 들어와도 하나만 QUEUED -> PROCESSING 전환
        if (
                job.getStatus()
                        != AnalysisJobStatus.QUEUED
        ) {
            return Optional.empty();
        }

        Consultation consultation =
                job.getConsultation();

        // 입력이 바뀌어도 Optional.empty()
        if (
                consultation.getCaseInputRevision()
                        != job.getCaseInputRevision()
                || consultation
                        .getFollowUpAnswerRevision()
                        != job.getFollowUpAnswerRevision()
        ) {

            job.fail(
                    "INPUT_CHANGED",
                    OffsetDateTime.now(
                            ZoneOffset.UTC
                    )
            );

            return Optional.empty();
        }

        ConsultationSummary summary =
                requireConfirmedSummary(
                        consultation
                );

        job.startProcessing(
                OffsetDateTime.now(
                        ZoneOffset.UTC
                )
        );

        return Optional.of(
                new AnalysisData.Snapshot(
                        job.getId(),
                        consultation.getId(),
                        consultation.getCategory(),
                        job.getCaseInputRevision(),
                        job.getFollowUpAnswerRevision(),
                        deserializeSummary(
                                summary.getResultJson()
                        )
                )
        );
    }

    @Transactional
    public void complete(
            AnalysisData.Snapshot snapshot,
            AnalysisAiResult result
    ) {

        AnalysisJob job =
                analysisJobRepository
                        .findForUpdateById(
                                snapshot.jobId()
                        )
                        .orElseThrow();

        if (
                job.getStatus()
                        != AnalysisJobStatus.PROCESSING
        ) {
            return;
        }

        Consultation consultation =
                consultationRepository
                        .findForUpdateById(
                                snapshot.consultationId()
                        )
                        .orElseThrow();

        // 외부 API 호출 도중 입력이 바뀌었다면
        // 오래된 분석 결과를 현재 Consultation에 적용하지 않는다
        if (
                consultation.getCaseInputRevision()
                        != snapshot.caseInputRevision()
                || consultation
                        .getFollowUpAnswerRevision()
                        != snapshot.followUpAnswerRevision()
        ) {

            job.fail(
                    "INPUT_CHANGED",
                    OffsetDateTime.now(
                            ZoneOffset.UTC
                    )
            );

            return;
        }

        String resultJson =
                serializeResult(result);

        OffsetDateTime now =
                OffsetDateTime.now(
                        ZoneOffset.UTC
                );

        if (
                result.outcome
                        == AnalysisAiResult
                        .Outcome.READY_FOR_REPORT
        ) {

            job.complete(
                    resultJson,
                    now
            );

            consultation
                    .markAnalysisReady(
                            now
                    );

            return;
        }

        if (
                consultation
                        .getInformationSupplementCount()
                        >= 1
        ) {
            // 이미 사용자가 정보를 보완한 이후에는 다시 Situation으로 보내지 않는다.
            job.insufficientInformation(
                    resultJson,
                    now
            );

            consultation
                    .markInsufficientInformation(
                            now
                    );

            return;
        }

        job.needsMoreInfo(
                resultJson,
                now
        );

        consultation.markNeedsMoreInfo(
                now
        );
    }

    @Transactional
    public void fail(
            UUID jobId,
            String failureCode
    ) {

        AnalysisJob job =
                analysisJobRepository
                        .findForUpdateById(
                                jobId
                        )
                        .orElseThrow();

        // 이미 다른 실행에서 종료됐다면 덮어쓰지 않는다
        if (
                job.getStatus()
                        != AnalysisJobStatus.PROCESSING
                && job.getStatus()
                        != AnalysisJobStatus.QUEUED
        ) {
            return;
        }

        OffsetDateTime now =
                OffsetDateTime.now(
                        ZoneOffset.UTC
                );

        // 해당 과거 Job 자체의 사실 기록
        job.fail(
                failureCode,
                now
        );

        Consultation consultation =
                consultationRepository
                        .findForUpdateById(
                                job.getConsultation().getId()
                        )
                        .orElseThrow();

        // 현재 Revision과 같은 Job일 때만 Consultation 전체 상태를 FAILED로 변경한다
        if (
                consultation.getCaseInputRevision()
                        == job.getCaseInputRevision()
                && consultation
                        .getFollowUpAnswerRevision()
                        == job.getFollowUpAnswerRevision()
                && consultation.getCurrentStep()
                        == ConsultationStep.ANALYSIS
        ) {
            // 해당 실패가 현재 상담에도 여전히 유효할 때만 전체 상태 반영
            consultation.markAnalysisFailed(
                    now
            );
        }
    }

    @Transactional(readOnly = true)
    public AnalysisStateResponse getState(
            UUID consultationId,
            String rawToken
    ) {

        Consultation consultation =
                findOwnedConsultation(
                        consultationId,
                        rawToken
                );

        ensureAnalysisStep(
                consultation
        );

        Optional<AnalysisJob> job =
                currentJob(
                        consultation
                );

        return job
            .map(
                    current ->
                            toState(
                                    current,
                                    consultation
                            )
            )
            .orElseGet(
                    () ->
                            AnalysisStateResponse
                                    .notStarted(
                                            consultation
                                                    // 현재 보완을 이미 진행했는지
                                                    .getInformationSupplementCount()
                                    )
            );
    }

    @Transactional
    public ReopenAnalysisResponse reopenForMoreInfo(
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
                        .findForUpdateByIdAndGuestSession_Id(
                                consultationId,
                                guestSession.getId()
                        )
                        .orElseThrow(
                                ConsultationNotFoundException::new
                        );

        ensureAnalysisStep(
                consultation
        );

        AnalysisJob job =
                currentJob(
                        consultation
                )
                        .orElseThrow(
                                InvalidConsultationStateException::new
                        );

        if (
                job.getStatus()
                        != AnalysisJobStatus.NEEDS_MORE_INFO
                || consultation.getStatus()
                        != ConsultationStatus.NEEDS_MORE_INFO
        ) {
            throw new InvalidConsultationStateException();
        }

        consultation.reopenForMoreInfo(
                OffsetDateTime.now(
                        ZoneOffset.UTC
                )
        );

        return new ReopenAnalysisResponse(
                consultation.getId(),
                "SITUATION"
        );
    }

    @Transactional(readOnly = true)
    public InformationSupplementContextResponse
    getSupplementContext(
            UUID consultationId,
            String rawToken
    ) {

        Consultation consultation =
                findOwnedConsultation(
                        consultationId,
                        rawToken
                );

        // 일반 Situation 수정 화면이면 추가정보 Context가 아님
        if (
                consultation.getCurrentStep()
                        != ConsultationStep.SITUATION
                || consultation
                        .getInformationSupplementCount()
                        != 1
        ) {
            // active = False, count = getInformationSupplementCount()
            return InformationSupplementContextResponse
                    .inactive(
                            consultation
                                    .getInformationSupplementCount()
                    );
        }

        Optional<AnalysisJob> job =
                currentJob(
                        consultation
                );

        // 추가정보 입력하기 클릭 이후인데,
        // NEEDS_MORE_INFO Job이 없는 상황
        if (
                job.isEmpty()
                || job.get().getStatus()
                        != AnalysisJobStatus.NEEDS_MORE_INFO
                || job.get().getResultJson()
                        == null
        ) {
            // active = False, count == 1
            return InformationSupplementContextResponse
                    .inactive(
                            consultation
                                    .getInformationSupplementCount()
                    );
        }

        AnalysisAiResult result =
                deserializeResult(
                        job.get()
                                .getResultJson()
                );

        return new InformationSupplementContextResponse(
                true,
                consultation
                        .getInformationSupplementCount(),

                result
                        .additionalInformationNeeded
                        .stream()
                        .map(
                                item ->
                                        new InformationSupplementContextResponse.Item(
                                                item.topic,
                                                item.reason
                                        )
                        )
                        .toList()
        );
    }

    private Optional<AnalysisJob> currentJob(
            Consultation consultation
    ) {

        return analysisJobRepository
                .findByConsultation_IdAndCaseInputRevisionAndFollowUpAnswerRevision(
                        consultation.getId(),
                        consultation.getCaseInputRevision(),
                        consultation.getFollowUpAnswerRevision()
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

    private ConsultationSummary requireConfirmedSummary(
            Consultation consultation
    ) {

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

        if (summary.getConfirmedAt() == null) {
            throw new InvalidConsultationStateException();
        }

        return summary;
    }

    private void ensureAnalysisStep(
            Consultation consultation
    ) {

        if (
                consultation.getCurrentStep()
                        != ConsultationStep.ANALYSIS
        ) {
            throw new InvalidConsultationStateException();
        }
    }

    private String serializeResult(
            AnalysisAiResult result
    ) {

        try {
            return jsonMapper
                    .writeValueAsString(
                            result
                    );

        } catch (JacksonException exception) {

            throw new IllegalStateException(
                    "Failed to serialize analysis result"
            );
        }
    }

    private AnalysisAiResult deserializeResult(
            String resultJson
    ) {

        try {
            return jsonMapper.readValue(
                    resultJson,
                    AnalysisAiResult.class
            );

        } catch (JacksonException exception) {

            throw new IllegalStateException(
                    "Failed to deserialize analysis result"
            );
        }
    }

    private ConsultationSummaryAiResult
    deserializeSummary(
            String resultJson
    ) {

        try {
            return jsonMapper.readValue(
                    resultJson,
                    ConsultationSummaryAiResult.class
            );

        } catch (JacksonException exception) {

            throw new IllegalStateException(
                    "Failed to deserialize confirmed summary"
            );
        }
    }

    private AnalysisStateResponse toState(
            AnalysisJob job,
            Consultation consultation
    ) {

        AnalysisAiResult result = null;

        if (
                job.getResultJson() != null
                && (
                job.getStatus()
                        == AnalysisJobStatus.COMPLETED
                || job.getStatus()
                        == AnalysisJobStatus.NEEDS_MORE_INFO
                || job.getStatus()
                        == AnalysisJobStatus.INSUFFICIENT_INFORMATION
        )
        ) {
            result =
                    deserializeResult(
                            job.getResultJson()
                    );
        }

        return AnalysisStateResponse.from(
                job,
                result,
                consultation
                        .getInformationSupplementCount()
        );
    }
}