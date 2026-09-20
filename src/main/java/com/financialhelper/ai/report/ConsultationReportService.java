package com.financialhelper.ai.report;

import com.financialhelper.ai.AiOutputContractException;
import com.financialhelper.ai.AiProviderException;
import com.financialhelper.ai.OpenAiProperties;
import com.financialhelper.ai.OpenAiStructuredClient;

import com.financialhelper.ai.grounded.GroundedAiInputProjection;
import com.financialhelper.ai.grounded.GroundedEvidenceUnavailableException;
import com.financialhelper.ai.grounded.GroundedOutputValidator;
import com.financialhelper.account.AccountProperties;
import com.financialhelper.account.InputLimitException;
import com.financialhelper.procedure.FinancialActionPlanData;

import com.financialhelper.ai.understanding.AiGenerationFailedException;

import org.springframework.boot.autoconfigure.condition
        .ConditionalOnProperty;

import org.springframework.stereotype.Service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.util.Optional;
import java.util.UUID;

@Service
@ConditionalOnProperty(
        prefix = "app.ai",
        name = "enabled",
        havingValue = "true"
)
public class ConsultationReportService {

    private static final Logger log = LoggerFactory.getLogger(ConsultationReportService.class);

    private static final String INSTRUCTIONS =
            """
            너는 금융소비자 보호 서비스의
            AI V1 해결 리포트 작성 단계다.

            입력으로 제공된 사용자가 확인한 Summary와
            검증 완료된 Analysis Result만 사용한다.

            반드시 다음 규칙을 따른다.

            1. 확인되지 않은 사실을 추가하지 않는다.

            2. 금융회사 또는 제3자의 위법 여부를 단정하지 않는다.

            3. 법적 승패, 손해배상 가능성,
               환급 가능성을 확정적으로 표현하지 않는다.

            4. Procedure-backed 입력에서는 제공된 evidence snapshot의
               evidenceId와 locator만 인용한다.
               법령, URL, 기관 지침 또는 사례를 만들어내지 않는다.

            5. 행동 단계는 사용자가 실제로 수행할 수 있는
               저위험 확인·문의·자료확보 중심으로 작성한다.

            6. 사용자가 반드시 특정 행동을 해야 한다는 식으로
               과도하게 단정하지 않는다.

            7. 민감 개인정보를 결과에 반복하지 않는다.

            8. 어려운 금융 용어가 등장하면
               terms에서 쉬운 한국어로 설명한다.

            9. complaintDraft는 사용자가 수정해서 사용할 수 있는
               일반적인 초안으로 작성한다.

            10. complaintDraft 안에서도
                확인되지 않은 사실이나 법적 결론을 만들지 않는다.

            11. ActionStep order는
                1부터 순서대로 작성한다.

            12. 출력은 제공된 Structured Output Schema만 사용한다.

            13. actionSteps와 requiredDocuments에는
                snapshot의 승인된 actionId/documentId, 제목, 설명을
                그대로 사용한다.

            14. actionSteps는 snapshot actionPlan.actions의 항목 수와
                순서를 그대로 유지한다. 제목과 설명을 요약하거나
                바꾸지 말고 그대로 복사한다.

            15. snapshot actionPlan.requiredDocuments가 비어 있으면
                requiredDocuments도 빈 배열로 반환한다. 확인되지 않은
                자료를 추가하지 않는다.
            """;

    private final OpenAiStructuredClient
            openAiStructuredClient;

    private final OpenAiProperties
            openAiProperties;

    private final ConsultationReportBusinessValidator
            businessValidator;

    private final ConsultationReportPersistenceService
            persistenceService;

    private final JsonMapper jsonMapper;

    private final GroundedOutputValidator groundedOutputValidator;
    private final AccountProperties accountProperties;

    public ConsultationReportService(
            OpenAiStructuredClient openAiStructuredClient,
            OpenAiProperties openAiProperties,
            ConsultationReportBusinessValidator businessValidator,
            ConsultationReportPersistenceService persistenceService,
            JsonMapper jsonMapper,
            GroundedOutputValidator groundedOutputValidator,
            AccountProperties accountProperties
    ) {
        this.openAiStructuredClient =
                openAiStructuredClient;

        this.openAiProperties =
                openAiProperties;

        this.businessValidator =
                businessValidator;

        this.persistenceService =
                persistenceService;

        this.jsonMapper =
                jsonMapper;

        this.groundedOutputValidator = groundedOutputValidator;
        this.accountProperties = accountProperties;
    }

