package com.financialhelper.ai.analysis;

import com.financialhelper.ai.AiOutputContractException;
import com.financialhelper.ai.AiProviderException;
import com.financialhelper.ai.OpenAiStructuredClient;
import com.financialhelper.account.AccountProperties;
import com.financialhelper.account.InputLimitException;
import com.financialhelper.ai.grounded.AnalysisEvidenceSnapshotData;
import com.financialhelper.ai.grounded.AnalysisEvidenceSnapshotService;
import com.financialhelper.ai.grounded.GroundedAiInputProjection;
import com.financialhelper.ai.grounded.GroundedEvidenceUnavailableException;
import com.financialhelper.ai.grounded.GroundedOutputValidator;
import com.financialhelper.procedure.FinancialActionPlanData;
import com.financialhelper.procedure.FinancialActionPlanService;
import com.financialhelper.procedure.PlanStatus;
import com.financialhelper.consultation.ConsultationScenario;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.boot.autoconfigure.condition
        .ConditionalOnProperty;

import org.springframework.scheduling.annotation.Async;

import org.springframework.stereotype.Component;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.util.Optional;
import java.util.UUID;
import java.util.Locale;

@Component
@ConditionalOnProperty(
        prefix = "app.ai",
        name = "enabled",
        havingValue = "true"
)
public class AnalysisWorker {

    private static final Logger log =
            LoggerFactory.getLogger(
                    AnalysisWorker.class
            );

    private static final String INSTRUCTIONS =
            """
            너는 금융소비자 보호 서비스의
            AI V1 사건 분석 단계다.

            사용자가 확인한 상담 요약만을 기반으로
            해결 리포트 작성에 필요한 핵심 쟁점을 구조화한다.

            반드시 다음 규칙을 따른다.

            1. 사용자가 확인한 정보만 사용한다.

            2. 입력되지 않은 사실을 추측하거나
               사실처럼 추가하지 않는다.

            3. 법적 승패, 배상 가능성,
               금융기관 위법 여부를 단정하지 않는다.

            4. Procedure-backed 입력에서는 제공된 evidence snapshot의
               evidenceId와 locator만 인용한다.
               법령, URL 또는 출처를 임의 생성하지 않는다.

            5. READY_FOR_REPORT는 현재 정보만으로
               행동 중심 AI V1 리포트를 만들 수 있을 때 사용한다.

            6. 사소한 정보가 조금 부족하다는 이유만으로
               NEEDS_MORE_INFO를 사용하지 않는다.

            7. 핵심 사건 이해 자체가 어려울 정도로
               중요한 정보가 빠졌을 때만
               NEEDS_MORE_INFO를 사용한다.

            8. NEEDS_MORE_INFO라면
               실제로 필요한 정보와 그 이유를 명확하게 작성한다.

            9. 주민등록번호, 계좌번호,
               카드번호 등 민감 식별정보를
               결과에 반복하지 않는다.

            10. 분석 결과는 Application Flow를
                직접 변경하는 명령이 아니다.
                실제 상태 전이는 Spring Boot가 결정한다.

            11. 출력은 반드시 제공된
                Structured Output Schema를 따른다.

            12. Procedure-backed 입력에서는 실제로 사용한
                snapshot evidenceId와 locator를 명시한다.
            """;

    private final AnalysisPersistenceService
            persistenceService;

    private final OpenAiStructuredClient
            openAiStructuredClient;

    private final AnalysisAiBusinessValidator
            businessValidator;

    private final JsonMapper jsonMapper;

    private final AnalysisEvidenceSnapshotService evidenceSnapshotService;

    private final GroundedOutputValidator groundedOutputValidator;
    private final AccountProperties accountProperties;
    private final FinancialActionPlanService actionPlanService;

    public AnalysisWorker(
            AnalysisPersistenceService persistenceService,
            OpenAiStructuredClient openAiStructuredClient,
            AnalysisAiBusinessValidator businessValidator,
            JsonMapper jsonMapper,
            AnalysisEvidenceSnapshotService evidenceSnapshotService,
            GroundedOutputValidator groundedOutputValidator,
            AccountProperties accountProperties,
            FinancialActionPlanService actionPlanService
    ) {
        this.persistenceService =
                persistenceService;

        this.openAiStructuredClient =
                openAiStructuredClient;

        this.businessValidator =
                businessValidator;

        this.jsonMapper =
                jsonMapper;

        this.evidenceSnapshotService = evidenceSnapshotService;

        this.groundedOutputValidator = groundedOutputValidator;
        this.accountProperties = accountProperties;
        this.actionPlanService = actionPlanService;
    }

