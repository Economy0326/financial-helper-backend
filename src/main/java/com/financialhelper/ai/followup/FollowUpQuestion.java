package com.financialhelper.ai.followup;

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
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(
        name = "follow_up_question",
        // Id + Revision + Sequence 조합이 유일해야 함
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_follow_up_question_revision_sequence",
                        columnNames = {
                                "consultation_id",
                                "case_input_revision",
                                "sequence_no"
                        }
                )
        }
)
public class FollowUpQuestion {

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
            name = "sequence_no",
            nullable = false
    )
    private int sequenceNo;

    @Column(
            name = "question_text",
            nullable = false,
            length = 300
    )
    private String questionText;

    @Column(
            name = "description",
            nullable = false,
            length = 500
    )
    private String description;

    @Column(
            name = "options_json",
            nullable = false,
            columnDefinition = "TEXT"
    )
    private String optionsJson;

    @Column(
            name = "model",
            nullable = false,
            length = 100
    )
    private String model;

    @Column(
            name = "answer_value",
            length = 64
    )
    private String answerValue;

    @Column(
            name = "answer_label",
            length = 200
    )
    private String answerLabel;

    @Column(
            name = "generated_at",
            nullable = false
    )
    private OffsetDateTime generatedAt;

    @Column(
            name = "answered_at"
    )
    private OffsetDateTime answeredAt;

    // protected -> JPA가 엔티티 생성가능하게, 일반 생성자 사용 금지
    protected FollowUpQuestion() {
    }

    public FollowUpQuestion(
            Consultation consultation,
            long caseInputRevision,
            int sequenceNo,
            String questionText,
            String description,
            String optionsJson,
            String model,
            OffsetDateTime generatedAt
    ) {
        this.consultation =
                consultation;

        this.caseInputRevision =
                caseInputRevision;

        this.sequenceNo =
                sequenceNo;

        this.questionText =
                questionText;

        this.description =
                description;

        this.optionsJson =
                optionsJson;

        this.model =
                model;

        this.generatedAt =
                generatedAt;
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

    public int getSequenceNo() {
        return sequenceNo;
    }

    public String getQuestionText() {
        return questionText;
    }

    public String getDescription() {
        return description;
    }

    public String getOptionsJson() {
        return optionsJson;
    }

    public String getModel() {
        return model;
    }

    public String getAnswerValue() {
        return answerValue;
    }

    public String getAnswerLabel() {
        return answerLabel;
    }

    public OffsetDateTime getGeneratedAt() {
        return generatedAt;
    }

    public OffsetDateTime getAnsweredAt() {
        return answeredAt;
    }

    public boolean isAnswered() {
        return answerValue != null;
    }

    public boolean answer(
            String value,
            String label,
            OffsetDateTime answeredAt
    ) {

        boolean changed =
                !Objects.equals(
                        this.answerValue,
                        value
                );

        this.answerValue = value;
        this.answerLabel = label;

        if (changed) {
            this.answeredAt =
                    answeredAt;
        }

        return changed;
    }
}