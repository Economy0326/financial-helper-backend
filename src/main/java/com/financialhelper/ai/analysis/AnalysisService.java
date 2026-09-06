package com.financialhelper.ai.analysis;

import org.springframework.boot.autoconfigure.condition
        .ConditionalOnProperty;

import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
@ConditionalOnProperty(
        prefix = "app.ai",
        name = "enabled",
        havingValue = "true"
)
public class AnalysisService {

    private final AnalysisPersistenceService
            persistenceService;

    private final AnalysisWorker
            analysisWorker;

    public AnalysisService(
            AnalysisPersistenceService persistenceService,
            AnalysisWorker analysisWorker
    ) {
        this.persistenceService =
                persistenceService;

        this.analysisWorker =
                analysisWorker;
    }

    public AnalysisStateResponse start(
            UUID consultationId,
            String rawToken
    ) {

        // 내부 Transaction이 종료된 다음 Worker를 dispatch 한다.
        // 따라서 Worker가 아직 commit되지 않은 Job을 읽으러 가는 문제를 피한다.
        AnalysisData.Reservation reservation =
                persistenceService
                        .reserveStart(
                                consultationId,
                                rawToken
                        );

        if (
                reservation.shouldDispatch()
        ) {
            // dispatch 이후에 OpenAI API 호출 직전에도 beginProcessing 트랜잭션을 먼저 진행
            // BeginProcessing => DB 상태를 PROCESSING으로 확정하고, 그 시점의 입력을 Snapshot으로 꺼내옴
            analysisWorker.run(
                    reservation.jobId()
            );
        }

        return reservation.state();
    }

    public AnalysisStateResponse getState(
            UUID consultationId,
            String rawToken
    ) {

        return persistenceService
                .getState(
                        consultationId,
                        rawToken
                );
    }

    public AnalysisStateResponse retry(
            UUID consultationId,
            String rawToken
    ) {

        AnalysisData.Reservation reservation =
                persistenceService
                        .reserveRetry(
                                consultationId,
                                rawToken
                        );

        if (
                reservation.shouldDispatch()
        ) {
            analysisWorker.run(
                    reservation.jobId()
            );
        }

        return reservation.state();
    }

    public ReopenAnalysisResponse
    reopenForMoreInfo(
            UUID consultationId,
            String rawToken
    ) {

        return persistenceService
                .reopenForMoreInfo(
                        consultationId,
                        rawToken
                );
    }

    public InformationSupplementContextResponse
    getSupplementContext(
            UUID consultationId,
            String rawToken
    ) {

        return persistenceService
                .getSupplementContext(
                        consultationId,
                        rawToken
                );
    }
}