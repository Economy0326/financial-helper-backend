package com.financialhelper.procedure;

import com.financialhelper.retrieval.ConfirmedCaseSnapshot;
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
        name = "financial_action_plan",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_financial_action_plan_revision",
                columnNames = {
                        "consultation_id", "procedure_version_id",
                        "case_input_revision", "follow_up_answer_revision"
                }
        )
)
public class FinancialActionPlan {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "consultation_id", nullable = false)
    private Consultation consultation;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "procedure_version_id", nullable = false)
    private ProcedureVersion procedureVersion;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "confirmed_case_snapshot_id", nullable = false)
    private ConfirmedCaseSnapshot confirmedCaseSnapshot;

    @Column(name = "case_input_revision", nullable = false)
    private long caseInputRevision;

    @Column(name = "follow_up_answer_revision", nullable = false)
    private long followUpAnswerRevision;

    @Column(nullable = false, length = 100)
    private String scenario;

    @Enumerated(EnumType.STRING)
    @Column(name = "plan_status", nullable = false, length = 32)
    private PlanStatus planStatus;

    @Column(name = "plan_json", nullable = false, columnDefinition = "TEXT")
    private String planJson;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    protected FinancialActionPlan() {
    }

    public FinancialActionPlan(
            Consultation consultation,
            ProcedureVersion procedureVersion,
            ConfirmedCaseSnapshot confirmedCaseSnapshot,
            PlanStatus planStatus,
            String planJson,
            OffsetDateTime now
    ) {
        if (consultation == null || procedureVersion == null || confirmedCaseSnapshot == null) {
            throw new IllegalArgumentException("plan relationships must be present");
        }
        if (planStatus == null) {
            throw new IllegalArgumentException("planStatus must be present");
        }
        if (planJson == null || planJson.isBlank()) {
            throw new IllegalArgumentException("planJson must not be blank");
        }
        this.consultation = consultation;
        this.procedureVersion = procedureVersion;
        this.confirmedCaseSnapshot = confirmedCaseSnapshot;
        this.caseInputRevision = confirmedCaseSnapshot.getCaseInputRevision();
        this.followUpAnswerRevision = confirmedCaseSnapshot.getFollowUpAnswerRevision();
        this.scenario = procedureVersion.getScenario();
        this.planStatus = planStatus;
        this.planJson = planJson;
        this.createdAt = now == null ? OffsetDateTime.now() : now;
        this.updatedAt = this.createdAt;
    }

    public UUID getId() { return id; }
    public Consultation getConsultation() { return consultation; }
    public ProcedureVersion getProcedureVersion() { return procedureVersion; }
    public ConfirmedCaseSnapshot getConfirmedCaseSnapshot() { return confirmedCaseSnapshot; }
    public long getCaseInputRevision() { return caseInputRevision; }
    public long getFollowUpAnswerRevision() { return followUpAnswerRevision; }
    public String getScenario() { return scenario; }
    public PlanStatus getPlanStatus() { return planStatus; }
    public String getPlanJson() { return planJson; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
}
