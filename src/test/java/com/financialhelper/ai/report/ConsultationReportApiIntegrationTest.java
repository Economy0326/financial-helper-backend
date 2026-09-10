package com.financialhelper.ai.report;

import com.financialhelper.ai.OpenAiStructuredClient;

import com.financialhelper.ai.analysis.AnalysisAiResult;
import com.financialhelper.ai.analysis.AnalysisJob;
import com.financialhelper.ai.analysis.AnalysisJobRepository;

import com.financialhelper.ai.summary.ConsultationSummary;
import com.financialhelper.ai.summary.ConsultationSummaryAiResult;
import com.financialhelper.ai.summary.ConsultationSummaryRepository;

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
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
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
class ConsultationReportApiIntegrationTest {

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
    private ConsultationReportRepository
            reportRepository;

    @Autowired
    private GuestSessionTokenService
            tokenService;

    @Autowired
    private JsonMapper jsonMapper;

    @MockitoBean
    private OpenAiStructuredClient
            openAiStructuredClient;

    @AfterEach
    void clean() {
        reportRepository.deleteAll();
        analysisJobRepository.deleteAll();
        summaryRepository.deleteAll();
        consultationRepository.deleteAll();
        guestSessionRepository.deleteAll();
    }

    // 같은 revision의 Report는 한 번만 생성되고, 이후 요청과 GET에서는 지정된 Report를 재사용하는지 검증
    @Test
    void preparesReportOnceAndReadsPersistedReport()
            throws Exception {

        TestContext context =
                createAnalysisCompletedContext();

        when(
                openAiStructuredClient
                        .generateStructured(
                                anyString(),
                                anyString(),
                                eq(
                                        ConsultationReportAiResult.class
                                )
                        )
        ).thenReturn(
                createReport()
        );

        mockMvc.perform(
                        post(
                                "/api/v1/consultations/{id}/report/prepare",
                                context.consultation().getId()
                        )
                                .cookie(
                                        context.cookie()
                                )
                                .with(csrf())
                )
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath("$.kind")
                                .value("ready")
                )
                .andExpect(
                        jsonPath("$.report.headline")
                                .value(
                                        "보험 해지환급금 확인 가이드"
                                )
                )
                .andExpect(
                        jsonPath("$.report.citations.length()")
                                .value(0)
                )
                .andExpect(
                        jsonPath("$.report.similarCases.length()")
                                .value(0)
                );

        /*
         * 동일 Revision 재요청은 저장 결과 사용.
         */
        mockMvc.perform(
                        post(
                                "/api/v1/consultations/{id}/report/prepare",
                                context.consultation().getId()
                        )
                                .cookie(
                                        context.cookie()
                                )
                                .with(csrf())
                )
                .andExpect(status().isOk());

        verify(
                openAiStructuredClient,
                times(1)
        ).generateStructured(
                anyString(),
                anyString(),
                eq(
                        ConsultationReportAiResult.class
                )
        );

        assertThat(
                reportRepository.count()
        ).isEqualTo(1L);