    public ConsultationReportStateResponse prepare(
            UUID consultationId,
            String rawToken
    ) {

        Optional<ConsultationReportData.Document>
                existing =
                persistenceService.findExisting(
                        consultationId,
                        rawToken
                );

        if (existing.isPresent()) {
            return ConsultationReportStateResponse
                    .ready(
                            existing.get()
                    );
        }

        ConsultationReportData.Snapshot snapshot =
                persistenceService
                        .loadGenerationSnapshot(
                                consultationId,
                                rawToken
                        );

        ConsultationReportAiResult result;
        String failureStage = "INPUT";

        try {

            String modelInput = buildModelInput(snapshot);

            failureStage = "AI_PROVIDER";
            result =
                    openAiStructuredClient
                            .generateStructured(
                                    INSTRUCTIONS,
                                    modelInput,
                                    ConsultationReportAiResult.class
                            );

            // action plan은 Backend가 결정한다. narrative field는 model 작성으로 유지하되
            // 결과 검증 전에 immutable Evidence snapshot에서 Procedure field를 조합한다.
            // model 응답의 무해한 문구나 순서 차이로 grounded report가 502가 되는 것을 막는다.
            if (snapshot.groundedEvidence() != null && result != null) {
                result = composeProcedureFields(
                        result, snapshot.groundedEvidence().actionPlan());
            }

            failureStage = "AI_OUTPUT_VALIDATION";
            result =
                    businessValidator
                            .validate(
                                    result
                            );

            if (snapshot.groundedEvidence() != null) {
                failureStage = "GROUNDED_OUTPUT_VALIDATION";
                groundedOutputValidator.validateReport(
                        result,
                        snapshot.groundedEvidence()
                );
            }

        } catch (
                AiProviderException
                | AiOutputContractException
                | GroundedEvidenceUnavailableException
                | InputLimitException
                        exception
        ) {

            // public 502 contract는 안정적으로 유지하되 진단용 안전한 machine category는
            // 보존한다. prompt, 사용자 situation, model 출력, provider credential은 log로 남기지 않는다.
            log.warn(
                    "Report generation failed consultationId={} caseRevision={} followUpRevision={} stage={} category={} rule={} type={}",
                    consultationId,
                    snapshot.caseInputRevision(),
                    snapshot.followUpAnswerRevision(),
                    failureStage,
                    reportFailureCategory(exception),
                    safeRule(exception),
                    exception.getClass().getSimpleName()
            );

            throw new AiGenerationFailedException();
        }

        ConsultationReportData.Document saved =
                persistenceService
                        .saveIfCurrent(
                                snapshot,
                                result,
                                openAiProperties.model()
                        );

        return ConsultationReportStateResponse
                .ready(saved);
    }

    public ConsultationReportStateResponse getState(
            UUID consultationId,
            String rawToken
    ) {

        return persistenceService
                .getState(
                        consultationId,
                        rawToken
                );
    }

    public ConsultationReportStateResponse getStateForAccount(UUID consultationId) {
        return persistenceService.getStateForAccount(consultationId);
    }

    private String buildModelInput(
            ConsultationReportData.Snapshot snapshot
    ) {

        try {

            GroundedAiInputProjection.ReportInput input =
                    GroundedAiInputProjection.forReport(
                            snapshot.category().name(),
                            snapshot.summary(),
                            snapshot.analysis(),
                            snapshot.groundedEvidence()
                    );

            String serialized = jsonMapper
                    .writeValueAsString(
                            input
                    );
            log.info(
                    "Report prompt prepared caseRevision={} followUpRevision={} inputCharacters={} limit={}",
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
                    "Failed to serialize report prompt input"
            );
        }
    }

    private static String reportFailureCategory(Exception exception) {
        if (exception instanceof InputLimitException) {
            return "AI_INPUT_TOO_LARGE";
        }
        if (exception instanceof AiProviderException) {
            return "AI_PROVIDER_FAILED";
        }
        if (exception instanceof GroundedEvidenceUnavailableException) {
            return "GROUNDED_EVIDENCE_VALIDATION_FAILED";
        }
        if (exception instanceof AiOutputContractException) {
            return "AI_OUTPUT_VALIDATION_FAILED";
        }
        return "REPORT_GENERATION_FAILED";
    }

    /**
     * Backend가 결정하는 Procedure field만 복사한다. 설명, 용어, 초안은 model이
     * 제공하고 action identity, 순서, 승인 document identity는 Evidence snapshot에
     * capture된 plan에서 가져온다.
     */
    static ConsultationReportAiResult composeProcedureFields(
            ConsultationReportAiResult result,
            FinancialActionPlanData plan
    ) {
        if (plan == null) {
            return result;
        }

        ConsultationReportAiResult.FirstAction first =
                new ConsultationReportAiResult.FirstAction();
        if (!plan.actions().isEmpty()) {
            FinancialActionPlanData.Action action = plan.actions().getFirst();
            first.actionId = action.actionId();
            first.title = action.title();
            first.description = action.description();
        }
        result.firstAction = first;

        // plan list가 기준 순서다. 오래된 row나 model 응답에는 누락, 중복, 재정렬된
        // 값이 있을 수 있으므로 저장된/model-facing order field를 전달하지 않는다.
        // validator가 정확히 1..N을 보도록 최종 public report를 결정적 plan 위치로 다시 번호 매긴다.
        result.actionSteps = java.util.stream.IntStream.range(0, plan.actions().size())
                .mapToObj(index -> {
            FinancialActionPlanData.Action action = plan.actions().get(index);
            ConsultationReportAiResult.ActionStep step =
                    new ConsultationReportAiResult.ActionStep();
            step.actionId = action.actionId();
            step.order = index + 1;
            step.title = action.title();
            step.description = action.description();
            return step;
        }).toList();

        java.util.Map<String, String> modelReasons = new java.util.HashMap<>();
        if (result.requiredDocuments != null) {
            for (ConsultationReportAiResult.RequiredDocument document : result.requiredDocuments) {
                if (document != null && document.documentId != null
                        && document.reason != null && !document.reason.isBlank()) {
                    modelReasons.put(document.documentId, document.reason);
                }
            }
        }
        result.requiredDocuments = plan.requiredDocuments().stream().map(document -> {
            ConsultationReportAiResult.RequiredDocument output =
                    new ConsultationReportAiResult.RequiredDocument();
            output.documentId = document.documentId();
            output.name = document.title();
            output.reason = modelReasons.getOrDefault(document.documentId(),
                    document.title());
            return output;
        }).toList();
        return result;
    }

    private static String safeRule(Exception exception) {
        if (exception == null || exception.getMessage() == null
                || exception.getMessage().isBlank()) {
            return "UNSPECIFIED";
        }
        // Validator message는 정적 rule 식별자/설명이다. 진단 범위를 제한하고
        // model 출력이나 prompt 문장을 포함하지 않는다.
        String safe = exception.getMessage().replaceAll("[\\r\\n]+", " ");
        return safe.substring(0, Math.min(safe.length(), 160));
    }

}
