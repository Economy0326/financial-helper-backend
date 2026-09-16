package com.financialhelper.emergency;

import com.financialhelper.account.Account;
import jakarta.persistence.*;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "emergency_history")
public class EmergencyHistory {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "account_id", nullable = false)
    private Account account;

    @Enumerated(EnumType.STRING)
    @Column(name = "emergency_type", nullable = false, length = 32)
    private EmergencyType emergencyType;

    @Column(name = "scenario_version", nullable = false, length = 128)
    private String scenarioVersion;

    @Column(name = "viewed_at", nullable = false)
    private OffsetDateTime viewedAt;

    protected EmergencyHistory() { }

    public EmergencyHistory(Account account, EmergencyType emergencyType, String scenarioVersion,
                             OffsetDateTime viewedAt) {
        this.account = account;
        this.emergencyType = emergencyType;
        this.scenarioVersion = scenarioVersion;
        this.viewedAt = viewedAt;
    }

    public UUID getId() { return id; }
    public Account getAccount() { return account; }
    public EmergencyType getEmergencyType() { return emergencyType; }
    public String getScenarioVersion() { return scenarioVersion; }
    public OffsetDateTime getViewedAt() { return viewedAt; }
}
