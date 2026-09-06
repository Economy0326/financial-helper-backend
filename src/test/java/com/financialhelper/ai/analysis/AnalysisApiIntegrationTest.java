package com.financialhelper.ai.analysis;

import com.financialhelper.ai.AiProviderException;
import com.financialhelper.ai.OpenAiStructuredClient;

import com.financialhelper.ai.summary
        .ConsultationSummary;
import com.financialhelper.ai.summary
        .ConsultationSummaryAiResult;
import com.financialhelper.ai.summary
        .ConsultationSummaryRepository;

import com.financialhelper.consultation.Consultation;
import com.financialhelper.consultation.ConsultationCategory;
import com.financialhelper.consultation.ConsultationRepository;
import com.financialhelper.consultation.ConsultationStatus;
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
        .result.MockMvcResultMatchers.jsonPath;

import static org.springframework.test.web.servlet
        .result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AnalysisApiIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private GuestSessionRepository
            guestSessionRepository;

    @Autowired
    private ConsultationRepository
            consultationRepository;

    @Autowired
    private ConsultationSummaryRepository
            summaryRepository;

    @Autowired
    private AnalysisJobRepository
            analysisJobRepository;

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

    // Analysis 정상 완료 및 같은 Revision의 중복 AI 호출 방지 검증
    @Test
    void startsAnalysisAndCompletesWithoutDuplicateGeneration()
            throws Exception {

        TestContext context =
                createAnalysisReadyConsultation();

        when(
                openAiStructuredClient
                        .generateStructured(
                                anyString(),
                                anyString(),
                                eq(
                                        AnalysisAiResult.class
                                )
                        )
        ).thenReturn(
                readyForReport()
        );

        mockMvc.perform(
                        post(
                                "/api/v1/consultations/{id}/analysis/start",
                                context
                                        .consultation()
                                        .getId()
                        )
                                .cookie(
                                        context.cookie()
                                )
                                .with(csrf())
                )
                .andExpect(
                        status().isOk()
                );

        waitUntilStatus(
                context
                        .consultation()
                        .getId(),
                AnalysisJobStatus.COMPLETED
        );

        mockMvc.perform(
                        get(
                                "/api/v1/consultations/{id}/analysis",
                                context
                                        .consultation()
                                        .getId()
                        )
                                .cookie(
                                        context.cookie()
                                )
                )
                .andExpect(
                        status().isOk()
                )
                .andExpect(
                        jsonPath("$.status")
                                .value(
                                        "COMPLETED"
                                )
                );

        /*
         * start를 다시 호출해도
         * 같은 Revision Job 재사용.
         */
        mockMvc.perform(
                        post(
                                "/api/v1/consultations/{id}/analysis/start",
                                context
                                        .consultation()
                                        .getId()
                        )
                                .cookie(
                                        context.cookie()
                                )
                                .with(csrf())
                )
                .andExpect(
                        status().isOk()
                )
                .andExpect(
                        jsonPath("$.status")
                                .value(
                                        "COMPLETED"
                                )
                );

        verify(
                openAiStructuredClient,
                times(1)
        ).generateStructured(
                anyString(),
                anyString(),
                eq(
                        AnalysisAiResult.class
                )
        );

        assertThat(
                analysisJobRepository.count()
        ).isEqualTo(1L);
    }

    // Analysis 실패 후 Retry로 재분석이 정상 완료되는지 검증
    @Test
    void retriesFailedAnalysis()
            throws Exception {

        TestContext context =
                createAnalysisReadyConsultation();

        when(
                openAiStructuredClient
                        .generateStructured(
                                anyString(),
                                anyString(),
                                eq(
                                        AnalysisAiResult.class
                                )
                        )
        )
                .thenThrow(
                        new AiProviderException(
                                "provider failure"
                        )
                )
                .thenReturn(
                        readyForReport()
                );

        mockMvc.perform(
                        post(
                                "/api/v1/consultations/{id}/analysis/start",
                                context
                                        .consultation()
                                        .getId()
                        )
                                .cookie(
                                        context.cookie()
                                )
                                .with(csrf())
                )
                .andExpect(
                        status().isOk()
                );

        waitUntilStatus(
                context
                        .consultation()
                        .getId(),
                AnalysisJobStatus.FAILED
        );

        mockMvc.perform(
                        get(
                                "/api/v1/consultations/{id}/analysis",
                                context
                                        .consultation()
                                        .getId()
                        )
                                .cookie(
                                        context.cookie()
                                )
                )
                .andExpect(
                        jsonPath("$.status")
                                .value(
                                        "FAILED"
                                )
                );

        mockMvc.perform(
                        post(
                                "/api/v1/consultations/{id}/analysis/retry",
                                context
                                        .consultation()
                                        .getId()
                        )
                                .cookie(
                                        context.cookie()
                                )
                                .with(csrf())
                )
                .andExpect(
                        status().isOk()
                );

        waitUntilStatus(
                context
                        .consultation()
                        .getId(),
                AnalysisJobStatus.COMPLETED
        );

        AnalysisJob job =
                currentJob(
                        context
                                .consultation()
                );

        assertThat(
                job.getAttemptCount()
        ).isEqualTo(2);

        verify(
                openAiStructuredClient,
                times(2)
        ).generateStructured(
                anyString(),
                anyString(),
                eq(
                        AnalysisAiResult.class
                )
        );
    }

    // 추가 정보 필요 시 Consultation이 Situation 단계로 다시 열리는 지 검증
    @Test
    void needsMoreInfoCanReturnToSituation()
            throws Exception {

        TestContext context =
                createAnalysisReadyConsultation();

        when(
                openAiStructuredClient
                        .generateStructured(
                                anyString(),
                                anyString(),
                                eq(
                                        AnalysisAiResult.class
                                )
                        )
        ).thenReturn(
                needsMoreInfo()
        );

        mockMvc.perform(
                        post(
                                "/api/v1/consultations/{id}/analysis/start",
                                context
                                        .consultation()
                                        .getId()
                        )
                                .cookie(
                                        context.cookie()
                                )
                                .with(csrf())
                )
                .andExpect(
                        status().isOk()
                );

        waitUntilStatus(
                context
                        .consultation()
                        .getId(),
                AnalysisJobStatus.NEEDS_MORE_INFO
        );

        mockMvc.perform(
                        get(
                                "/api/v1/consultations/{id}/analysis",
                                context
                                        .consultation()
                                        .getId()
                        )
                                .cookie(
                                        context.cookie()
                                )
                )
                .andExpect(
                        status().isOk()
                )
                .andExpect(
                        jsonPath("$.status")
                                .value(
                                        "NEEDS_MORE_INFO"
                                )
                )
                .andExpect(
                        jsonPath(
                                "$.additionalInformationNeeded.length()"
                        )
                                .value(1)
                );

        mockMvc.perform(
                        post(
                                "/api/v1/consultations/{id}/analysis/reopen",
                                context
                                        .consultation()
                                        .getId()
                        )
                                .cookie(
                                        context.cookie()
                                )
                                .with(csrf())
                )
                .andExpect(
                        status().isOk()
                )
                .andExpect(
                        jsonPath("$.nextStep")
                                .value(
                                        "SITUATION"
                                )
                );

        Consultation reopened =
                consultationRepository
                        .findById(
                                context
                                        .consultation()
                                        .getId()
                        )
                        .orElseThrow();

        assertThat(
                reopened.getStatus()
        ).isEqualTo(
                ConsultationStatus.IN_PROGRESS
        );

        assertThat(
                reopened.getCurrentStep()
        ).isEqualTo(
                ConsultationStep.SITUATION
        );
    }

    private void waitUntilStatus(
            java.util.UUID consultationId,
            AnalysisJobStatus expected
    ) throws InterruptedException {

        long deadline =
                System.currentTimeMillis()
                        + 5_000L;

        while (
                System.currentTimeMillis()
                        < deadline
        ) {

            Consultation consultation =
                    consultationRepository
                            .findById(
                                    consultationId
                            )
                            .orElseThrow();

            AnalysisJob job =
                    analysisJobRepository
                            .findByConsultation_IdAndCaseInputRevisionAndFollowUpAnswerRevision(
                                    consultation.getId(),
                                    consultation
                                            .getCaseInputRevision(),
                                    consultation
                                            .getFollowUpAnswerRevision()
                            )
                            .orElse(null);

            if (
                    job != null
                    && job.getStatus()
                    == expected
            ) {
                return;
            }

            Thread.sleep(50L);
        }

        throw new AssertionError(
                "Analysis job did not reach status "
                        + expected
        );
    }

    private AnalysisJob currentJob(
            Consultation consultation
    ) {

        Consultation latest =
                consultationRepository
                        .findById(
                                consultation.getId()
                        )
                        .orElseThrow();

        return analysisJobRepository
                .findByConsultation_IdAndCaseInputRevisionAndFollowUpAnswerRevision(
                        latest.getId(),
                        latest.getCaseInputRevision(),
                        latest.getFollowUpAnswerRevision()
                )
                .orElseThrow();
    }

    private TestContext
    createAnalysisReadyConsultation()
            throws Exception {

        String rawToken =
                tokenService.generateRawToken();

        OffsetDateTime now =
                OffsetDateTime.now(
                        ZoneOffset.UTC
                );

        GuestSession guestSession =
                guestSessionRepository.save(
                        new GuestSession(
                                tokenService
                                        .hashToken(
                                                rawToken
                                        ),
                                now,
                                now.plusHours(1)
                        )
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
                "보험 해지 후 해지환급금이 예상보다 적었습니다.",
                now.plusSeconds(2)
        );

        consultation.recordFollowUpAnswerChanged(
                now.plusSeconds(3)
        );

        consultation.moveToSummary(
                now.plusSeconds(4)
        );

        consultation =
                consultationRepository
                        .saveAndFlush(
                                consultation
                        );

        ConsultationSummaryAiResult summaryResult =
                new ConsultationSummaryAiResult();

        summaryResult.headline =
                "보험 해지환급금 관련 상담";

        summaryResult.summaryText =
                "보험을 해지한 뒤 예상보다 적은 환급금을 받은 상황입니다.";

        ConsultationSummaryAiResult.KeyPoint point =
                new ConsultationSummaryAiResult
                        .KeyPoint();

        point.text =
                "해지 후 환급금이 예상보다 적었습니다.";

        ConsultationSummaryAiResult.KeyPoint point2 =
                new ConsultationSummaryAiResult
                        .KeyPoint();

        point2.text =
                "환급금 산정 이유를 확인하고 싶어 합니다.";

        summaryResult.keyPoints =
                List.of(
                        point,
                        point2
                );

        ConsultationSummary summary =
                new ConsultationSummary(
                        consultation,
                        consultation
                                .getCaseInputRevision(),
                        consultation
                                .getFollowUpAnswerRevision(),
                        "gpt-5.6-luna",
                        jsonMapper.writeValueAsString(
                                summaryResult
                        ),
                        now.plusSeconds(5)
                );

        summary.confirm(
                now.plusSeconds(6)
        );

        summaryRepository.saveAndFlush(
                summary
        );

        consultation.moveToAnalysis(
                now.plusSeconds(6)
        );

        consultation =
                consultationRepository
                        .saveAndFlush(
                                consultation
                        );

        Cookie cookie =
                new Cookie(
                        GuestSessionCookie.NAME,
                        rawToken
                );

        return new TestContext(
                consultation,
                cookie
        );
    }

    private AnalysisAiResult readyForReport() {

        AnalysisAiResult result =
                new AnalysisAiResult();

        result.outcome =
                AnalysisAiResult.Outcome.READY_FOR_REPORT;

        result.analysisSummary =
                "현재 확인된 정보를 기준으로 핵심 쟁점을 정리할 수 있습니다.";

        AnalysisAiResult.KeyIssue issue =
                new AnalysisAiResult.KeyIssue();

        issue.title =
                "해지환급금 산정 기준 확인";

        issue.explanation =
                "실제 지급된 금액과 계약상 산정 기준을 비교해 볼 필요가 있습니다.";

        result.keyIssues =
                List.of(issue);

        result.additionalInformationNeeded =
                List.of();

        return result;
    }

    private AnalysisAiResult needsMoreInfo() {

        AnalysisAiResult result =
                new AnalysisAiResult();

        result.outcome =
                AnalysisAiResult.Outcome.NEEDS_MORE_INFO;

        result.analysisSummary =
                "현재 정보만으로는 핵심 상황을 충분히 구분하기 어렵습니다.";

        AnalysisAiResult.KeyIssue issue =
                new AnalysisAiResult.KeyIssue();

        issue.title =
                "보험 해지 시점 확인";

        issue.explanation =
                "해지 시점에 따라 현재 상황을 이해하는 데 차이가 생길 수 있습니다.";

        AnalysisAiResult.AdditionalInformation info =
                new AnalysisAiResult
                        .AdditionalInformation();

        info.topic =
                "보험을 실제로 해지한 시점";

        info.reason =
                "사건의 시간 순서를 확인하기 위해 필요합니다.";

        result.keyIssues =
                List.of(issue);

        result.additionalInformationNeeded =
                List.of(info);

        return result;
    }

    private void cleanDatabase() {

        analysisJobRepository.deleteAll();
        summaryRepository.deleteAll();
        consultationRepository.deleteAll();
        guestSessionRepository.deleteAll();
    }

    private record TestContext(
            Consultation consultation,
            Cookie cookie
    ) {
    }
}