package com.financialhelper.ai.analysis;

import com.financialhelper.ai.AiOutputContractException;
import com.financialhelper.ai.AiProviderException;
import com.financialhelper.ai.OpenAiStructuredClient;
import com.financialhelper.ai.summary.ConsultationSummaryAiResult;

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

            4. 아직 공식 금융자료 Retrieval이 제공되지 않았으므로
               법령, 판례, 공식 기관 지침,
               URL 또는 출처를 임의 생성하지 않는다.

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
            """;

    private final AnalysisPersistenceService
            persistenceService;

    private final OpenAiStructuredClient
            openAiStructuredClient;

    private final AnalysisAiBusinessValidator
            businessValidator;

    private final JsonMapper jsonMapper;

    public AnalysisWorker(
            AnalysisPersistenceService persistenceService,
            OpenAiStructuredClient openAiStructuredClient,
            AnalysisAiBusinessValidator businessValidator,
            JsonMapper jsonMapper
    ) {
        this.persistenceService =
                persistenceService;

        this.openAiStructuredClient =
                openAiStructuredClient;

        this.businessValidator =
                businessValidator;

        this.jsonMapper =
                jsonMapper;
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

            AnalysisAiResult result =
                    openAiStructuredClient
                            .generateStructured(
                                    INSTRUCTIONS,
                                    buildModelInput(
                                            snapshot
                                    ),
                                    AnalysisAiResult.class
                            );

            result =
                    businessValidator
                            .validate(
                                    result
                            );

            persistenceService.complete(
                    snapshot,
                    result
            );

        } catch (
                AiProviderException
                | AiOutputContractException
                        exception
        ) {

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

    private String buildModelInput(
            AnalysisData.Snapshot snapshot
    ) {

        try {

            AnalysisPromptInput input =
                    new AnalysisPromptInput(
                            snapshot.category()
                                    .name(),

                            snapshot
                                    .confirmedSummary()
                    );

            return jsonMapper
                    .writeValueAsString(
                            input
                    );

        } catch (JacksonException exception) {

            throw new IllegalStateException(
                    "Failed to serialize analysis prompt input"
            );
        }
    }

    private record AnalysisPromptInput(
            String consultationCategory,
            ConsultationSummaryAiResult confirmedSummary
    ) {
    }
}