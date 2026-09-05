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

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    // GuestSession 1 : Consultation N
    // LAZY => Consultatiob을 조회했다고 GuestSession 전체를 무조건 가져오지 않게 함
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "guest_session_id", nullable = false)
    private GuestSession guestSession;

    // category nullable
    @Enumerated(EnumType.STRING)
    @Column(name = "category", length = 32)
    private ConsultationCategory category;

    // situation nullable
    @Column(name = "situation_text")
    private String situationText;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private ConsultationStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "current_step", nullable = false, length = 32)
    private ConsultationStep currentStep;
    
    @Column(name = "case_input_revision", nullable = false)
    private long caseInputRevision;

    @Column(name = "follow_up_answer_revision", nullable = false)
    private long followUpAnswerRevision;

    public void recordFollowUpAnswerChanged(
            OffsetDateTime updatedAt
    ) {
        // 답변 처음 저장 하면 followUpAnswerRevision + 1
        // 같은 답은 증가 x
        this.followUpAnswerRevision++;
        this.updatedAt = updatedAt;
    }

    public void moveToSummary(
            OffsetDateTime updatedAt
    ) {
        this.currentStep =
                ConsultationStep.SUMMARY;

        this.updatedAt =
                updatedAt;
    }

    public void moveToAnalysis(
            OffsetDateTime updatedAt
    ) {
        this.currentStep =
                ConsultationStep.ANALYSIS;

        this.updatedAt =
                updatedAt;
    }

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    protected Consultation() {
    }

    public Consultation(
            GuestSession guestSession,
            OffsetDateTime createdAt
    ) {
        this.guestSession = guestSession;
        // 새로운 Consultation의 status와 currentStep을 보장하게 함
        this.status = ConsultationStatus.IN_PROGRESS;
        this.currentStep = ConsultationStep.CATEGORY;
        
        this.caseInputRevision = 0L;
        this.followUpAnswerRevision = 0L;
        
        this.createdAt = createdAt;
        this.updatedAt = createdAt;
    }

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

    public void updateCategory(
            ConsultationCategory category,
            OffsetDateTime updatedAt
    ) {
        // Category는 enum 값이기 때문에 비교 연산자 사용
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
        // Situation은 문자열이기 때문에 equals 사용
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

    private void markCaseInputChanged() {
        this.caseInputRevision++;

        this.followUpAnswerRevision = 0L;
    }
}