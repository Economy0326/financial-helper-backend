package com.financialhelper.consultation;

import com.financialhelper.guest.GuestSession;
import com.financialhelper.guest.GuestSessionService;
import com.financialhelper.ai.followup.FollowUpQuestionRepository;

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

    @Mock
    private FollowUpQuestionRepository followUpQuestionRepository;

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

        when(guestSessionService.requireValidSession(rawToken)).thenReturn(guestSession);

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

    @Test
    void keepsSelectedScenarioAndReturnsSupportedMismatchSuggestion() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        Consultation consultation = new Consultation(guestSession, now);
        consultation.updateCategory(ConsultationCategory.CARD,
                ConsultationScenario.CARD_LOSS_UNAUTHORIZED_USE, now);
        when(guestSessionService.requireValidSession(rawToken)).thenReturn(guestSession);
        when(consultationRepository.findByIdAndGuestSession_Id(consultationId, guestSessionId))
                .thenReturn(Optional.of(consultation));

        UpdateConsultationSituationResponse response = consultationService.updateSituation(consultationId,
                rawToken, new UpdateConsultationSituationRequest("모르는 사람이 전화로 시키는 대로 돈을 송금했어요."));

        assertThat(response.scenarioAlignment()).isEqualTo(ScenarioAlignment.SUPPORTED_SCENARIO_MISMATCH);
        assertThat(response.selectedScenario()).isEqualTo(ConsultationScenario.CARD_LOSS_UNAUTHORIZED_USE);
        assertThat(response.suggestedScenario()).isEqualTo(ConsultationScenario.VOICE_PHISHING_SUSPICIOUS_TRANSFER);
        assertThat(consultation.getScenario()).isEqualTo(ConsultationScenario.CARD_LOSS_UNAUTHORIZED_USE);
    }

    @Test
    void confirmsOnlyResolverSuggestedScenarioAndAdvancesRevisionOnce() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        Consultation consultation = new Consultation(guestSession, now);
        consultation.updateCategory(ConsultationCategory.CARD,
                ConsultationScenario.CARD_LOSS_UNAUTHORIZED_USE, now);
        consultation.updateSituation("모르는 사람이 전화로 시키는 대로 돈을 송금했어요.", now.plusSeconds(1));
        long expectedRevision = consultation.getCaseInputRevision();
        when(guestSessionService.requireValidSession(rawToken)).thenReturn(guestSession);
        when(consultationRepository.findByIdAndGuestSession_Id(consultationId, guestSessionId))
                .thenReturn(Optional.of(consultation));

        ConfirmSuggestedScenarioResponse response = consultationService.confirmSuggestedScenario(
                consultationId, rawToken, new ConfirmSuggestedScenarioRequest(
                        ConsultationScenario.VOICE_PHISHING_SUSPICIOUS_TRANSFER, expectedRevision));

        assertThat(response.selectedScenario()).isEqualTo(ConsultationScenario.VOICE_PHISHING_SUSPICIOUS_TRANSFER);
        assertThat(response.caseInputRevision()).isEqualTo(expectedRevision + 1);
        assertThat(response.nextStep()).isEqualTo(ConsultationStep.FOLLOW_UP);
        assertThat(consultation.getSituationText()).contains("전화");
        assertThatThrownBy(() -> consultationService.confirmSuggestedScenario(consultationId, rawToken,
                new ConfirmSuggestedScenarioRequest(ConsultationScenario.VOICE_PHISHING_SUSPICIOUS_TRANSFER, expectedRevision)))
                .isInstanceOf(InvalidConsultationStateException.class);
    }

    @Test
    void rejectsArbitraryOrUnknownConfirmWithoutChangingRevision() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        Consultation consultation = new Consultation(guestSession, now);
        consultation.updateCategory(ConsultationCategory.CARD, ConsultationScenario.CARD_LOSS_UNAUTHORIZED_USE, now);
        consultation.updateSituation("모르는 사람이 전화로 시키는 대로 돈을 송금했어요.", now);
        long revision = consultation.getCaseInputRevision();
        when(guestSessionService.requireValidSession(rawToken)).thenReturn(guestSession);
        when(consultationRepository.findByIdAndGuestSession_Id(consultationId, guestSessionId)).thenReturn(Optional.of(consultation));
        assertThatThrownBy(() -> consultationService.confirmSuggestedScenario(consultationId, rawToken,
                new ConfirmSuggestedScenarioRequest(ConsultationScenario.PERSONAL_INFO_SMISHING_MALICIOUS_APP, revision)))
                .isInstanceOf(InvalidConsultationStateException.class);
        assertThat(consultation.getCaseInputRevision()).isEqualTo(revision);
        assertThat(consultation.getScenario()).isEqualTo(ConsultationScenario.CARD_LOSS_UNAUTHORIZED_USE);
    }

    @Test
    void oldRevisionFollowUpIsNotReusedAfterScenarioConfirm() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        Consultation consultation = new Consultation(guestSession, now);
        consultation.updateCategory(ConsultationCategory.CARD, ConsultationScenario.CARD_LOSS_UNAUTHORIZED_USE, now);
        consultation.updateSituation("모르는 사람이 전화로 시키는 대로 돈을 송금했어요.", now);
        long previousRevision = consultation.getCaseInputRevision();
        when(guestSessionService.requireValidSession(rawToken)).thenReturn(guestSession);
        when(consultationRepository.findByIdAndGuestSession_Id(consultationId, guestSessionId)).thenReturn(Optional.of(consultation));
        consultationService.confirmSuggestedScenario(consultationId, rawToken,
                new ConfirmSuggestedScenarioRequest(ConsultationScenario.VOICE_PHISHING_SUSPICIOUS_TRANSFER, previousRevision));

        assertThat(consultation.getCaseInputRevision()).isEqualTo(previousRevision + 1);
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
