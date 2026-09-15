package com.financialhelper.ai.report;

import com.financialhelper.ai.AiOutputContractException;
import com.financialhelper.ai.AiProviderException;
import com.financialhelper.ai.OpenAiProperties;
import com.financialhelper.ai.OpenAiStructuredClient;

import com.financialhelper.ai.analysis.AnalysisAiResult;
import com.financialhelper.ai.grounded.AnalysisEvidenceSnapshotData;
import com.financialhelper.ai.grounded.GroundedEvidenceUnavailableException;
import com.financialhelper.ai.grounded.GroundedOutputValidator;
import com.financialhelper.ai.summary.ConsultationSummaryAiResult;

import com.financialhelper.ai.understanding.AiGenerationFailedException;

import org.springframework.boot.autoconfigure.condition
        .ConditionalOnProperty;

import org.springframework.stereotype.Service;

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

            4. CARD 입력에서는 제공된 evidence snapshot의
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

            13. CARD actionSteps와 requiredDocuments에는
                snapshot의 승인된 actionId/documentId, 제목, 설명을
                그대로 사용한다.
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

    public ConsultationReportService(
            OpenAiStructuredClient openAiStructuredClient,
            OpenAiProperties openAiProperties,
            ConsultationReportBusinessValidator businessValidator,
            ConsultationReportPersistenceService persistenceService,
            JsonMapper jsonMapper,
            GroundedOutputValidator groundedOutputValidator
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

        try {

            result =
                    openAiStructuredClient
                            .generateStructured(
                                    INSTRUCTIONS,
                                    buildModelInput(
                                            snapshot
                                    ),
                                    ConsultationReportAiResult.class
                            );

            result =
                    businessValidator
                            .validate(
                                    result
                            );

            if (snapshot.groundedEvidence() != null) {
                groundedOutputValidator.validateReport(
                        result,
                        snapshot.groundedEvidence()
                );
            }

        } catch (
                AiProviderException
                | AiOutputContractException
                | GroundedEvidenceUnavailableException
                        exception
        ) {

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

            ReportPromptInput input =
                    new ReportPromptInput(
                            snapshot.category().name(),
                            snapshot.summary(),
                            snapshot.analysis(),
                            snapshot.groundedEvidence()
                    );

            return jsonMapper
                    .writeValueAsString(
                            input
                    );

        } catch (JacksonException exception) {

            throw new IllegalStateException(
                    "Failed to serialize report prompt input"
            );
        }
    }

    private record ReportPromptInput(
            String consultationCategory,
            ConsultationSummaryAiResult confirmedSummary,
            AnalysisAiResult analysisResult,
            AnalysisEvidenceSnapshotData evidenceSnapshot
    ) {
    }
}
