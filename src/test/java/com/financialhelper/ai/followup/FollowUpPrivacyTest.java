package com.financialhelper.ai.followup;

import com.financialhelper.consultation.*;
import com.financialhelper.guest.GuestSession;
import com.financialhelper.guest.GuestSessionService;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class FollowUpPrivacyTest {
    @Test
    void rejectsSensitiveAnswerBeforeQuestionOrRevisionChanges() {
        var consultations = mock(ConsultationRepository.class);
        var sessions = mock(GuestSessionService.class);
        var questions = mock(FollowUpQuestionRepository.class);
        var guest = mock(GuestSession.class);
        var consultation = mock(Consultation.class);
        var question = mock(FollowUpQuestion.class);
        var consultationId = UUID.randomUUID();
        var questionId = UUID.randomUUID();
        var guestId = UUID.randomUUID();
        when(sessions.requireValidSession("session")).thenReturn(guest);
        when(guest.getId()).thenReturn(guestId);
        when(consultations.findByIdAndGuestSession_Id(consultationId, guestId))
                .thenReturn(Optional.of(consultation));
        when(consultation.getStatus()).thenReturn(ConsultationStatus.IN_PROGRESS);
        when(consultation.getCurrentStep()).thenReturn(ConsultationStep.FOLLOW_UP);
        when(consultation.getId()).thenReturn(consultationId);
        when(consultation.getCaseInputRevision()).thenReturn(3L);
        when(questions.findByIdAndConsultation_IdAndCaseInputRevisionAndSupersededAtIsNull(
                questionId, consultationId, 3L)).thenReturn(Optional.of(question));
        when(questions.findByConsultation_IdAndCaseInputRevisionAndSupersededAtIsNullOrderBySequenceNoAsc(
                consultationId, 3L)).thenReturn(List.of(question));
        when(question.getSequenceNo()).thenReturn(1);
        var service = new FollowUpPersistenceService(consultations, sessions, questions,
                JsonMapper.builder().build(), Clock.systemUTC());

        assertThatThrownBy(() -> service.saveAnswer(consultationId, questionId, "session",
                "인증번호는 123456입니다"))
                .isInstanceOf(UnsafeConsultationInputException.class);
        verify(question, never()).answer(anyString(), anyString(), any());
        verify(consultation, never()).recordFollowUpAnswerChanged(any());
    }
}
