package com.financialhelper.consultation;

import com.financialhelper.guest.GuestSession;
import com.financialhelper.guest.GuestSessionService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ConsultationServiceTest {

    @Mock
    private ConsultationRepository consultationRepository;

    @Mock
    private GuestSessionService guestSessionService;

    @Mock
    private GuestSession guestSession;

    private ConsultationService consultationService;

    private UUID consultationId;
    private UUID guestSessionId;
    private String rawToken;

    @BeforeEach
    void setUp() {
        consultationService =
                new ConsultationService(
                        consultationRepository,
                        guestSessionService
                );

        consultationId = UUID.randomUUID();
        guestSessionId = UUID.randomUUID();
        rawToken = "raw-test-token";

        when(guestSession.getId())
                .thenReturn(guestSessionId);
    }

    // Category 저장 테스트
    @Test
    void updatesCategoryAndMovesToSituation() {
        OffsetDateTime now =
                OffsetDateTime.now(
                        ZoneOffset.UTC
                );

        Consultation consultation =
                new Consultation(
                        guestSession,
                        now
                );

        when(
                guestSessionService
                        .requireValidSession(rawToken)
        ).thenReturn(guestSession);

        when(
                consultationRepository
                        .findByIdAndGuestSession_Id(
                                consultationId,
                                guestSessionId
                        )
        ).thenReturn(
                Optional.of(consultation)
        );

        UpdateConsultationCategoryResponse response =
                consultationService.updateCategory(
                        consultationId,
                        rawToken,
                        new UpdateConsultationCategoryRequest(
                                ConsultationCategory.INSURANCE
                        )
                );

        assertThat(
                consultation.getCategory()
        ).isEqualTo(
                ConsultationCategory.INSURANCE
        );

        assertThat(
                response.currentStep()
        ).isEqualTo(
                ConsultationStep.SITUATION
        );
    }

    // Situation 저장 테스트
    @Test
    void updatesSituationAndMovesToFollowUp() {
        OffsetDateTime now =
                OffsetDateTime.now(
                        ZoneOffset.UTC
                );

        Consultation consultation =
                new Consultation(
                        guestSession,
                        now
                );

        consultation.updateCategory(
                ConsultationCategory.INSURANCE,
                now
        );

        when(
                guestSessionService
                        .requireValidSession(rawToken)
        ).thenReturn(guestSession);

        when(
                consultationRepository
                        .findByIdAndGuestSession_Id(
                                consultationId,
                                guestSessionId
                        )
        ).thenReturn(
                Optional.of(consultation)
        );

        UpdateConsultationSituationResponse response =
                consultationService.updateSituation(
                        consultationId,
                        rawToken,
                        new UpdateConsultationSituationRequest(
                                "Refund amount seems too small."
                        )
                );

        assertThat(
                consultation.getSituationText()
        ).isEqualTo(
                "Refund amount seems too small."
        );

        assertThat(
                response.currentStep()
        ).isEqualTo(
                ConsultationStep.FOLLOW_UP
        );
    }

    // Ownership 조회 실패
    @Test
    void hidesConsultationWhenOwnershipDoesNotMatch() {
        when(
                guestSessionService
                        .requireValidSession(rawToken)
        ).thenReturn(guestSession);

        when(
                consultationRepository
                        .findByIdAndGuestSession_Id(
                                consultationId,
                                guestSessionId
                        )
        ).thenReturn(
                Optional.empty()
        );

        assertThatThrownBy(() ->
                consultationService.updateCategory(
                        consultationId,
                        rawToken,
                        new UpdateConsultationCategoryRequest(
                                ConsultationCategory.CARD
                        )
                )
        ).isInstanceOf(
                ConsultationNotFoundException.class
        );
    }
}