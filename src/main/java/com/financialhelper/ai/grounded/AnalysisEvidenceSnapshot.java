package com.financialhelper.ai.grounded;

import com.financialhelper.ai.analysis.AnalysisJob;
import com.financialhelper.consultation.Consultation;
import com.financialhelper.procedure.FinancialActionPlan;
import com.financialhelper.procedure.ProcedureVersion;
import com.financialhelper.source.RetrievalGeneration;

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

/** Immutable-at-a-revision evidence boundary used by analysis and report. */
@Entity
@Table(name = "analysis_evidence_snapshot", uniqueConstraints = @UniqueConstraint(
        name = "uk_analysis_evidence_snapshot_job_revision",
        columnNames = {"analysis_job_id", "case_input_revision", "follow_up_answer_revision"}
))
public class AnalysisEvidenceSnapshot {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "consultation_id", nullable = false)
    private Consultation consultation;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "analysis_job_id", nullable = false)
    private AnalysisJob analysisJob;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "procedure_version_id", nullable = false)
    private ProcedureVersion procedureVersion;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "financial_action_plan_id", nullable = false)
    private FinancialActionPlan financialActionPlan;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "retrieval_generation_id", nullable = false)
    private RetrievalGeneration retrievalGeneration;

    @Column(name = "case_input_revision", nullable = false)
    private long caseInputRevision;

    @Column(name = "follow_up_answer_revision", nullable = false)
    private long followUpAnswerRevision;

    @Column(name = "snapshot_revision", nullable = false)
    private int snapshotRevision;

    @Enumerated(EnumType.STRING)
    @Column(name = "snapshot_status", nullable = false, length = 32)
    private AnalysisEvidenceSnapshotStatus status;

    @Column(name = "snapshot_json", nullable = false, columnDefinition = "TEXT")
    private String snapshotJson;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    protected AnalysisEvidenceSnapshot() {
    }

    public AnalysisEvidenceSnapshot(
            Consultation consultation,
            AnalysisJob analysisJob,
            ProcedureVersion procedureVersion,
            FinancialActionPlan financialActionPlan,
            RetrievalGeneration retrievalGeneration,
            long caseInputRevision,
            long followUpAnswerRevision,
            int snapshotRevision,
            AnalysisEvidenceSnapshotStatus status,
            String snapshotJson,
            OffsetDateTime now
    ) {
        if (consultation == null || analysisJob == null || procedureVersion == null
                || financialActionPlan == null || retrievalGeneration == null) {
            throw new IllegalArgumentException("snapshot relationships must be present");
        }
        if (snapshotRevision < 1 || status == null || snapshotJson == null || snapshotJson.isBlank()) {
            throw new IllegalArgumentException("snapshot metadata is invalid");
        }
        this.consultation = consultation;
        this.analysisJob = analysisJob;
        this.procedureVersion = procedureVersion;
        this.financialActionPlan = financialActionPlan;
        this.retrievalGeneration = retrievalGeneration;
        this.caseInputRevision = caseInputRevision;
        this.followUpAnswerRevision = followUpAnswerRevision;
        this.snapshotRevision = snapshotRevision;
        this.status = status;
        this.snapshotJson = snapshotJson;
        this.createdAt = now == null ? OffsetDateTime.now() : now;
        this.updatedAt = this.createdAt;
    }

    public UUID getId() { return id; }
    public Consultation getConsultation() { return consultation; }
    public AnalysisJob getAnalysisJob() { return analysisJob; }
    public ProcedureVersion getProcedureVersion() { return procedureVersion; }
    public FinancialActionPlan getFinancialActionPlan() { return financialActionPlan; }
    public RetrievalGeneration getRetrievalGeneration() { return retrievalGeneration; }
    public long getCaseInputRevision() { return caseInputRevision; }
    public long getFollowUpAnswerRevision() { return followUpAnswerRevision; }
    public int getSnapshotRevision() { return snapshotRevision; }
    public AnalysisEvidenceSnapshotStatus getStatus() { return status; }
    public String getSnapshotJson() { return snapshotJson; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
}
