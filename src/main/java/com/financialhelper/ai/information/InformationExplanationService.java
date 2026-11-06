package com.financialhelper.ai.information;

import com.financialhelper.ai.AiOutputContractException;
import com.financialhelper.ai.OpenAiStructuredClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

/** An extractive AI boundary: the model may select evidence, never author report claims. */
@Service
@ConditionalOnProperty(prefix = "app.ai", name = "enabled", havingValue = "true")
public class InformationExplanationService {
    private static final String INSTRUCTIONS = """
            제공된 공식 문서 후보만 선택한다. 선택한 supportChunkId와 원문 문장을 정확히 복사한다.
            해당 근거에 연결된 conditionChunkIds와 exceptionChunkIds를 빠짐없이 그대로 복사한다.
            새로운 문장, 조언, 사실, 기한, 금액, 연락처, 출처, 법률 판단을 작성하지 않는다.
            후보의 개별 사건 적용성이나 보상 결과를 판단하지 않는다.
            """;

    private final OpenAiStructuredClient client;
    private final JsonMapper mapper;
    private final InformationExplanationValidator validator;

    public InformationExplanationService(OpenAiStructuredClient client, JsonMapper mapper,
            InformationExplanationValidator validator) {
        this.client = client;
        this.mapper = mapper;
        this.validator = validator;
    }

    public List<InformationEvidenceSnapshot.Evidence> select(InformationEvidenceSnapshot snapshot) {
        if (snapshot == null || snapshot.evidence().isEmpty()) return List.of();
        if (!"CURRENT_READY_SOURCE_AND_HASH".equals(snapshot.sourceIntegrityStatus())
                || !"V38_SCOPE_REVIEWED".equals(snapshot.humanScopeReviewStatus())) {
            throw new AiOutputContractException("information evidence is not reviewed");
        }
        try {
            // Extractive selection needs no personal case facts in the provider payload.
            String input = mapper.writeValueAsString(new Input(snapshot.evidence()));
            return validator.validate(client.generateStructured(INSTRUCTIONS, input,
                    InformationExplanationAiResult.class), snapshot);
        } catch (JacksonException exception) {
            throw new AiOutputContractException("information evidence input is invalid");
        }
    }

    private record Input(List<InformationEvidenceSnapshot.Evidence> evidence) {}
}
