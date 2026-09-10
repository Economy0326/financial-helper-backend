package com.financialhelper.ai.understanding;

import com.financialhelper.ai.AiProviderException;
import com.financialhelper.ai.OpenAiStructuredClient;

import com.financialhelper.consultation.Consultation;
import com.financialhelper.consultation.ConsultationCategory;
import com.financialhelper.consultation.ConsultationRepository;

import com.financialhelper.guest.GuestSession;
import com.financialhelper.guest.GuestSessionCookie;
import com.financialhelper.guest.GuestSessionRepository;
import com.financialhelper.guest.GuestSessionTokenService;

import jakarta.servlet.http.Cookie;

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
        .request.MockMvcRequestBuilders.post;

import static org.springframework.test.web.servlet
        .result.MockMvcResultMatchers.jsonPath;

import static org.springframework.test.web.servlet
        .result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CaseUnderstandingApiIntegrationTest {

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
    private GuestSessionTokenService
            tokenService;

    @MockitoBean
    private OpenAiStructuredClient
            openAiStructuredClient;

    @BeforeEach
    void cleanDatabase() {

        caseUnderstandingRepository
                .deleteAll();

        consultationRepository
                .deleteAll();

        guestSessionRepository
                .deleteAll();
    }

    // 같은 Revision이면 AI 결과를 재사용하고 OpenAI를 중복 호출하지 않는지 검증
    @Test
    void generatesUnderstandingAndReusesSameRevision()
            throws Exception {

        TestGuest guest =
                createGuest();

        Consultation consultation =
                createReadyConsultation(
                        guest.session()
                );

        CaseUnderstandingAiResult aiResult =
                createAiResult();

        when(
                openAiStructuredClient
                        .generateStructured(
                                anyString(),
                                anyString(),
                                eq(
                                        CaseUnderstandingAiResult.class
                                )
                        )
        ).thenReturn(aiResult);

        /*
         * 첫 호출:
         * 실제 생성 + DB 저장
         */
        mockMvc.perform(
                        post(
                                "/api/v1/consultations/{id}/understanding",
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
                        jsonPath("$.factCount")
                                .value(1)
                )
                .andExpect(
                        jsonPath(
                                "$.missingInformationCount"
                        )
                                .value(1)
                );

        /*
         * 같은 Revision으로 다시 호출:
         * OpenAI를 다시 호출하지 않고 DB 결과 재사용
         */
        mockMvc.perform(
                        post(
                                "/api/v1/consultations/{id}/understanding",
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
                        jsonPath("$.factCount")
                                .value(1)
                );

        assertThat(
                caseUnderstandingRepository.count()
        ).isEqualTo(1);

        verify(
                openAiStructuredClient,
                times(1)
        ).generateStructured(
                anyString(),
                anyString(),
                eq(
                        CaseUnderstandingAiResult.class
                )
        );
    }

    // AI 처리 중 입력이 변경되면 이전 Revision 결과 저장을 거부하는지 검증
    @Test
    void rejectsStaleAiResultWhenSituationChangesDuringGeneration()
            throws Exception {

        TestGuest guest =
                createGuest();

        Consultation consultation =
                createReadyConsultation(
                        guest.session()
                );

        CaseUnderstandingAiResult aiResult =
                createAiResult();

        /*
         * AI 호출 중 사용자가 Situation을 수정한 상황을 재현한다.
         */
        when(
                openAiStructuredClient
                        .generateStructured(
                                anyString(),
                                anyString(),
                                eq(
                                        CaseUnderstandingAiResult.class
                                )
                        )
        ).thenAnswer(invocation -> {

            Consultation latest =
                    consultationRepository
                            .findById(
                                    consultation.getId()
                            )
                            .orElseThrow();

            latest.updateSituation(
                    "사용자가 AI 처리 중 상황 내용을 수정했습니다.",
                    OffsetDateTime.now(
                            ZoneOffset.UTC
                    )
            );

            consultationRepository
                    .saveAndFlush(latest);

            return aiResult;
        });

        mockMvc.perform(
                        post(
                                "/api/v1/consultations/{id}/understanding",
                                consultation.getId()
                        )
                                .cookie(
                                        guest.cookie()
                                )
                                .with(csrf())
                )
                .andExpect(
                        status().isConflict()
                )
                .andExpect(
                        jsonPath("$.error.code")
                                .value(
                                        "AI_INPUT_CHANGED"
                                )
                );

        assertThat(
                caseUnderstandingRepository.count()
        ).isZero();
    }

    // OpenAI Provider 호출 실패 시 502 AI_GENERATION_FAILED를 반환하는지 검증
    @Test
    void returnsGatewayErrorWhenAiProviderFails()
            throws Exception {

        TestGuest guest =
                createGuest();

        Consultation consultation =
                createReadyConsultation(
                        guest.session()
                );

        when(
                openAiStructuredClient
                        .generateStructured(
                                anyString(),
                                anyString(),
                                eq(
                                        CaseUnderstandingAiResult.class
                                )
                        )
        ).thenThrow(
                new AiProviderException(
                        "provider failure"
                )
        );

        mockMvc.perform(
                        post(
                                "/api/v1/consultations/{id}/understanding",
                                consultation.getId()
                        )
                                .cookie(
                                        guest.cookie()
                                )
                                .with(csrf())
                )
                .andExpect(
                        status().isBadGateway()
                )
                .andExpect(
                        jsonPath("$.error.code")
                                .value(
                                        "AI_GENERATION_FAILED"
                                )
                );
    }

    private TestGuest createGuest() {

        String rawToken =
                tokenService.generateRawToken();

        OffsetDateTime now =
                OffsetDateTime.now(
                        ZoneOffset.UTC
                );

        GuestSession guestSession =
                guestSessionRepository.save(
                        new GuestSession(
                                tokenService.hashToken(
                                        rawToken
                                ),
                                now,
                                now.plusHours(1)
                        )
                );

        Cookie cookie =
                new Cookie(
                        GuestSessionCookie.NAME,
                        rawToken
                );

        return new TestGuest(
                guestSession,
                cookie
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
                "보험을 해지했는데 예상보다 해지환급금이 적게 지급됐어요.",
                now.plusSeconds(2)
        );

        return consultationRepository
                .saveAndFlush(
                        consultation
                );
    }

    private CaseUnderstandingAiResult
    createAiResult() {

        CaseUnderstandingAiResult.ExtractedFact fact =
                new CaseUnderstandingAiResult
                        .ExtractedFact();

        fact.type =
                CaseUnderstandingAiResult
                        .FactType.PRODUCT;

        fact.label =
                "문제 유형";

        fact.value =
                "보험 해지환급금 문제";

        CaseUnderstandingAiResult
                .MissingInformation missing =
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

        CaseUnderstandingAiResult result =
                new CaseUnderstandingAiResult();

        result.facts =
                List.of(fact);

        result.missingInformation =
                List.of(missing);

        return result;
    }

    private record TestGuest(
            GuestSession session,
            Cookie cookie
    ) {
    }
}