        mockMvc.perform(
                        get(
                                "/api/v1/consultations/{id}/report",
                                context.consultation().getId()
                        )
                                .cookie(
                                        context.cookie()
                                )
                )
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath("$.kind")
                                .value("ready")
                )
                .andExpect(
                        jsonPath("$.evidence.status")
                                .value(
                                        "NOT_AVAILABLE_IN_AI_V1"
                                )
                );

        Consultation latest =
                consultationRepository
                        .findById(
                                context.consultation().getId()
                        )
                        .orElseThrow();

        assertThat(
                latest.getCurrentStep()
        ).isEqualTo(
                ConsultationStep.REPORT
        );
    }

    private TestContext createAnalysisCompletedContext()
            throws Exception {

        OffsetDateTime now =
                OffsetDateTime.now(
                        ZoneOffset.UTC
                );

        String rawToken =
                tokenService.generateRawToken();

        GuestSession guest =
                guestSessionRepository.save(
                        new GuestSession(
                                tokenService.hashToken(
                                        rawToken
                                ),
                                now,
                                now.plusHours(1)
                        )
                );

        Consultation consultation =
                new Consultation(
                        guest,
                        now
                );

        consultation.updateCategory(
                ConsultationCategory.INSURANCE,
                now.plusSeconds(1)
        );

        consultation.updateSituation(
                "보험을 해지했는데 예상보다 환급금이 적었습니다.",
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
                "보험 해지 후 예상보다 적은 환급금을 받은 상황입니다.";

        ConsultationSummaryAiResult.KeyPoint point1 =
                new ConsultationSummaryAiResult.KeyPoint();

        point1.text =
                "보험을 이미 해지했습니다.";

        ConsultationSummaryAiResult.KeyPoint point2 =
                new ConsultationSummaryAiResult.KeyPoint();

        point2.text =
                "환급금 산정 기준을 확인하고 싶어 합니다.";

        summaryResult.keyPoints =
                List.of(
                        point1,
                        point2
                );

        ConsultationSummary summary =
                new ConsultationSummary(
                        consultation,
                        consultation.getCaseInputRevision(),
                        consultation.getFollowUpAnswerRevision(),
                        "gpt-5.6-luna",
                        jsonMapper.writeValueAsString(
                                summaryResult
                        ),
                        now.plusSeconds(5)
                );

        summary.confirm(
                now.plusSeconds(6)
        );

        summaryRepository
                .saveAndFlush(summary);

        consultation.moveToAnalysis(
                now.plusSeconds(6)
        );

        consultation.startAnalysis(
                now.plusSeconds(7)
        );

        consultation =
                consultationRepository
                        .saveAndFlush(
                                consultation
                        );

        AnalysisJob analysisJob =
                new AnalysisJob(
                        consultation,
                        consultation.getCaseInputRevision(),
                        consultation.getFollowUpAnswerRevision(),
                        "gpt-5.6-luna",
                        now.plusSeconds(7)
                );

        analysisJob.startProcessing(
                now.plusSeconds(8)
        );

        AnalysisAiResult analysisResult =
                new AnalysisAiResult();

        analysisResult.outcome =
                AnalysisAiResult
                        .Outcome.READY_FOR_REPORT;

        analysisResult.analysisSummary =
                "환급금 산정 기준과 실제 지급 내역을 우선 확인할 필요가 있습니다.";

        AnalysisAiResult.KeyIssue issue =
                new AnalysisAiResult.KeyIssue();

        issue.title =
                "해지환급금 산정 기준";

        issue.explanation =
                "지급된 금액이 어떤 기준으로 계산됐는지 확인할 필요가 있습니다.";

        analysisResult.keyIssues =
                List.of(issue);

        analysisResult.additionalInformationNeeded =
                List.of();

        analysisJob.complete(
                jsonMapper.writeValueAsString(
                        analysisResult
                ),
                now.plusSeconds(9)
        );

        analysisJobRepository
                .saveAndFlush(
                        analysisJob
                );

        consultation.markAnalysisReady(
                now.plusSeconds(9)
        );

        consultation =
                consultationRepository
                        .saveAndFlush(
                                consultation
                        );

        return new TestContext(
                consultation,

                new Cookie(
                        GuestSessionCookie.NAME,
                        rawToken
                )
        );
    }

    private ConsultationReportAiResult createReport() {

        ConsultationReportAiResult result =
                new ConsultationReportAiResult();

        result.headline =
                "보험 해지환급금 확인 가이드";

        result.caseSummary =
                "보험을 해지한 뒤 예상보다 적은 환급금을 받은 상황입니다.";

        ConsultationReportAiResult.FirstAction
                firstAction =
                new ConsultationReportAiResult.FirstAction();

        firstAction.title =
                "해지환급금 산출내역부터 확인해 보세요";

        firstAction.description =
                "금융회사에 실제 지급 금액의 산정 내역을 확인할 수 있는 자료를 요청해 보세요.";

        result.firstAction =
                firstAction;

        ConsultationReportAiResult.KeyIssue issue =
                new ConsultationReportAiResult.KeyIssue();

        issue.title =
                "환급금 산정 기준 확인";

        issue.explanation =
                "실제 지급 금액과 계약 관련 자료를 함께 확인하는 것이 중요합니다.";

        result.keyIssues =
                List.of(issue);

        ConsultationReportAiResult.ActionStep step =
                new ConsultationReportAiResult.ActionStep();

        step.order = 1;

        step.title =
                "산출내역 요청하기";

        step.description =
                "금융회사에 해지환급금이 어떻게 계산되었는지 확인할 수 있는 자료를 요청하세요.";

        result.actionSteps =
                List.of(step);

        result.actionConsequences =
                List.of();

        ConsultationReportAiResult.RequiredDocument document =
                new ConsultationReportAiResult.RequiredDocument();

        document.name =
                "보험 계약 관련 자료";

        document.reason =
                "계약 내용과 지급 내역을 비교하기 위해 도움이 됩니다.";

        result.requiredDocuments =
                List.of(document);

        ConsultationReportAiResult.FinancialTerm term =
                new ConsultationReportAiResult.FinancialTerm();

        term.term =
                "해지환급금";

        term.explanation =
                "보험 계약을 중도에 해지할 때 계약 조건 등에 따라 지급될 수 있는 금액입니다.";

        result.terms =
                List.of(term);

        ConsultationReportAiResult.ComplaintDraft draft =
                new ConsultationReportAiResult.ComplaintDraft();

        draft.subject =
                "해지환급금 산정 내역 확인 요청";

        draft.body =
                """
                보험 계약 해지 후 지급된 해지환급금과 관련하여,
                지급 금액의 산정 기준과 세부 내역을 확인하고 싶습니다.

                확인 가능한 관련 자료와 설명을 제공해 주시기 바랍니다.
                """;

        result.complaintDraft =
                draft;

        return result;
    }

    private record TestContext(
            Consultation consultation,
            Cookie cookie
    ) {
    }
}