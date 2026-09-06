package com.financialhelper.consultation;

import com.financialhelper.guest.GuestSession;

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

import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "consultation")
public class Consultation {

    // =========================
    // Fields
    // =========================

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    // GuestSession 1 : Consultation N
    // LAZY: Consultation 조회 시 GuestSession을 즉시 조회하지 않음
    @ManyToOne(
            fetch = FetchType.LAZY,
            optional = false
    )
    @JoinColumn(
            name = "guest_session_id",
            nullable = false
    )
    private GuestSession guestSession;

    @Enumerated(EnumType.STRING)
    @Column(
            name = "category",
            length = 32
    )
    private ConsultationCategory category;

    @Column(name = "situation_text")
    private String situationText;

    @Enumerated(EnumType.STRING)
    @Column(
            name = "status",
            nullable = false,
            length = 32
    )
    private ConsultationStatus status;

    @Enumerated(EnumType.STRING)
    @Column(
            name = "current_step",
            nullable = false,
            length = 32
    )
    private ConsultationStep currentStep;

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

    @Column(
            name = "created_at",
            nullable = false
    )
    private OffsetDateTime createdAt;

    @Column(
            name = "updated_at",
            nullable = false
    )
    private OffsetDateTime updatedAt;

    // NEEDS_MORE_INFO 이후 사용자가 실제로 추가 정보 입력 FLOW를 연 횟수
    // Analysis Retry 횟수와 별개
    @Column(
            name = "information_supplement_count",
            nullable = false
    )
    private int informationSupplementCount;


    // =========================
    // Constructors
    // =========================

    protected Consultation() {
    }

    public Consultation(
            GuestSession guestSession,
            OffsetDateTime createdAt
    ) {
        this.guestSession = guestSession;

        // 새 상담의 초기 상태
        this.status =
                ConsultationStatus.IN_PROGRESS;

        this.currentStep =
                ConsultationStep.CATEGORY;

        this.caseInputRevision = 0L;
        this.followUpAnswerRevision = 0L;

        this.createdAt = createdAt;
        this.updatedAt = createdAt;

        this.informationSupplementCount = 0;
    }


    // =========================
    // Getters
    // =========================

    public UUID getId() {
        return id;
    }

    public GuestSession getGuestSession() {
        return guestSession;
    }

    public ConsultationCategory getCategory() {
        return category;
    }

    public String getSituationText() {
        return situationText;
    }

    public ConsultationStatus getStatus() {
        return status;
    }

    public ConsultationStep getCurrentStep() {
        return currentStep;
    }

    public long getCaseInputRevision() {
        return caseInputRevision;
    }

    public long getFollowUpAnswerRevision() {
        return followUpAnswerRevision;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }

    public int getInformationSupplementCount() {
        return informationSupplementCount;
    }

    // =========================
    // Consultation Input
    // =========================

    public void updateCategory(
            ConsultationCategory category,
            OffsetDateTime updatedAt
    ) {
        // Enum은 동일 값 여부를 == / != 로 비교 가능
        boolean changed =
                this.category != category;

        this.category = category;

        if (changed) {
            markCaseInputChanged();

            this.currentStep =
                    ConsultationStep.SITUATION;
        }

        this.updatedAt = updatedAt;
    }

    public void updateSituation(
            String situationText,
            OffsetDateTime updatedAt
    ) {
        // String은 값 기준 비교
        boolean changed =
                !Objects.equals(
                        this.situationText,
                        situationText
                );

        this.situationText = situationText;

        if (changed) {
            markCaseInputChanged();

            this.currentStep =
                    ConsultationStep.FOLLOW_UP;
        }

        this.updatedAt = updatedAt;
    }

    public void recordFollowUpAnswerChanged(
            OffsetDateTime updatedAt
    ) {
        this.followUpAnswerRevision++;
        this.updatedAt = updatedAt;
    }


    // =========================
    // Step Transition
    // =========================

    public void moveToSummary(
            OffsetDateTime updatedAt
    ) {
        this.currentStep =
                ConsultationStep.SUMMARY;

        this.updatedAt = updatedAt;
    }

    public void moveToAnalysis(
            OffsetDateTime updatedAt
    ) {
        this.currentStep =
                ConsultationStep.ANALYSIS;

        this.updatedAt = updatedAt;
    }

    public void moveToReport(
            OffsetDateTime updatedAt
    ) {

        this.status =
                ConsultationStatus.IN_PROGRESS;

        this.currentStep =
                ConsultationStep.REPORT;

        this.updatedAt =
                updatedAt;
    }


    // =========================
    // Analysis State
    // =========================

    public void startAnalysis(
            OffsetDateTime updatedAt
    ) {
        this.status =
                ConsultationStatus.ANALYZING;

        this.currentStep =
                ConsultationStep.ANALYSIS;

        this.updatedAt = updatedAt;
    }

    public void markAnalysisReady(
            OffsetDateTime updatedAt
    ) {
        this.status =
                ConsultationStatus.IN_PROGRESS;

        this.currentStep =
                ConsultationStep.ANALYSIS;

        this.updatedAt = updatedAt;
    }

    public void markNeedsMoreInfo(
            OffsetDateTime updatedAt
    ) {
        this.status =
                ConsultationStatus.NEEDS_MORE_INFO;

        this.currentStep =
                ConsultationStep.ANALYSIS;

        this.updatedAt = updatedAt;
    }

    public void markAnalysisFailed(
            OffsetDateTime updatedAt
    ) {
        this.status =
                ConsultationStatus.FAILED;

        this.currentStep =
                ConsultationStep.ANALYSIS;

        this.updatedAt = updatedAt;
    }

    public void reopenForMoreInfo(
            OffsetDateTime updatedAt
    ) {

        if (
                this.status
                        != ConsultationStatus.NEEDS_MORE_INFO
        ) {
            throw new IllegalStateException(
                    "Only consultation needing more information can be reopened"
            );
        }

        if (
                this.informationSupplementCount >= 1
        ) {
            throw new IllegalStateException(
                    "Information supplementation limit has been reached"
            );
        }

        // 실제 사용자가 '추가 정보 입력하기'를 선택해
        // 보완 flow에 진입한 시점에 1회로 기록
        this.informationSupplementCount++;

        this.status =
                ConsultationStatus.IN_PROGRESS;

        this.currentStep =
                ConsultationStep.SITUATION;

        this.updatedAt =
                updatedAt;
    }

    public boolean canSupplementInformation() {
        return informationSupplementCount < 1;
    }

    public void markInsufficientInformation(
            OffsetDateTime updatedAt
    ) {
        // 추가 보완 1회 이후에도 NEEDS_MORE_INFO가 나온 경우
        // 신뢰 가능한 분석 불가 상태로 종료
        this.status =
                ConsultationStatus.INSUFFICIENT_INFORMATION;

        this.currentStep =
                ConsultationStep.ANALYSIS;

        this.updatedAt =
                updatedAt;
    }


    // =========================
    // Internal Revision
    // =========================

    private void markCaseInputChanged() {
        this.caseInputRevision++;
        this.followUpAnswerRevision = 0L;
    }
}