    // OpenAI 요청을 다른 스레드에 넘겨서 실행
    // DB 트랜잭션 안에서 외부 API를 오래 기다리지 않는다.
    @Async("analysisTaskExecutor")
    public void run(
            UUID jobId
    ) {

        Optional<AnalysisData.Snapshot> snapshotOptional =
                persistenceService
                        .beginProcessing(
                                jobId
                        );

        if (snapshotOptional.isEmpty()) {
            return;
        }

        AnalysisData.Snapshot snapshot =
                snapshotOptional.get();

        try {

            if (snapshot.scenario() != null && snapshot.scenario() != ConsultationScenario.UNKNOWN) {
                FinancialActionPlanData plan = actionPlanService.buildForCurrent(snapshot.consultationId());
                if (plan.status() == PlanStatus.NEEDS_CLARIFICATION
                        && plan.coverageGaps().isEmpty()) {
                    persistenceService.complete(snapshot, partialResult(plan));
                    return;
                }
            }

            AnalysisEvidenceSnapshotData evidenceSnapshot =
                    evidenceSnapshotService.prepare(snapshot);

            AnalysisAiResult result =
                    openAiStructuredClient
                            .generateStructured(
                                    INSTRUCTIONS,
                                    buildModelInput(
                                            snapshot
                                    , evidenceSnapshot),
                                    AnalysisAiResult.class
                            );

            result =
                    businessValidator
                            .validate(
                                    result
                            );

            if (evidenceSnapshot != null) {
                groundedOutputValidator.validateAnalysis(result, evidenceSnapshot);
            }

            persistenceService.complete(
                    snapshot,
                    result,
                    evidenceSnapshot
            );

        } catch (InputLimitException exception) {
            log.warn("Analysis job input exceeded configured limit. jobId={}", jobId);
            persistenceService.fail(jobId, "AI_INPUT_TOO_LARGE");

        } catch (AiOutputContractException exception) {

            log.warn("Analysis job failed validation. jobId={}", jobId);
            persistenceService.fail(jobId, "AI_VALIDATION_FAILED");

        } catch (GroundedEvidenceUnavailableException exception) {

            String failureCode = groundedFailureCode(exception);
            log.warn("Analysis job evidence unavailable. jobId={}, category={}", jobId, failureCode);
            // Keep the public API contract stable while preserving the precise
            // root category in the server log for diagnosis.
            persistenceService.fail(jobId, publicFailureCode(failureCode));

        } catch (AiProviderException exception) {

            // Provider response, 상담 원문,
            // AI output을 로그로 남기지 않는다
            log.warn(
                    "Analysis job failed. jobId={}, type={}",
                    jobId,
                    exception
                            .getClass()
                            .getSimpleName()
            );

            persistenceService.fail(
                    jobId,
                    "AI_GENERATION_FAILED"
            );

        } catch (RuntimeException exception) {

            // 비동기 Worker가 예상치 못한 RuntimeException으로 끝나면서 PROCESSING에 영구적으로 남지 않게 한다.
            log.error(
                    "Analysis job failed unexpectedly. jobId={}, type={}",
                    jobId,
                    exception
                            .getClass()
                            .getSimpleName()
            );

            persistenceService.fail(
                    jobId,
                    "INTERNAL_ERROR"
            );
        }
    }

    static String groundedFailureCode(
            GroundedEvidenceUnavailableException exception
    ) {
        String message = exceptionMessages(exception);

        if (message.contains("law")) {
            return lawFailureCode(message);
        }

        if (message.contains("financial action plan")) {
            return "FAP_UNAVAILABLE";
        }

        if (message.contains("procedure")) {
            return "PROCEDURE_UNAVAILABLE";
        }

        if (message.contains("generation")) {
            return "RETRIEVAL_GENERATION_MISMATCH";
        }

        if (message.contains("index")) {
            return "RETRIEVAL_MAPPING_MISSING";
        }

        if (message.contains("official evidence") || message.contains("source chunk")) {
            return "NO_APPROVED_EVIDENCE";
        }

        if (message.contains("snapshot")
                || message.contains("confirmed case")
                || message.contains("analysis job")
                || message.contains("source evidence changed")) {
            return "SNAPSHOT_UNAVAILABLE";
        }

        return "RETRIEVAL_UNAVAILABLE";
    }

    private static String lawFailureCode(String message) {
        if (message.contains("disabled") || message.contains("law_oc")
                || message.contains("configuration")) {
            return "LAW_API_CONFIGURATION_MISSING";
        }
        if (message.contains("http error 401") || message.contains("http error 403")
                || message.contains("unauthorized") || message.contains("forbidden")) {
            return "LAW_API_AUTH_FAILED";
        }
        if (message.contains("http error")) {
            return "LAW_API_HTTP_FAILED";
        }
        if (message.contains("search returned") || message.contains("search failed")) {
            return "LAW_SEARCH_FAILED";
        }
        if (message.contains("requested article") || message.contains("no article")
                || message.contains("article number is missing")) {
            return "LAW_REQUIRED_ARTICLE_NOT_FOUND";
        }
        if (message.contains("no version applicable") || message.contains("no current statute")
                || message.contains("future law version")) {
            return "LAW_EFFECTIVE_VERSION_NOT_FOUND";
        }
        if (message.contains("identity") || message.contains("provenance")
                || message.contains("effective date") || message.contains("allowlist")) {
            return "LAW_EVIDENCE_VALIDATION_FAILED";
        }
        if (message.contains("malformed") || message.contains("missing law")
                || message.contains("empty response") || message.contains("invalid ")) {
            return "LAW_RESPONSE_PARSE_FAILED";
        }
        return "LAW_EVIDENCE_UNAVAILABLE";
    }

