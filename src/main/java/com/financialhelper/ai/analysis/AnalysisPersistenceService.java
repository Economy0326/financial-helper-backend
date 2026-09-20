package com.financialhelper.ai.analysis;

import com.financialhelper.ai.OpenAiProperties;
import com.financialhelper.ai.grounded.AnalysisEvidenceSnapshotData;
import com.financialhelper.ai.grounded.AnalysisEvidenceSnapshotService;
import com.financialhelper.account.AccountAiQuotaService;
import com.financialhelper.account.GlobalAiBudgetGuard;
import com.financialhelper.account.AccountProperties;
import com.financialhelper.procedure.FinancialActionPlanData;
import com.financialhelper.procedure.FinancialActionPlanService;
import com.financialhelper.procedure.PlanStatus;

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
import com.financialhelper.consultation.ConsultationScenarioResolver;
import com.financialhelper.consultation.InvalidConsultationStateException;
import com.financialhelper.consultation.UnsupportedConsultationScopeException;

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

    private final AnalysisProperties
        analysisProperties;

    private final AnalysisEvidenceSnapshotService
            evidenceSnapshotService;
    private final AccountAiQuotaService accountAiQuotaService;
    private final GlobalAiBudgetGuard globalAiBudgetGuard;
    private final AccountProperties accountProperties;
    private final FinancialActionPlanService actionPlanService;

    public AnalysisPersistenceService(
            ConsultationRepository consultationRepository,
            ConsultationSummaryRepository summaryRepository,
            AnalysisJobRepository analysisJobRepository,
            GuestSessionService guestSessionService,
            OpenAiProperties openAiProperties,
            JsonMapper jsonMapper,
            AnalysisProperties analysisProperties,
            AnalysisEvidenceSnapshotService evidenceSnapshotService,
            AccountAiQuotaService accountAiQuotaService,
            GlobalAiBudgetGuard globalAiBudgetGuard,
            AccountProperties accountProperties,
            FinancialActionPlanService actionPlanService
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

        this.analysisProperties =
                analysisProperties;

        this.evidenceSnapshotService = evidenceSnapshotService;
        this.accountAiQuotaService = accountAiQuotaService;
        this.globalAiBudgetGuard = globalAiBudgetGuard;
        this.accountProperties = accountProperties;
        this.actionPlanService = actionPlanService;
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

            recoverStaleJobIfNeeded(
                    job,
                    consultation
            );

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

        // Summary가 Confirm인 경우에만 새로운 AI 사용량을 예약한다.
        if (summary.getConfirmedAt() == null) {
            throw new InvalidConsultationStateException();
        }

        rejectUnsupportedProcedureScope(consultation);

        if (analysisJobRepository.sumAttemptCountByConsultationId(consultationId)
                >= accountAiAttemptsLimit()) {
            throw new AnalysisRetryLimitExceededException();
        }

        accountAiQuotaService.reserveIfAuthenticated();
        globalAiBudgetGuard.reserveRequestUnit();

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

        recoverStaleJobIfNeeded(
                job,
                consultation
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

        rejectUnsupportedProcedureScope(consultation);

        if (
                job.getAttemptCount()
                        >= analysisProperties
                        .maxAttempts()
        ) {
            throw new AnalysisRetryLimitExceededException();
        }

        OffsetDateTime now =
                OffsetDateTime.now(
                        ZoneOffset.UTC
                );

        accountAiQuotaService.reserveIfAuthenticated();
        globalAiBudgetGuard.reserveRequestUnit();

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

    private void rejectUnsupportedProcedureScope(Consultation consultation) {
        if (ConsultationScenarioResolver.resolve(consultation)
                == com.financialhelper.consultation.ConsultationScenario.UNKNOWN) {
            return;
        }
        FinancialActionPlanData plan = actionPlanService.buildForCurrent(consultation.getId());
        if (plan.status() == PlanStatus.UNSUPPORTED) {
            throw new UnsupportedConsultationScopeException();
        }
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
                        ConsultationScenarioResolver.resolve(consultation),
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

        complete(snapshot, result, null);
    }

    /**
     * 별도의 1회 free-form 정보 보완 flow를 열지 않고 결정적 partial 결과를 완료한다.
     * structured follow-up clarification 횟수를 이미 소진했을 때 사용한다.
     */
    @Transactional
    public void completeWithoutSupplement(
            AnalysisData.Snapshot snapshot,
            AnalysisAiResult result
    ) {
        complete(snapshot, result, null, true);
    }

    private int accountAiAttemptsLimit() {
        // 기존 job별 최대 시도 정책을 기준으로 삼는다. account 단위 guard는 quota
        // service를 통해 AccountProperties가 제공하며, 이 fallback은 legacy caller를
        // 기존 retry limit 안으로 제한한다.
        return Math.max(accountProperties.limits().consultationAiAttempts(), 1);
    }

    @Transactional
    public void complete(
            AnalysisData.Snapshot snapshot,
            AnalysisAiResult result,
            AnalysisEvidenceSnapshotData evidenceSnapshot
    ) {

        complete(snapshot, result, evidenceSnapshot, false);
    }

    private void complete(
            AnalysisData.Snapshot snapshot,
            AnalysisAiResult result,
            AnalysisEvidenceSnapshotData evidenceSnapshot,
            boolean forceInsufficientInformation
    ) {

        if (evidenceSnapshot != null) {
            evidenceSnapshotService.assertCurrent(evidenceSnapshot);
        }

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
                forceInsufficientInformation
                ||
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

    @Transactional
    public AnalysisStateResponse getState(
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

        Optional<AnalysisJob> job =
                currentJob(
                        consultation
                );

        if (job.isEmpty()) {

            // 사용자가 Analysis 시작 버튼에 도달하기 전에 범위 밖으로 확인된
            // Procedure를 판정한다. read-only 결정적 검사이며 job 생성, AI quota
            // 소비 또는 OpenAI 호출을 하지 않는다.
            if (isStructuredScenario(consultation)) {
                try {
                    FinancialActionPlanData plan = actionPlanService.buildForCurrent(consultationId);
                    if (plan.status() == PlanStatus.UNSUPPORTED) {
                        return AnalysisStateResponse.unsupported(
                                consultation.getInformationSupplementCount());
                    }
                } catch (RuntimeException ignored) {
                    // 아직 plan을 평가할 수 없으면 일반 NOT_STARTED 동작을 유지한다.
                    // reserveStart가 최종 fail-closed guard로 남는다.
                }
            }

            return AnalysisStateResponse
                    .notStarted(
                            consultation
                                    .getInformationSupplementCount()
                    );
        }

        AnalysisJob currentJob =
                job.get();

        recoverStaleJobIfNeeded(
                currentJob,
                consultation
        );

        return toState(
                currentJob,
                consultation
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

        // Structured General Consultation scenario는 Analysis 전에 missing fact 수집을
        // 마친다. legacy NEEDS_MORE_INFO row가 Situation을 다시 열거나 두 번째
        // 보완 질문을 만들어서는 안 된다.
        if (isStructuredScenario(consultation)) {
            throw new InvalidConsultationStateException();
        }

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

        if (isStructuredScenario(consultation)) {
            return InformationSupplementContextResponse.inactive(
                    consultation.getInformationSupplementCount());
        }

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

    private void recoverStaleJobIfNeeded(
            AnalysisJob job,
            Consultation consultation
    ) {

        OffsetDateTime referenceTime;

        if (
                job.getStatus()
                        == AnalysisJobStatus.QUEUED
        ) {

            referenceTime =
                    job.getQueuedAt();

        } else if (
                job.getStatus()
                        == AnalysisJobStatus.PROCESSING
        ) {

            referenceTime =
                    job.getStartedAt() != null
                            ? job.getStartedAt()
                            : job.getQueuedAt();

        } else {

            return;
        }

        OffsetDateTime now =
                OffsetDateTime.now(
                        ZoneOffset.UTC
                );

        if (
                !referenceTime
                        // 기준시간 + 허용시간 => 제한시간 이 현재 시간보다 이전일경우 True -> 시간이 초과된 상태
                        // !True => False
                        .plus(
                                analysisProperties
                                        .staleAfter()
                        )
                        .isBefore(now)
        ) {
            return;
        }

        job.fail(
                "STALE_ANALYSIS_JOB",
                now
        );

        // 이미 새로운 Revision으로 이동했다면
        // 과거 Job 때문에 현재 Consultation을 FAILED로 만들면 안 된다.
        if (
                consultation.getCaseInputRevision()
                        == job.getCaseInputRevision()
                && consultation
                        .getFollowUpAnswerRevision()
                        == job.getFollowUpAnswerRevision()
                && consultation.getCurrentStep()
                        == ConsultationStep.ANALYSIS
                && consultation.getStatus()
                        == ConsultationStatus.ANALYZING
        ) {

            consultation
                    .markAnalysisFailed(
                            now
                    );
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

        AnalysisStateResponse response = AnalysisStateResponse.from(
                job,
                result,
                consultation
                        .getInformationSupplementCount()
                , partialSafeActions(job, consultation)
        );
        if (isStructuredScenario(consultation)
                && job.getStatus() == AnalysisJobStatus.NEEDS_MORE_INFO) {
            return new AnalysisStateResponse(
                    AnalysisJobStatus.INSUFFICIENT_INFORMATION.name(),
                    response.failureCode(),
                    response.attemptCount(),
                    response.informationSupplementCount(),
                    false,
                    response.additionalInformationNeeded(),
                    response.safeActions());
        }
        return response;
    }

    private boolean isStructuredScenario(Consultation consultation) {
        return switch (ConsultationScenarioResolver.resolve(consultation)) {
            case CARD_LOSS_UNAUTHORIZED_USE,
                    VOICE_PHISHING_SUSPICIOUS_TRANSFER,
                    UNAUTHORIZED_ACCOUNT_TRANSFER,
                    PERSONAL_INFO_SMISHING_MALICIOUS_APP -> true;
            case UNKNOWN -> false;
        };
    }

    private java.util.List<AnalysisStateResponse.SafeAction> partialSafeActions(
            AnalysisJob job,
            Consultation consultation
    ) {
        // 한 번의 정보 보완 기회 뒤 job은 INSUFFICIENT_INFORMATION으로 이동한다.
        // 독립적으로 검토된 safe action은 이 terminal 상태에서도 유효하며 계속 보여야 한다.
        if (job.getStatus() != AnalysisJobStatus.NEEDS_MORE_INFO
                && job.getStatus() != AnalysisJobStatus.INSUFFICIENT_INFORMATION
                || com.financialhelper.consultation.ConsultationScenarioResolver.resolve(consultation)
                == com.financialhelper.consultation.ConsultationScenario.UNKNOWN) {
            return java.util.List.of();
        }
        try {
            FinancialActionPlanData plan = actionPlanService.buildForCurrent(consultation.getId());
            if (plan.status() != PlanStatus.NEEDS_CLARIFICATION || !plan.coverageGaps().isEmpty()) {
                return java.util.List.of();
            }
            return plan.actions().stream()
                    .map(action -> new AnalysisStateResponse.SafeAction(
                            action.actionId(), action.title(), action.description()))
                    .toList();
        } catch (RuntimeException ignored) {
            // optional guidance를 불러오지 못해도 state polling이 정보 부족 결과를
            // technical failure로 바꾸어서는 안 된다.
            return java.util.List.of();
        }
    }
}
