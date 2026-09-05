package com.financialhelper.ai.summary;

import com.financialhelper.ai.AiOutputContractException;
import com.financialhelper.ai.AiProviderException;
import com.financialhelper.ai.OpenAiProperties;
import com.financialhelper.ai.OpenAiStructuredClient;

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
public class ConsultationSummaryService {

    private static final String INSTRUCTIONS =
            """
            너는 금융소비자 보호 서비스의 상담 요약 단계다.

            제공된 상담 정보와 Follow-up Answer만 사용해서
            사용자가 확인할 수 있는 요약을 작성한다.

            반드시 다음 규칙을 따른다.

            1. 사용자가 제공했거나 앞 단계에서 검증된 정보만 사용한다.

            2. 추측이나 과장, 법적 결론을 만들지 않는다.

            3. 승소 가능성, 보상 가능성,
               금융기관의 위법 여부를 단정하지 않는다.

            4. 법령, 공식 출처, URL을 임의 생성하지 않는다.

            5. 주민등록번호, 계좌번호, 카드번호 등
               민감 식별정보를 반복해서 적지 않는다.

            6. headline은 짧은 한국어 제목으로 작성한다.

            7. summaryText는 사용자가 상담 내용을 확인할 수 있도록
               명확하고 자연스러운 한국어로 작성한다.

            8. keyPoints는 핵심만 짧게 정리한다.

            9. 출력은 반드시 제공된 Structured Output Schema를 따른다.
            """;

    private final OpenAiStructuredClient
            openAiStructuredClient;

    private final OpenAiProperties
            openAiProperties;

    private final ConsultationSummaryBusinessValidator
            businessValidator;

    private final ConsultationSummaryPersistenceService
            persistenceService;

    private final JsonMapper jsonMapper;

    public ConsultationSummaryService(
            OpenAiStructuredClient openAiStructuredClient,
            OpenAiProperties openAiProperties,
            ConsultationSummaryBusinessValidator businessValidator,
            ConsultationSummaryPersistenceService persistenceService,
            JsonMapper jsonMapper
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
    }

    // Ready 또는 Not prepared 상태를 반환
    public ConsultationSummaryStateResponse getState(
            UUID consultationId,
            String rawToken
    ) {
        return persistenceService.getState(
                consultationId,
                rawToken
        );
    }

    public ConsultationSummaryStateResponse prepare(
            UUID consultationId,
            String rawToken
    ) {

        ConsultationSummaryData.Snapshot snapshot =
                persistenceService.loadSnapshot(
                        consultationId,
                        rawToken
                );

        Optional<ConsultationSummaryData.Document>
                existing =
                persistenceService.findExisting(
                        snapshot
                );

        if (existing.isPresent()) {
            return ConsultationSummaryStateResponse.ready(
                    existing.get()
            );
        }

        ConsultationSummaryAiResult result;

        try {
            result =
                    openAiStructuredClient
                            .generateStructured(
                                    // LLM 규칙
                                    INSTRUCTIONS,
                                    // 직렬화한 실제로 요약해야 할 데이터
                                    buildModelInput(snapshot),
                                    // LLM 결과의 구조를 지정하는 타입
                                    ConsultationSummaryAiResult.class
                            );

            result =
                    businessValidator
                            .validate(result);

        } catch (
                AiProviderException
                | AiOutputContractException
                        exception
        ) {
            throw new AiGenerationFailedException();
        }

        ConsultationSummaryData.Document saved =
                persistenceService.saveIfCurrent(
                        snapshot,
                        result,
                        openAiProperties.model()
                );

        return ConsultationSummaryStateResponse.ready(
                saved
        );
    }

    public ConfirmConsultationSummaryResponse confirm(
            UUID consultationId,
            String rawToken
    ) {
        return persistenceService.confirm(
                consultationId,
                rawToken
        );
    }

    private String buildModelInput(
            ConsultationSummaryData.Snapshot snapshot
    ) {

        try {
            SummaryPromptInput promptInput =
                    new SummaryPromptInput(
                            snapshot.category().name(),
                            snapshot.situationText(),
                            snapshot.followUpAnswers()
                    );

            return jsonMapper.writeValueAsString(
                    promptInput
            );

        } catch (JacksonException exception) {
            throw new IllegalStateException(
                    "Failed to serialize summary prompt input"
            );
        }
    }

    private record SummaryPromptInput(
            String consultationCategory,
            String userSituation,
            java.util.List<ConsultationSummaryData.FollowUpAnswer> followUpAnswers
    ) {
    }
}