package com.financialhelper.ai.summary;

import com.financialhelper.ai.OpenAiStructuredClient;

import com.financialhelper.ai.followup.FollowUpQuestion;
import com.financialhelper.ai.followup.FollowUpQuestionRepository;

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
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ConsultationSummaryApiIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private GuestSessionRepository guestSessionRepository;

    @Autowired
    private ConsultationRepository consultationRepository;

    @Autowired
    private FollowUpQuestionRepository followUpQuestionRepository;

    @Autowired
    private ConsultationSummaryRepository consultationSummaryRepository;

    @Autowired
    private GuestSessionTokenService tokenService;

    @MockitoBean
    private OpenAiStructuredClient openAiStructuredClient;

    @BeforeEach
    void beforeEach() {
        cleanDatabase();
    }

    @AfterEach
    void afterEach() {
        cleanDatabase();
    }

    @Test
    void preparesSummaryReusesItAndConfirmsToAnalysis()
            throws Exception {

        TestGuest guest =
                createGuest();

        Consultation consultation =
                createSummaryReadyConsultation(
                        guest.session()
                );

        when(
                openAiStructuredClient
                        .generateStructured(
                                anyString(),
                                anyString(),
                                eq(
                                        ConsultationSummaryAiResult.class
                                )
                        )
        )
                .thenReturn(
                        createSummaryResult()
                );

        mockMvc.perform(
                        post(
                                "/api/v1/consultations/{id}/summary/prepare",
                                consultation.getId()
                        )
                                .cookie(guest.cookie())
                                .with(csrf())
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kind").value("ready"))
                .andExpect(jsonPath("$.summary.headline").value("보험 해지환급금 관련 상담"))
                .andExpect(jsonPath("$.summary.keyPoints.length()").value(3));

        mockMvc.perform(
                        post(
                                "/api/v1/consultations/{id}/summary/prepare",
                                consultation.getId()
                        )
                                .cookie(guest.cookie())
                                .with(csrf())
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kind").value("ready"));

        verify(
                openAiStructuredClient,
                times(1)
        ).generateStructured(
                anyString(),
                anyString(),
                eq(
                        ConsultationSummaryAiResult.class
                )
        );

        assertThat(
                consultationSummaryRepository.count()
        ).isEqualTo(1L);

        mockMvc.perform(
                        get(
                                "/api/v1/consultations/{id}/summary",
                                consultation.getId()
                        )
                                .cookie(guest.cookie())
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kind").value("ready"));

        mockMvc.perform(
                        post(
                                "/api/v1/consultations/{id}/summary/confirm",
                                consultation.getId()
                        )
                                .cookie(guest.cookie())
                                .with(csrf())
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nextStep").value("ANALYSIS"));

        Consultation updated =
                consultationRepository
                        .findById(consultation.getId())
                        .orElseThrow();

        assertThat(
                updated.getCurrentStep()
        ).isEqualTo(
                ConsultationStep.ANALYSIS
        );

        ConsultationSummary savedSummary =
                consultationSummaryRepository
                        .findByConsultation_IdAndCaseInputRevisionAndFollowUpAnswerRevision(
                                updated.getId(),
                                updated.getCaseInputRevision(),
                                updated.getFollowUpAnswerRevision()
                        )
                        .orElseThrow();

        assertThat(
                savedSummary.getConfirmedAt()
        ).isNotNull();
    }

    private void cleanDatabase() {
        consultationSummaryRepository.deleteAll();
        followUpQuestionRepository.deleteAll();
        consultationRepository.deleteAll();
        guestSessionRepository.deleteAll();
    }

    private TestGuest createGuest() {

        String rawToken =
                tokenService.generateRawToken();

        OffsetDateTime now =
                OffsetDateTime.now(ZoneOffset.UTC);

        GuestSession guestSession =
                guestSessionRepository.save(
                        new GuestSession(
                                tokenService.hashToken(rawToken),
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

    private Consultation createSummaryReadyConsultation(
            GuestSession guestSession
    ) {

        OffsetDateTime now =
                OffsetDateTime.now(ZoneOffset.UTC);

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
                "보험을 해지했는데 예상보다 해지환급금이 적게 지급됐어요.",
                now.plusSeconds(2)
        );

        consultation.recordFollowUpAnswerChanged(
                now.plusSeconds(3)
        );
        consultation.recordFollowUpAnswerChanged(
                now.plusSeconds(4)
        );
        consultation.moveToSummary(
                now.plusSeconds(5)
        );

        Consultation saved =
                consultationRepository.saveAndFlush(
                        consultation
                );

        FollowUpQuestion question1 =
                new FollowUpQuestion(
                        saved,
                        saved.getCaseInputRevision(),
                        1,
                        "보험 계약을 해지하셨나요?",
                        "현재 계약 상태와 가까운 항목을 선택해 주세요.",
                        """
                        {"options":[
                          {"value":"YES","label":"네","description":"해지했어요."},
                          {"value":"NO","label":"아니요","description":"아직 해지하지 않았어요."},
                          {"value":"UNKNOWN","label":"잘 모르겠어요","description":"정확히 모르겠어요."}
                        ]}
                        """,
                        "gpt-5.6-luna",
                        now.plusSeconds(3)
                );

        question1.answer(
                "YES",
                "네",
                now.plusSeconds(3)
        );

        FollowUpQuestion question2 =
                new FollowUpQuestion(
                        saved,
                        saved.getCaseInputRevision(),
                        2,
                        "해지환급금 산출내역을 확인하셨나요?",
                        "현재 상황과 가까운 항목을 선택해 주세요.",
                        """
                        {"options":[
                          {"value":"YES","label":"네","description":"확인했어요."},
                          {"value":"NO","label":"아니요","description":"아직 확인하지 않았어요."},
                          {"value":"UNKNOWN","label":"잘 모르겠어요","description":"정확히 모르겠어요."}
                        ]}
                        """,
                        "gpt-5.6-luna",
                        now.plusSeconds(4)
                );

        question2.answer(
                "NO",
                "아니요",
                now.plusSeconds(4)
        );

        followUpQuestionRepository.saveAllAndFlush(
                List.of(question1, question2)
        );

        return saved;
    }

    private ConsultationSummaryAiResult createSummaryResult() {

        ConsultationSummaryAiResult result =
                new ConsultationSummaryAiResult();

        result.headline =
                "보험 해지환급금 관련 상담";

        result.summaryText =
                "사용자는 보험을 해지한 뒤 예상보다 적은 해지환급금을 받았다고 설명했습니다. 현재 해지 여부는 확인되었고, 해지환급금 산출내역은 아직 확인하지 않은 상태입니다.";

        ConsultationSummaryAiResult.KeyPoint point1 =
                new ConsultationSummaryAiResult.KeyPoint();
        point1.text = "보험 해지 후 환급금이 예상보다 적다고 느끼고 있습니다.";

        ConsultationSummaryAiResult.KeyPoint point2 =
                new ConsultationSummaryAiResult.KeyPoint();
        point2.text = "사용자는 보험 계약을 이미 해지했다고 답변했습니다.";

        ConsultationSummaryAiResult.KeyPoint point3 =
                new ConsultationSummaryAiResult.KeyPoint();
        point3.text = "해지환급금 산출내역은 아직 확인하지 않았습니다.";

        result.keyPoints =
                List.of(point1, point2, point3);

        return result;
    }

    private record TestGuest(
            GuestSession session,
            Cookie cookie
    ) {
    }
}