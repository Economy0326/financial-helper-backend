package com.financialhelper.ai.summary;

import com.financialhelper.consultation.Consultation;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
        name = "consultation_summary",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_consultation_summary_revision",
                        columnNames = {
                                "consultation_id",
                                "case_input_revision",
                                "follow_up_answer_revision"
                        }
                )
        }
)
public class ConsultationSummary {

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

    @Column(
            name = "model",
            nullable = false,
            length = 100
    )
    private String model;

    @Column(
            name = "result_json",
            nullable = false,
            columnDefinition = "TEXT"
    )
    private String resultJson;

    @Column(
            name = "generated_at",
            nullable = false
    )
    private OffsetDateTime generatedAt;

    @Column(name = "confirmed_at")
    private OffsetDateTime confirmedAt;

    protected ConsultationSummary() {
    }

    public ConsultationSummary(
            Consultation consultation,
            long caseInputRevision,
            long followUpAnswerRevision,
            String model,
            String resultJson,
            OffsetDateTime generatedAt
    ) {
        this.consultation = consultation;
        this.caseInputRevision = caseInputRevision;
        this.followUpAnswerRevision = followUpAnswerRevision;
        this.model = model;
        this.resultJson = resultJson;
        this.generatedAt = generatedAt;
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

    public String getModel() {
        return model;
    }

    public String getResultJson() {
        return resultJson;
    }

    public OffsetDateTime getGeneratedAt() {
        return generatedAt;
    }

    public OffsetDateTime getConfirmedAt() {
        return confirmedAt;
    }

    public void confirm(
            OffsetDateTime confirmedAt
    ) {
        if (this.confirmedAt == null) {
            this.confirmedAt = confirmedAt;
        }
    }
}