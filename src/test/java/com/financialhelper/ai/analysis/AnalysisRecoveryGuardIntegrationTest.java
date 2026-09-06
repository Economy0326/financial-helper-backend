package com.financialhelper.ai.analysis;

import com.financialhelper.consultation.Consultation;
import com.financialhelper.consultation.ConsultationCategory;
import com.financialhelper.consultation.ConsultationRepository;
import com.financialhelper.consultation.ConsultationStatus;

import com.financialhelper.guest.GuestSession;
import com.financialhelper.guest.GuestSessionRepository;
import com.financialhelper.guest.GuestSessionTokenService;

import com.financialhelper.ai.summary.ConsultationSummary;
import com.financialhelper.ai.summary.ConsultationSummaryRepository;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;

import org.springframework.boot.test.context.SpringBootTest;

import org.springframework.test.context.ActiveProfiles;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(
        properties = {
                "app.ai.analysis.stale-after=PT1M",
                "app.ai.analysis.max-attempts=1"
        }
)
@ActiveProfiles("test")
class AnalysisRecoveryGuardIntegrationTest {

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

    @Autowired
    private ConsultationSummaryRepository
            summaryRepository;

    @AfterEach
    void cleanDatabase() {

        analysisJobRepository
                .deleteAll();

        summaryRepository
                .deleteAll();

        consultationRepository
                .deleteAll();

        guestSessionRepository
                .deleteAll();
    }

    // 오래 멈춘 Analysis Job이 stale 처리되어 FAILED로 복구되는지 검증
    @Test
    void staleProcessingJobBecomesFailed() {

        TestContext context =
                createAnalyzingConsultation();

        OffsetDateTime oldTime =
                OffsetDateTime.now(
                        ZoneOffset.UTC
                )
                        .minusMinutes(5);

        AnalysisJob job =
                new AnalysisJob(
                        context.consultation(),
                        context.consultation()
                                .getCaseInputRevision(),
                        context.consultation()
                                .getFollowUpAnswerRevision(),
                        "gpt-5.6-luna",
                        oldTime
                );

        job.startProcessing(
                oldTime.plusSeconds(1)
        );

        analysisJobRepository
                .saveAndFlush(job);

        AnalysisStateResponse state =
                persistenceService
                        .getState(
                                context.consultation()
                                        .getId(),
                                context.rawToken()
                        );

        assertThat(
                state.status()
        ).isEqualTo(
                "FAILED"
        );

        Consultation latest =
                consultationRepository
                        .findById(
                                context.consultation()
                                        .getId()
                        )
                        .orElseThrow();

        assertThat(
                latest.getStatus()
        ).isEqualTo(
                ConsultationStatus.FAILED
        );
    }

    // 설정된 최대 Analysis 재시도 횟수를 초과하면 Retry를 막는지 검증
    @Test
    void preventsRetryBeyondConfiguredAttemptLimit() {

        TestContext context =
                createAnalyzingConsultation();

        OffsetDateTime now =
                OffsetDateTime.now(
                        ZoneOffset.UTC
                );

        AnalysisJob job =
                new AnalysisJob(
                        context.consultation(),
                        context.consultation()
                                .getCaseInputRevision(),
                        context.consultation()
                                .getFollowUpAnswerRevision(),
                        "gpt-5.6-luna",
                        now
                );

        job.startProcessing(
                now.plusSeconds(1)
        );

        job.fail(
                "AI_GENERATION_FAILED",
                now.plusSeconds(2)
        );

        analysisJobRepository
                .saveAndFlush(job);

        Consultation consultation =
                consultationRepository
                        .findById(
                                context.consultation()
                                        .getId()
                        )
                        .orElseThrow();

        consultation.markAnalysisFailed(
                now.plusSeconds(2)
        );

        consultationRepository
                .saveAndFlush(
                        consultation
                );

        assertThatThrownBy(
                () ->
                        persistenceService
                                .reserveRetry(
                                        consultation.getId(),
                                        context.rawToken()
                                )
        )
                .isInstanceOf(
                        AnalysisRetryLimitExceededException.class
                );
    }

    private TestContext createAnalyzingConsultation() {

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

        consultation.moveToSummary(
                now.plusSeconds(3)
        );

        consultation =
                consultationRepository
                        .saveAndFlush(
                                consultation
                        );

        // Retry는 Confirmed Summary 이후에만 가능하므로
        // 실제 Analysis 진입 조건을 테스트 데이터에도 준비한다.
        ConsultationSummary summary =
                new ConsultationSummary(
                        consultation,
                        consultation
                                .getCaseInputRevision(),
                        consultation
                                .getFollowUpAnswerRevision(),
                        "gpt-5.6-luna",
                        "{}",
                        now.plusSeconds(4)
                );

        summary.confirm(
                now.plusSeconds(5)
        );

        summaryRepository
                .saveAndFlush(
                        summary
                );

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

        return new TestContext(
                consultation,
                rawToken
        );
    }

    private record TestContext(
            Consultation consultation,
            String rawToken
    ) {
    }
}