    private static String publicFailureCode(String internalCategory) {
        return internalCategory != null && internalCategory.startsWith("LAW_")
                ? "LAW_EVIDENCE_UNAVAILABLE"
                : internalCategory;
    }

    private static String exceptionMessages(Throwable root) {
        StringBuilder messages = new StringBuilder();
        Throwable current = root;
        while (current != null) {
            if (current.getMessage() != null) {
                messages.append(' ').append(current.getMessage());
            }
            current = current.getCause();
        }
        return messages.toString().toLowerCase(Locale.ROOT);
    }

    private AnalysisAiResult partialResult(FinancialActionPlanData plan) {
        AnalysisAiResult result = new AnalysisAiResult();
        result.outcome = AnalysisAiResult.Outcome.NEEDS_MORE_INFO;
        result.analysisSummary = "현재 확인된 내용으로 안내할 수 있는 부분만 먼저 정리했습니다.";
        result.keyIssues = plan.actions().isEmpty()
                ? java.util.List.of(issue("추가 확인이 필요해요", "중요한 정보가 확인되지 않아 이 상황에 맞는 행동을 아직 확정할 수 없습니다."))
                : plan.actions().stream()
                        .map(action -> issue(action.title(), action.description()))
                        .toList();
        result.additionalInformationNeeded = plan.unresolvedFacts().stream()
                .map(fact -> {
                    AnalysisAiResult.AdditionalInformation information =
                            new AnalysisAiResult.AdditionalInformation();
                    information.topic = factLabel(fact);
                    information.reason = "이 정보가 확인되면 해당 조건에 맞는 안내를 더 정확히 정리할 수 있어요.";
                    return information;
                })
                .toList();
        result.evidenceCitations = java.util.List.of();
        return result;
    }

    private AnalysisAiResult.KeyIssue issue(String title, String explanation) {
        AnalysisAiResult.KeyIssue issue = new AnalysisAiResult.KeyIssue();
        issue.title = title;
        issue.explanation = explanation;
        return issue;
    }

    private String factLabel(String fact) {
        return switch (fact) {
            case "institution" -> "금융회사";
            case "productType" -> "금융상품 종류";
            case "cardLost" -> "분실·도난 여부";
            case "unauthorizedPayment" -> "본인이 하지 않은 결제 여부";
            case "transactionType" -> "거래 유형";
            case "domestic" -> "국내 거래 여부";
            case "reported" -> "분실·도난 신고 여부";
            case "incidentDate" -> "사고 발생 날짜";
            case "transferCompleted" -> "송금 완료 여부";
            case "userInitiatedTransfer" -> "본인 송금 여부";
            case "suspiciousTransfer" -> "사기 의심 여부";
            case "unauthorizedTransaction" -> "본인 아닌 거래 여부";
            case "reportedToFinancialInstitution" -> "금융회사 신고 여부";
            case "policeReported" -> "경찰 신고 여부";
            case "suspiciousLinkClicked" -> "의심 링크 클릭 여부";
            case "maliciousAppInstalled" -> "의심 앱 설치 여부";
            case "remoteControlUsed" -> "원격제어 여부";
            case "personalInfoExposed" -> "개인정보 노출 여부";
            case "authenticationInfoExposed", "accessCredentialExposed" -> "인증정보 노출 여부";
            default -> fact;
        };
    }

    private String buildModelInput(
            AnalysisData.Snapshot snapshot,
            AnalysisEvidenceSnapshotData evidenceSnapshot
    ) {

        try {

            GroundedAiInputProjection.AnalysisInput input =
                    GroundedAiInputProjection.forAnalysis(
                            snapshot.scenario().name(),
                            snapshot.confirmedSummary(),
                            evidenceSnapshot
                    );

            String serialized = jsonMapper.writeValueAsString(input);
            log.info(
                    "Analysis prompt prepared caseRevision={} followUpRevision={} inputCharacters={} limit={}",
                    snapshot.caseInputRevision(),
                    snapshot.followUpAnswerRevision(),
                    serialized.length(),
                    accountProperties.limits().maxAiInputCharacters()
            );
            if (serialized.length() > accountProperties.limits().maxAiInputCharacters()) {
                throw new InputLimitException("ai");
            }
            return serialized;

        } catch (JacksonException exception) {

            throw new IllegalStateException(
                    "Failed to serialize analysis prompt input"
            );
        }
    }

}
