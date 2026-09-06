package com.financialhelper.ai.analysis;

import com.financialhelper.consultation.Consultation;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(
        name = "analysis_job",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_analysis_job_revision",
                        columnNames = {
                                "consultation_id",
                                "case_input_revision",
                                "follow_up_answer_revision"
                        }
                )
        }
)
public class AnalysisJob {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(
            fetch = FetchType.LAZY,
            optional = false
    )
    @JoinColumn(
            name = "consultation_id",
            nullable = false
    )
    private Consultation consultation;

    @Column(
            name = "case_input_revision",
            nullable = false
    )
    private long caseInputRevision;

    @Column(
            name = "follow_up_answer_revision",
            nullable = false
    )
    private long followUpAnswerRevision;

    @Enumerated(EnumType.STRING)
    @Column(
            name = "status",
            nullable = false,
            length = 32
    )
    private AnalysisJobStatus status;

    @Column(
            name = "attempt_count",
            nullable = false
    )
    private int attemptCount;

    @Column(
            name = "model",
            nullable = false,
            length = 100
    )
    private String model;

    @Column(
            name = "result_json",
            columnDefinition = "TEXT"
    )
    private String resultJson;

    @Column(
            name = "failure_code",
            length = 64
    )
    private String failureCode;

    @Column(
            name = "created_at",
            nullable = false
    )
    private OffsetDateTime createdAt;

    @Column(
            name = "queued_at",
            nullable = false
    )
    private OffsetDateTime queuedAt;

    @Column(name = "started_at")
    private OffsetDateTime startedAt;

    @Column(name = "finished_at")
    private OffsetDateTime finishedAt;

    protected AnalysisJob() {
    }

    public AnalysisJob(
            Consultation consultation,
            long caseInputRevision,
            long followUpAnswerRevision,
            String model,
            OffsetDateTime now
    ) {
        this.consultation = consultation;
        this.caseInputRevision = caseInputRevision;
        this.followUpAnswerRevision = followUpAnswerRevision;

        this.status =
                AnalysisJobStatus.QUEUED;

        this.attemptCount = 1;
        this.model = model;

        this.createdAt = now;
        this.queuedAt = now;
    }

    public UUID getId() {
        return id;
    }

    public Consultation getConsultation() {
        return consultation;
    }

    public long getCaseInputRevision() {
        return caseInputRevision;
    }

    public long getFollowUpAnswerRevision() {
        return followUpAnswerRevision;
    }

    public AnalysisJobStatus getStatus() {
        return status;
    }

    public int getAttemptCount() {
        return attemptCount;
    }

    public String getModel() {
        return model;
    }

    public String getResultJson() {
        return resultJson;
    }

    public String getFailureCode() {
        return failureCode;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public OffsetDateTime getQueuedAt() {
        return queuedAt;
    }

    public OffsetDateTime getStartedAt() {
        return startedAt;
    }

    public OffsetDateTime getFinishedAt() {
        return finishedAt;
    }

    // QUEUED -> 분석 작업은 만들어졌는데, 아직 실제 AI 분석을 시작하지 않은 대기 상태
    public void startProcessing(
            OffsetDateTime now
    ) {

        if (
                status != AnalysisJobStatus.QUEUED
        ) {
            throw new IllegalStateException(
                    "Only queued analysis can start processing"
            );
        }

        status =
                AnalysisJobStatus.PROCESSING;

        startedAt = now;
        finishedAt = null;
        failureCode = null;
    }

    public void complete(
            String resultJson,
            OffsetDateTime now
    ) {
        this.resultJson = resultJson;
        this.failureCode = null;

        this.status =
                AnalysisJobStatus.COMPLETED;

        this.finishedAt = now;
    }

    public void needsMoreInfo(
            String resultJson,
            OffsetDateTime now
    ) {
        this.resultJson = resultJson;
        this.failureCode = null;

        this.status =
                AnalysisJobStatus.NEEDS_MORE_INFO;

        this.finishedAt = now;
    }

    public void fail(
            String failureCode,
            OffsetDateTime now
    ) {
        this.status =
                AnalysisJobStatus.FAILED;

        this.failureCode =
                failureCode;

        this.finishedAt = now;
    }

    public void queueRetry(
            String model,
            OffsetDateTime now
    ) {
        this.status =
                AnalysisJobStatus.QUEUED;

        // RETRY시 시도횟수 1 증가
        this.attemptCount++;

        this.model = model;

        this.resultJson = null;
        this.failureCode = null;

        this.queuedAt = now;
        this.startedAt = null;
        this.finishedAt = null;
    }
}