package com.financialhelper.ai;

import com.financialhelper.ai.analysis.AnalysisAiResult;
import com.financialhelper.ai.analysis.AnalysisJobRepository;
import com.financialhelper.ai.analysis.AnalysisJobStatus;

import com.financialhelper.ai.followup.FollowUpQuestion;
import com.financialhelper.ai.followup.FollowUpQuestionAiResult;
import com.financialhelper.ai.followup.FollowUpQuestionRepository;

import com.financialhelper.ai.report.ConsultationReportAiResult;
import com.financialhelper.ai.report.ConsultationReportRepository;

import com.financialhelper.ai.summary.ConsultationSummaryAiResult;
import com.financialhelper.ai.summary.ConsultationSummaryRepository;

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
class ConsultationAiV1EndToEndIntegrationTest {

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
            understandingRepository;

    @Autowired
    private FollowUpQuestionRepository
            followUpQuestionRepository;

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

    @MockitoBean
    private OpenAiStructuredClient
            openAiStructuredClient;

    @AfterEach
    void cleanDatabase() {

        reportRepository.deleteAll();
        analysisJobRepository.deleteAll();
        summaryRepository.deleteAll();
        followUpQuestionRepository.deleteAll();
        understandingRepository.deleteAll();
        consultationRepository.deleteAll();
        guestSessionRepository.deleteAll();
    }

    // AI V1 전체 FLOW 테스트
    @Test
    void completesAiV1FlowFromSituationToPersistedReport()
            throws Exception {

        TestContext context =
                createConsultation();

        stubAiResults();

        /*
         * 1. Fact Extraction + Missing Info
         */
        mockMvc.perform(
                        post(
                                "/api/v1/consultations/{id}/understanding",
                                context.consultation().getId()
                        )
                                .cookie(context.cookie())
                                .with(csrf())
                )
                .andExpect(status().isOk());

        /*
         * 2. Follow-up 준비
         */
        mockMvc.perform(
                        post(
                                "/api/v1/consultations/{id}/follow-up/prepare",
                                context.consultation().getId()
                        )
                                .cookie(context.cookie())
                                .with(csrf())
                )
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath("$.kind")
                                .value("question")
                );

        Consultation latest =
                consultationRepository
                        .findById(
                                context.consultation().getId()
                        )
                        .orElseThrow();

        FollowUpQuestion question =
                followUpQuestionRepository
                        .findByConsultation_IdAndCaseInputRevisionOrderBySequenceNoAsc(
                                latest.getId(),
                                latest.getCaseInputRevision()
                        )
                        .getFirst();

        /*
         * 3. Follow-up 답변
         */
        mockMvc.perform(
                        put(
                                "/api/v1/consultations/{id}/follow-up/questions/{questionId}/answer",
                                latest.getId(),
                                question.getId()
                        )
                                .cookie(context.cookie())
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
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath("$.kind")
                                .value("complete")
                );

