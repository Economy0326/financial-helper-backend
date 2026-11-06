package com.financialhelper.ai.information;

import com.financialhelper.ai.followup.FollowUpQuestion;
import com.financialhelper.ai.followup.FollowUpQuestionRepository;
import com.financialhelper.consultation.Consultation;
import com.financialhelper.consultation.ConsultationCategory;
import com.financialhelper.consultation.ConsultationRepository;
import com.financialhelper.guest.GuestSession;
import com.financialhelper.guest.GuestSessionService;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class InformationConfirmedFactsServiceTest {
    private final GuestSessionService sessions = mock(GuestSessionService.class);
    private final ConsultationRepository consultations = mock(ConsultationRepository.class);
    private final FollowUpQuestionRepository questions = mock(FollowUpQuestionRepository.class);
    private final InformationConfirmedFactsService service = new InformationConfirmedFactsService(
            sessions, consultations, questions, JsonMapper.builder().build());
    private final UUID id = UUID.randomUUID();

    @Test
    void excludesVersionBoundAnswersFromInformationInput() {
        var session = mock(GuestSession.class);
        var consultation = mock(Consultation.class);
        var versionBound = mock(FollowUpQuestion.class);
        when(sessions.requireValidSession("token")).thenReturn(session);
        when(session.getId()).thenReturn(UUID.randomUUID());
        when(consultations.findByIdAndGuestSession_Id(id, session.getId()))
                .thenReturn(Optional.of(consultation));
        when(consultation.getCategory()).thenReturn(ConsultationCategory.CARD);
        when(consultation.getSituationText()).thenReturn("개인 신용카드 분실");
        when(consultation.getCaseInputRevision()).thenReturn(2L);
        when(consultation.getFollowUpAnswerRevision()).thenReturn(3L);
        when(questions.findByConsultation_IdAndCaseInputRevisionAndSupersededAtIsNullOrderBySequenceNoAsc(id, 2))
                .thenReturn(List.of(versionBound));
        when(versionBound.getQuestionScope()).thenReturn("VERSION_BOUND");
        var result = service.current(id, "token");
        assertThat(result.facts()).extracting(fact -> fact.key())
                .containsExactly("category", "scenario", "situationText");
        assertThat(result.caseInputRevision()).isEqualTo(2);
    }

    @Test
    void ownershipFailureStopsBeforeReadingQuestions() {
        var session = mock(GuestSession.class);
        when(sessions.requireValidSession("token")).thenReturn(session);
        when(session.getId()).thenReturn(UUID.randomUUID());
        when(consultations.findByIdAndGuestSession_Id(id, session.getId())).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.current(id, "token"))
                .isInstanceOf(com.financialhelper.consultation.ConsultationNotFoundException.class);
        verifyNoInteractions(questions);
    }
}
