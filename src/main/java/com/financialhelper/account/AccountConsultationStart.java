package com.financialhelper.account;

import com.financialhelper.consultation.Consultation;
import jakarta.persistence.*;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "account_consultation_start")
public class AccountConsultationStart {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "account_id", nullable = false)
    private Account account;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "consultation_id", nullable = false, unique = true)
    private Consultation consultation;

    @Column(name = "started_at", nullable = false)
    private OffsetDateTime startedAt;

    protected AccountConsultationStart() { }

    public AccountConsultationStart(Account account, Consultation consultation, OffsetDateTime startedAt) {
        this.account = account;
        this.consultation = consultation;
        this.startedAt = startedAt;
    }

    public UUID getId() { return id; }
    public Account getAccount() { return account; }
    public Consultation getConsultation() { return consultation; }
    public OffsetDateTime getStartedAt() { return startedAt; }
}
