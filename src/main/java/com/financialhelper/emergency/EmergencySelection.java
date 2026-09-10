package com.financialhelper.emergency;

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
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "emergency_selection")
public class EmergencySelection {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @OneToOne(
            fetch = FetchType.LAZY,
            optional = false
    )
    @JoinColumn(
            name = "guest_session_id",
            nullable = false,
            unique = true
    )
    private GuestSession guestSession;

    @Enumerated(EnumType.STRING)
    @Column(
            name = "selected_type",
            nullable = false,
            length = 32
    )
    private EmergencyType selectedType;

    @Column(
            name = "created_at",
            nullable = false
    )
    private OffsetDateTime createdAt;

    @Column(
            name = "updated_at",
            nullable = false
    )
    private OffsetDateTime updatedAt;

    protected EmergencySelection() {
    }

    public EmergencySelection(
            GuestSession guestSession,
            EmergencyType selectedType,
            OffsetDateTime createdAt
    ) {
        this.guestSession = guestSession;
        this.selectedType = selectedType;
        this.createdAt = createdAt;
        this.updatedAt = createdAt;
    }

    public UUID getId() {
        return id;
    }

    public GuestSession getGuestSession() {
        return guestSession;
    }

    public EmergencyType getSelectedType() {
        return selectedType;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void updateSelectedType(
            EmergencyType selectedType,
            OffsetDateTime updatedAt
    ) {
        this.selectedType = selectedType;
        this.updatedAt = updatedAt;
    }
}