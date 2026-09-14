package com.financialhelper.retrieval;

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

import com.financialhelper.consultation.Consultation;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(
        name = "confirmed_case_snapshot",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_confirmed_case_snapshot_revision",
                columnNames = {
                        "consultation_id",
                        "case_input_revision",
                        "follow_up_answer_revision"
                }
        )
)
public class ConfirmedCaseSnapshot {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "consultation_id", nullable = false)
    private Consultation consultation;

    @Column(name = "case_input_revision", nullable = false)
    private long caseInputRevision;

    @Column(name = "follow_up_answer_revision", nullable = false)
    private long followUpAnswerRevision;

    @Column(name = "facts_json", nullable = false, columnDefinition = "TEXT")
    private String factsJson;

    @Column(name = "missing_facts_json", nullable = false, columnDefinition = "TEXT")
    private String missingFactsJson;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    protected ConfirmedCaseSnapshot() {
    }

    public ConfirmedCaseSnapshot(
            Consultation consultation,
            long caseInputRevision,
            long followUpAnswerRevision,
            String factsJson,
            String missingFactsJson,
            OffsetDateTime createdAt
    ) {
        if (consultation == null || consultation.getId() == null) {
            throw new IllegalArgumentException("consultation must be persisted");
        }
        if (caseInputRevision < 0 || followUpAnswerRevision < 0) {
            throw new IllegalArgumentException("revision must be non-negative");
        }
        this.consultation = consultation;
        this.caseInputRevision = caseInputRevision;
        this.followUpAnswerRevision = followUpAnswerRevision;
        this.factsJson = requireJson(factsJson, "factsJson");
        this.missingFactsJson = requireJson(missingFactsJson, "missingFactsJson");
        this.createdAt = createdAt == null ? OffsetDateTime.now() : createdAt;
    }

    private static String requireJson(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    public UUID getId() { return id; }
    public Consultation getConsultation() { return consultation; }
    public long getCaseInputRevision() { return caseInputRevision; }
    public long getFollowUpAnswerRevision() { return followUpAnswerRevision; }
    public String getFactsJson() { return factsJson; }
    public String getMissingFactsJson() { return missingFactsJson; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
}
