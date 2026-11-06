package com.financialhelper.ai.information;

import com.financialhelper.consultation.Consultation;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "information_report")
public class InformationReport {
    @Id private UUID id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "consultation_id", nullable = false)
    private Consultation consultation;
    @Column(name = "case_input_revision", nullable = false) private long caseInputRevision;
    @Column(name = "follow_up_answer_revision", nullable = false) private long followUpAnswerRevision;
    @Column(name = "retrieval_generation_id") private UUID retrievalGenerationId;
    @Column(name = "candidates_json", nullable = false, columnDefinition = "TEXT") private String candidatesJson;
    @Column(name = "snapshot_json", nullable = false, columnDefinition = "TEXT") private String snapshotJson;
    @Column(name = "selected_evidence_json", nullable = false, columnDefinition = "TEXT") private String selectedEvidenceJson;
    @Column(name = "explanations_json", columnDefinition = "TEXT") private String explanationsJson;
    @Column(name = "generated_at", nullable = false) private OffsetDateTime generatedAt;

    protected InformationReport() {}

    public InformationReport(Consultation consultation, InformationEvidenceSnapshot snapshot,
            String candidatesJson, String snapshotJson, String selectedEvidenceJson,
            String explanationsJson, OffsetDateTime generatedAt) {
        this.id = UUID.randomUUID();
        this.consultation = consultation;
        this.caseInputRevision = snapshot.caseInputRevision();
        this.followUpAnswerRevision = snapshot.followUpAnswerRevision();
        this.retrievalGenerationId = snapshot.retrievalGenerationId();
        this.candidatesJson = candidatesJson;
        this.snapshotJson = snapshotJson;
        this.selectedEvidenceJson = selectedEvidenceJson;
        this.explanationsJson = explanationsJson;
        this.generatedAt = generatedAt;
    }

    public UUID getId() { return id; }
    public Consultation getConsultation() { return consultation; }
    public long getCaseInputRevision() { return caseInputRevision; }
    public long getFollowUpAnswerRevision() { return followUpAnswerRevision; }
    public UUID getRetrievalGenerationId() { return retrievalGenerationId; }
    public String getCandidatesJson() { return candidatesJson; }
    public String getSnapshotJson() { return snapshotJson; }
    public String getSelectedEvidenceJson() { return selectedEvidenceJson; }
    public String getExplanationsJson() { return explanationsJson; }
    public OffsetDateTime getGeneratedAt() { return generatedAt; }
}