        /*
         * 4. Summary 생성
         */
        mockMvc.perform(
                        post(
                                "/api/v1/consultations/{id}/summary/prepare",
                                latest.getId()
                        )
                                .cookie(context.cookie())
                                .with(csrf())
                )
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath("$.kind")
                                .value("ready")
                );

        /*
         * 5. Summary Confirm
         */
        mockMvc.perform(
                        post(
                                "/api/v1/consultations/{id}/summary/confirm",
                                latest.getId()
                        )
                                .cookie(context.cookie())
                                .with(csrf())
                )
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath("$.nextStep")
                                .value("ANALYSIS")
                );

        /*
         * 6. Analysis Start
         */
        mockMvc.perform(
                        post(
                                "/api/v1/consultations/{id}/analysis/start",
                                latest.getId()
                        )
                                .cookie(context.cookie())
                                .with(csrf())
                )
                .andExpect(status().isOk());

        waitForAnalysis(
                latest.getId()
        );

        /*
         * 7. Analysis 상태 확인
         */
        mockMvc.perform(
                        get(
                                "/api/v1/consultations/{id}/analysis",
                                latest.getId()
                        )
                                .cookie(context.cookie())
                )
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath("$.status")
                                .value("COMPLETED")
                );

        /*
         * 8. Report 생성
         */
        mockMvc.perform(
                        post(
                                "/api/v1/consultations/{id}/report/prepare",
                                latest.getId()
                        )
                                .cookie(context.cookie())
                                .with(csrf())
                )
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath("$.kind")
                                .value("ready")
                );

        /*
         * 9. Report 조회
         */
        mockMvc.perform(
                        get(
                                "/api/v1/consultations/{id}/report",
                                latest.getId()
                        )
                                .cookie(context.cookie())
                )
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath("$.kind")
                                .value("ready")
                )
                .andExpect(
                        jsonPath(
                                "$.report.citations.length()"
                        )
                                .value(0)
                );

        Consultation completed =
                consultationRepository
                        .findById(
                                latest.getId()
                        )
                        .orElseThrow();

        assertThat(
                completed.getCurrentStep()
        ).isEqualTo(
                ConsultationStep.REPORT
        );

        assertThat(
                understandingRepository.count()
        ).isEqualTo(1);

        assertThat(
                followUpQuestionRepository.count()
        ).isEqualTo(1);

        assertThat(
                summaryRepository.count()
        ).isEqualTo(1);

        assertThat(
                analysisJobRepository.count()
        ).isEqualTo(1);

        assertThat(
                reportRepository.count()
        ).isEqualTo(1);
    }

    private TestContext createConsultation() {

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
                "보험을 해지했는데 예상보다 해지환급금이 적게 지급됐습니다.",
                now.plusSeconds(2)
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

    private void stubAiResults() {

        when(
                openAiStructuredClient
                        .generateStructured(
                                anyString(),
                                anyString(),
                                eq(
                                        CaseUnderstandingAiResult.class
                                )
                        )
        ).thenReturn(
                understandingResult()
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
        ).thenReturn(
                followUpResult()
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
        ).thenReturn(
                summaryResult()
        );

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
                analysisResult()
        );

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
                reportResult()
        );
    }

    private CaseUnderstandingAiResult
    understandingResult() {

        CaseUnderstandingAiResult.ExtractedFact fact =
                new CaseUnderstandingAiResult
                        .ExtractedFact();

        fact.type =
                CaseUnderstandingAiResult
                        .FactType.EVENT;

        fact.label =
                "현재 문제";

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
                "지급 금액 산정 내용을 확인하는 데 도움이 됩니다.";

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

    private FollowUpQuestionAiResult
    followUpResult() {

        FollowUpQuestionAiResult.Option yes =
                option(
                        "YES",
                        "네",
                        "확인했어요."
                );

        FollowUpQuestionAiResult.Option no =
                option(
                        "NO",
                        "아니요",
                        "아직 확인하지 않았어요."
                );

        FollowUpQuestionAiResult.Option unknown =
                option(
                        "UNKNOWN",
                        "잘 모르겠어요",
                        "정확히 모르겠어요."
                );

        FollowUpQuestionAiResult.Question question =
                new FollowUpQuestionAiResult
                        .Question();

        question.question =
                "해지환급금 산출내역을 확인하셨나요?";

        question.description =
                "현재 상황과 가까운 항목을 선택해 주세요.";

        question.options =
                List.of(
                        yes,
                        no,
                        unknown
                );

        FollowUpQuestionAiResult result =
                new FollowUpQuestionAiResult();

        result.questions =
                List.of(question);

        return result;
    }

    private FollowUpQuestionAiResult.Option option(
            String value,
            String label,
            String description
    ) {

        FollowUpQuestionAiResult.Option option =
                new FollowUpQuestionAiResult
                        .Option();

        option.value = value;
        option.label = label;
        option.description = description;

        return option;
    }

    private ConsultationSummaryAiResult
    summaryResult() {

        ConsultationSummaryAiResult result =
                new ConsultationSummaryAiResult();

        result.headline =
                "보험 해지환급금 관련 상담";

        result.summaryText =
                "보험 해지 후 예상보다 적은 환급금을 받은 상황입니다.";

        ConsultationSummaryAiResult.KeyPoint point1 =
                new ConsultationSummaryAiResult
                        .KeyPoint();

        point1.text =
                "보험 계약을 해지했습니다.";

        ConsultationSummaryAiResult.KeyPoint point2 =
                new ConsultationSummaryAiResult
                        .KeyPoint();

        point2.text =
                "해지환급금 산정 내용을 확인하고 싶어 합니다.";

        result.keyPoints =
                List.of(
                        point1,
                        point2
                );

        return result;
    }

    private AnalysisAiResult analysisResult() {

        AnalysisAiResult result =
                new AnalysisAiResult();

        result.outcome =
                AnalysisAiResult
                        .Outcome.READY_FOR_REPORT;

        result.analysisSummary =
                "현재 정보로 해결 리포트를 작성할 수 있습니다.";

        AnalysisAiResult.KeyIssue issue =
                new AnalysisAiResult.KeyIssue();

        issue.title =
                "해지환급금 산정 기준";

        issue.explanation =
                "실제 지급 금액의 계산 기준을 확인할 필요가 있습니다.";

        result.keyIssues =
                List.of(issue);

        result.additionalInformationNeeded =
                List.of();

        return result;
    }

    private ConsultationReportAiResult reportResult() {

        ConsultationReportAiResult result =
                new ConsultationReportAiResult();

        result.headline =
                "해지환급금 확인 가이드";

        result.caseSummary =
                "보험 해지 후 예상보다 적은 환급금을 받은 상황입니다.";

        ConsultationReportAiResult.FirstAction first =
                new ConsultationReportAiResult.FirstAction();

        first.title =
                "산출내역부터 확인해 보세요";

        first.description =
                "금융회사에 지급 금액의 산출내역을 요청해 보세요.";

        result.firstAction = first;

        ConsultationReportAiResult.KeyIssue issue =
                new ConsultationReportAiResult.KeyIssue();

        issue.title =
                "환급금 산정 기준";

        issue.explanation =
                "계약 내용과 실제 지급 내역을 비교할 필요가 있습니다.";

        result.keyIssues =
                List.of(issue);

        ConsultationReportAiResult.ActionStep step =
                new ConsultationReportAiResult.ActionStep();

        step.order = 1;

        step.title =
                "산출자료 요청";

        step.description =
                "금융회사에 해지환급금 산출자료를 요청합니다.";

        result.actionSteps =
                List.of(step);

        result.actionConsequences =
                List.of();

        ConsultationReportAiResult.RequiredDocument document =
                new ConsultationReportAiResult
                        .RequiredDocument();

        document.name =
                "보험 계약 관련 자료";

        document.reason =
                "계약 조건과 지급 내역을 확인하는 데 도움이 됩니다.";

        result.requiredDocuments =
                List.of(document);

        ConsultationReportAiResult.FinancialTerm term =
                new ConsultationReportAiResult
                        .FinancialTerm();

        term.term =
                "해지환급금";

        term.explanation =
                "보험 계약을 중도 해지할 때 계약 조건 등에 따라 지급될 수 있는 금액입니다.";

        result.terms =
                List.of(term);

        ConsultationReportAiResult.ComplaintDraft draft =
                new ConsultationReportAiResult
                        .ComplaintDraft();

        draft.subject =
                "해지환급금 산정 내역 확인 요청";

        draft.body =
                "해지환급금의 산정 기준과 세부 내역을 확인하고 싶습니다.";

        result.complaintDraft =
                draft;

        return result;
    }

    private void waitForAnalysis(
            java.util.UUID consultationId
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

            var job =
                    analysisJobRepository
                            .findByConsultation_IdAndCaseInputRevisionAndFollowUpAnswerRevision(
                                    consultation.getId(),
                                    consultation.getCaseInputRevision(),
                                    consultation.getFollowUpAnswerRevision()
                            );

            if (
                    job.isPresent()
                    && job.get().getStatus()
                    == AnalysisJobStatus.COMPLETED
            ) {
                return;
            }

            Thread.sleep(50L);
        }

        throw new AssertionError(
                "Analysis did not complete"
        );
    }

    private record TestContext(
            Consultation consultation,
            Cookie cookie
    ) {
    }
}