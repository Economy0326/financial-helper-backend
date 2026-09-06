package com.financialhelper.ai.analysis;

import com.financialhelper.consultation.Consultation;
import com.financialhelper.consultation.ConsultationCategory;
import com.financialhelper.consultation.ConsultationRepository;
import com.financialhelper.consultation.ConsultationStatus;
import com.financialhelper.consultation.ConsultationStep;

import com.financialhelper.guest.GuestSession;
import com.financialhelper.guest.GuestSessionRepository;
import com.financialhelper.guest.GuestSessionTokenService;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class AnalysisInformationSupplementPolicyTest {

    @Autowired
    private AnalysisPersistenceService
            persistenceService;

    @Autowired
    private AnalysisJobRepository
            analysisJobRepository;

    @Autowired
    private ConsultationRepository
            consultationRepository;

    @Autowired
    private GuestSessionRepository
            guestSessionRepository;

    @Autowired
    private GuestSessionTokenService
            tokenService;

    @AfterEach
    void clean() {
        analysisJobRepository.deleteAll();
        consultationRepository.deleteAll();
        guestSessionRepository.deleteAll();
    }

    // 1회 보완 후에도 정보가 부족하면 INSUFFICIENT_INFORMATION으로 종료되는지 검증ㅇ
    @Test
    void secondNeedsMoreInfoBecomesTerminalInsufficientInformation() {

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
                "보험 해지환급금 문제입니다.",
                now.plusSeconds(2)
        );

        /*
         * 1차 Analysis가 NEEDS_MORE_INFO였다고 가정.
         */
        consultation.moveToAnalysis(
                now.plusSeconds(3)
        );

        consultation.markNeedsMoreInfo(
                now.plusSeconds(4)
        );

        /*
         * 사용자가 추가 정보 입력을 선택.
         * 여기서 supplement count = 1.
         */
        consultation.reopenForMoreInfo(
                now.plusSeconds(5)
        );

        /*
         * 실제 Situation 보완.
         */
        consultation.updateSituation(
                "보험을 2026년 8월에 해지했고 환급금 산출내역은 받지 못했습니다.",
                now.plusSeconds(6)
        );

        consultation.recordFollowUpAnswerChanged(
                now.plusSeconds(7)
        );

        consultation.moveToSummary(
                now.plusSeconds(8)
        );

        consultation.moveToAnalysis(
                now.plusSeconds(9)
        );

        consultation =
                consultationRepository
                        .saveAndFlush(
                                consultation
                        );

        AnalysisJob job =
                new AnalysisJob(
                        consultation,
                        consultation.getCaseInputRevision(),
                        consultation.getFollowUpAnswerRevision(),
                        "gpt-5.6-luna",
                        now.plusSeconds(10)
                );

        analysisJobRepository
                .saveAndFlush(job);

        job.startProcessing(
                now.plusSeconds(11)
        );

        analysisJobRepository
                .saveAndFlush(job);

        AnalysisData.Snapshot snapshot =
                new AnalysisData.Snapshot(
                        job.getId(),
                        consultation.getId(),
                        consultation.getCategory(),
                        consultation.getCaseInputRevision(),
                        consultation.getFollowUpAnswerRevision(),
                        null
                );

        AnalysisAiResult result =
                new AnalysisAiResult();

        result.outcome =
                AnalysisAiResult
                        .Outcome.NEEDS_MORE_INFO;

        result.analysisSummary =
                "추가 보완 후에도 핵심 사실 확인이 어렵습니다.";

        AnalysisAiResult.KeyIssue issue =
                new AnalysisAiResult.KeyIssue();

        issue.title =
                "환급금 산출 근거";

        issue.explanation =
                "실제 산출 기준을 확인하기 위한 자료가 부족합니다.";

        AnalysisAiResult.AdditionalInformation info =
                new AnalysisAiResult
                        .AdditionalInformation();

        info.topic =
                "해지환급금 산출내역";

        info.reason =
                "지급 금액 산정 근거를 확인하기 위해 필요합니다.";

        result.keyIssues =
                List.of(issue);

        result.additionalInformationNeeded =
                List.of(info);

        persistenceService.complete(
                snapshot,
                result
        );

        AnalysisJob finishedJob =
                analysisJobRepository
                        .findById(job.getId())
                        .orElseThrow();

        Consultation finishedConsultation =
                consultationRepository
                        .findById(
                                consultation.getId()
                        )
                        .orElseThrow();

        assertThat(
                finishedJob.getStatus()
        ).isEqualTo(
                AnalysisJobStatus
                        .INSUFFICIENT_INFORMATION
        );

        assertThat(
                finishedConsultation.getStatus()
        ).isEqualTo(
                ConsultationStatus
                        .INSUFFICIENT_INFORMATION
        );

        assertThat(
                finishedConsultation
                        .getInformationSupplementCount()
        ).isEqualTo(1);

        assertThat(
                finishedConsultation
                        .getCurrentStep()
        ).isEqualTo(
                ConsultationStep.ANALYSIS
        );
    }
}