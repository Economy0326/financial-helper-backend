package com.financialhelper.ai.followup;

import com.financialhelper.ai.OpenAiStructuredClient;

import com.financialhelper.ai.understanding.CaseUnderstanding;
import com.financialhelper.ai.understanding.CaseUnderstandingAiResult;
import com.financialhelper.ai.understanding.CaseUnderstandingRepository;

import com.financialhelper.consultation.Consultation;
import com.financialhelper.consultation.ConsultationCategory;
import com.financialhelper.consultation.ConsultationRepository;
import com.financialhelper.consultation.ConsultationStep;

import com.financialhelper.guest.GuestSession;
import com.financialhelper.guest.GuestSessionCookie;
import com.financialhelper.guest.GuestSessionRepository;
import com.financialhelper.guest.GuestSessionTokenService;

import jakarta.servlet.http.Cookie;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;

import org.springframework.boot.test.context.SpringBootTest;

import org.springframework.boot.webmvc.test.autoconfigure
        .AutoConfigureMockMvc;

import org.springframework.test.context.ActiveProfiles;

import org.springframework.test.context.bean.override.mockito
        .MockitoBean;

import org.springframework.test.web.servlet.MockMvc;

import tools.jackson.databind.json.JsonMapper;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;

import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import static org.springframework.security.test.web.servlet
        .request.SecurityMockMvcRequestPostProcessors.csrf;

import static org.springframework.test.web.servlet
        .request.MockMvcRequestBuilders.get;

import static org.springframework.test.web.servlet
        .request.MockMvcRequestBuilders.post;

import static org.springframework.test.web.servlet
        .request.MockMvcRequestBuilders.put;

import static org.springframework.test.web.servlet
        .result.MockMvcResultMatchers.jsonPath;

