package com.financialhelper.retrieval;

import com.financialhelper.account.Account;
import com.financialhelper.consultation.Consultation;
import com.financialhelper.source.RetrievalGeneration;
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
@Table(name = "retrieval_search_context")
public class PersistentSearchContext {
    @Id
    private UUID id;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "consultation_id")
    private Consultation consultation;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "account_id")
    private Account account;
    @Column(name = "case_input_revision", nullable = false)
    private long caseInputRevision;
    @Column(name = "follow_up_answer_revision", nullable = false)
    private long followUpAnswerRevision;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "retrieval_generation_id")
    private RetrievalGeneration retrievalGeneration;
    @Column(name = "response_json", nullable = false, columnDefinition = "TEXT")
    private String responseJson;
    @Column(name = "candidates_json", nullable = false, columnDefinition = "TEXT")
    private String candidatesJson;
    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;
    @Column(name = "expires_at", nullable = false)
    private OffsetDateTime expiresAt;

    protected PersistentSearchContext() { }

    public PersistentSearchContext(UUID id, Consultation consultation, Account account,
                                   long caseInputRevision, long followUpAnswerRevision,
                                   RetrievalGeneration generation, String responseJson,
                                   String candidatesJson, OffsetDateTime createdAt,
                                   OffsetDateTime expiresAt) {
        this.id = id;
        this.consultation = consultation;
        this.account = account;
        this.caseInputRevision = caseInputRevision;
        this.followUpAnswerRevision = followUpAnswerRevision;
        this.retrievalGeneration = generation;
        this.responseJson = responseJson;
        this.candidatesJson = candidatesJson;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
    }

    public UUID getId() { return id; }
    public Consultation getConsultation() { return consultation; }
    public Account getAccount() { return account; }
    public long getCaseInputRevision() { return caseInputRevision; }
    public long getFollowUpAnswerRevision() { return followUpAnswerRevision; }
    public RetrievalGeneration getRetrievalGeneration() { return retrievalGeneration; }
    public String getResponseJson() { return responseJson; }
    public String getCandidatesJson() { return candidatesJson; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getExpiresAt() { return expiresAt; }
}