import static org.springframework.test.web.servlet
        .result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class FollowUpApiIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private GuestSessionRepository
            guestSessionRepository;

    @Autowired
    private ConsultationRepository
            consultationRepository;

    @Autowired
    private CaseUnderstandingRepository
            caseUnderstandingRepository;

    @Autowired
    private FollowUpQuestionRepository
            followUpQuestionRepository;

    @Autowired
    private GuestSessionTokenService
            tokenService;

    @Autowired
    private JsonMapper jsonMapper;

    @MockitoBean
    private OpenAiStructuredClient
            openAiStructuredClient;

    @BeforeEach
    void beforeEach() {
        cleanDatabase();
    }

    @AfterEach
    void afterEach() {
        cleanDatabase();
    }

    // Follow-up 질문 준비부터 답변 저장, SUMMARY 전환까지 전체 정상 Flow 검증
    @Test
    void preparesQuestionsPersistsAnswersAndMovesToSummary()
            throws Exception {

        TestGuest guest =
                createGuest();

        Consultation consultation =
                createReadyConsultation(
                        guest.session()
                );

        saveUnderstanding(
                consultation
        );

        when(
                openAiStructuredClient
                        .generateStructured(
                                anyString(),
                                anyString(),
                                eq(
                                        FollowUpQuestionAiResult.class
                                )
                        )
        )
                .thenReturn(
                        createFollowUpResult()
                );

        /*
         * 질문 준비
         */
        mockMvc.perform(
                        post(
                                "/api/v1/consultations/{id}/follow-up/prepare",
                                consultation.getId()
                        )
                                .cookie(
                                        guest.cookie()
                                )
                                .with(csrf())
                )
                .andExpect(
                        status().isOk()
                )
                .andExpect(
                        jsonPath("$.kind")
                                .value("question")
                )
                .andExpect(
                        jsonPath("$.currentQuestionNumber")
                                .value(1)
                )
                .andExpect(
                        jsonPath("$.totalQuestions")
                                .value(2)
                );

        /*
         * 같은 Revision prepare 재호출:
         * LLM 재호출 없음.
         */
        mockMvc.perform(
                        post(
                                "/api/v1/consultations/{id}/follow-up/prepare",
                                consultation.getId()
                        )
                                .cookie(
                                        guest.cookie()
                                )
                                .with(csrf())
                )
                .andExpect(
                        status().isOk()
                );

        verify(
                openAiStructuredClient,
                times(1)
        ).generateStructured(
                anyString(),
                anyString(),
                eq(
                        FollowUpQuestionAiResult.class
                )
        );

        List<FollowUpQuestion> questions =
                followUpQuestionRepository
                        .findByConsultation_IdAndCaseInputRevisionOrderBySequenceNoAsc(
                                consultation.getId(),
                                consultation
                                        .getCaseInputRevision()
                        );

        assertThat(questions)
                .hasSize(2);

        FollowUpQuestion first =
                questions.get(0);

        FollowUpQuestion second =
                questions.get(1);

        /*
         * 첫 답변
         */
        mockMvc.perform(
                        put(
                                "/api/v1/consultations/{id}/follow-up/questions/{questionId}/answer",
                                consultation.getId(),
                                first.getId()
                        )
                                .cookie(
                                        guest.cookie()
                                )
                                .with(csrf())
                                .contentType(
                                        "application/json"
                                )
                                .content(
                                        """
                                        {
                                          "answer": "YES"
                                        }
                                        """
                                )
                )
                .andExpect(
                        status().isOk()
                )
                .andExpect(
                        jsonPath("$.kind")
                                .value("question")
                )
                .andExpect(
                        jsonPath("$.currentQuestionNumber")
                                .value(2)
                );

        /*
         * 이전 질문 GET도 가능.
         */
        mockMvc.perform(
                        get(
                                "/api/v1/consultations/{id}/follow-up",
                                consultation.getId()
                        )
                                .queryParam(
                                        "questionNumber",
                                        "1"
                                )
                                .cookie(
                                        guest.cookie()
                                )
                )
                .andExpect(
                        status().isOk()
                )
                .andExpect(
                        jsonPath("$.savedAnswer")
                                .value("YES")
                );

        /*
         * 마지막 답변
         */
        mockMvc.perform(
                        put(
                                "/api/v1/consultations/{id}/follow-up/questions/{questionId}/answer",
                                consultation.getId(),
                                second.getId()
                        )
                                .cookie(
                                        guest.cookie()
                                )
                                .with(csrf())
                                .contentType(
                                        "application/json"
                                )
                                .content(
                                        """
                                        {
                                          "answer": "NO"
                                        }
                                        """
                                )
                )
                .andExpect(
                        status().isOk()
                )
                .andExpect(
                        jsonPath("$.kind")
                                .value("complete")
                );

        Consultation completed =
                consultationRepository
                        .findById(
                                consultation.getId()
                        )
                        .orElseThrow();

        assertThat(
                completed.getCurrentStep()
        )
                .isEqualTo(
                        ConsultationStep.SUMMARY
                );

        assertThat(
                completed
                        .getFollowUpAnswerRevision()
        )
                .isEqualTo(2L);
    }

    private void cleanDatabase() {

        followUpQuestionRepository
                .deleteAll();

        caseUnderstandingRepository
                .deleteAll();

        consultationRepository
                .deleteAll();

        guestSessionRepository
                .deleteAll();
    }

    private TestGuest createGuest() {

        String rawToken =
                tokenService.generateRawToken();

        OffsetDateTime now =
                OffsetDateTime.now(
                        ZoneOffset.UTC
                );

        GuestSession guestSession =
                guestSessionRepository
                        .save(
                                new GuestSession(
                                        tokenService
                                                .hashToken(
                                                        rawToken
                                                ),
                                        now,
                                        now.plusHours(1)
                                )
                        );

        return new TestGuest(
                guestSession,

                new Cookie(
                        GuestSessionCookie.NAME,
                        rawToken
                )
        );
    }

    private Consultation createReadyConsultation(
            GuestSession guestSession
    ) {

        OffsetDateTime now =
                OffsetDateTime.now(
                        ZoneOffset.UTC
                );

        Consultation consultation =
                new Consultation(
                        guestSession,
                        now
                );

        consultation.updateCategory(
                ConsultationCategory.INSURANCE,
                now.plusSeconds(1)
        );

        consultation.updateSituation(
                "보험을 해지했는데 예상보다 해지환급금이 적어요.",
                now.plusSeconds(2)
        );

        return consultationRepository
                .saveAndFlush(
                        consultation
                );
    }

    private void saveUnderstanding(
            Consultation consultation
    ) throws Exception {

        CaseUnderstandingAiResult result =
                new CaseUnderstandingAiResult();

        CaseUnderstandingAiResult.ExtractedFact fact =
                new CaseUnderstandingAiResult
                        .ExtractedFact();

        fact.type =
                CaseUnderstandingAiResult
                        .FactType.EVENT;

        fact.label =
                "현재 상황";

        fact.value =
                "보험 해지 후 환급금이 예상보다 적음";

        CaseUnderstandingAiResult.MissingInformation missing =
                new CaseUnderstandingAiResult
                        .MissingInformation();

        missing.type =
                CaseUnderstandingAiResult
                        .MissingInformationType.DOCUMENT;

        missing.topic =
                "해지환급금 산출내역";

        missing.reason =
                "환급금 산정 내용을 이해하는 데 도움이 됩니다.";

        missing.priority =
                CaseUnderstandingAiResult
                        .MissingInformationPriority.IMPORTANT;

        result.facts =
                List.of(fact);

        result.missingInformation =
                List.of(missing);

        String resultJson =
                jsonMapper
                        .writeValueAsString(
                                result
                        );

        caseUnderstandingRepository
                .saveAndFlush(
                        new CaseUnderstanding(
                                consultation,
                                consultation
                                        .getCaseInputRevision(),
                                "gpt-5.6-luna",
                                resultJson,
                                OffsetDateTime.now(
                                        ZoneOffset.UTC
                                )
                        )
                );
    }

    private FollowUpQuestionAiResult
    createFollowUpResult() {

        FollowUpQuestionAiResult.Question first =
                question(
                        "보험 계약을 해지하셨나요?",
                        "현재 계약 상태와 가까운 항목을 선택해 주세요."
                );

        FollowUpQuestionAiResult.Question second =
                question(
                        "해지환급금 산출내역을 확인하셨나요?",
                        "현재 상황과 가까운 항목을 선택해 주세요."
                );

        FollowUpQuestionAiResult result =
                new FollowUpQuestionAiResult();

        result.questions =
                List.of(
                        first,
                        second
                );

        return result;
    }

    private FollowUpQuestionAiResult.Question
    question(
            String question,
            String description
    ) {

        FollowUpQuestionAiResult.Option yes =
                option(
                        "YES",
                        "네",
                        "해당돼요."
                );

        FollowUpQuestionAiResult.Option no =
                option(
                        "NO",
                        "아니요",
                        "해당되지 않아요."
                );

        FollowUpQuestionAiResult.Option unknown =
                option(
                        "UNKNOWN",
                        "잘 모르겠어요",
                        "정확히 알기 어려워요."
                );

        FollowUpQuestionAiResult.Question result =
                new FollowUpQuestionAiResult
                        .Question();

        result.question =
                question;

        result.description =
                description;

        result.options =
                List.of(
                        yes,
                        no,
                        unknown
                );

        return result;
    }

    private FollowUpQuestionAiResult.Option
    option(
            String value,
            String label,
            String description
    ) {

        FollowUpQuestionAiResult.Option option =
                new FollowUpQuestionAiResult
                        .Option();

        option.value =
                value;

        option.label =
                label;

        option.description =
                description;

        return option;
    }

    private record TestGuest(
            GuestSession session,
            Cookie cookie
    ) {
    }
}