package com.financialhelper.retrieval;

import com.financialhelper.ai.followup.FollowUpQuestionRepository;
import com.financialhelper.ai.followup.FollowUpQuestion;
import com.financialhelper.consultation.Consultation;
import com.financialhelper.consultation.ConsultationCategory;
import com.financialhelper.consultation.ConsultationRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class ConfirmedCaseSnapshotServiceTest {
    @Mock private ConsultationRepository consultationRepository;
    @Mock private FollowUpQuestionRepository followUpQuestionRepository;
    @Mock private ConfirmedCaseSnapshotRepository snapshotRepository;
    @Mock private Consultation consultation;

    private ConfirmedCaseSnapshotService service;

    @BeforeEach
    void setUp() {
        service = new ConfirmedCaseSnapshotService(
                consultationRepository, followUpQuestionRepository,
                snapshotRepository, JsonMapper.builder().build());
    }

    @Test
    void captures_only_direct_consultation_inputs_and_is_revision_keyed() {
        UUID consultationId = UUID.randomUUID();
        when(consultation.getId()).thenReturn(consultationId);
        when(consultation.getCaseInputRevision()).thenReturn(2L);
        when(consultation.getFollowUpAnswerRevision()).thenReturn(3L);
        when(consultation.getCategory()).thenReturn(ConsultationCategory.CARD);
        when(consultation.getSituationText()).thenReturn("국내에서 모르는 신용판매 결제");
        when(consultationRepository.findForUpdateById(consultationId))
                .thenReturn(Optional.of(consultation));
        FollowUpQuestion first = new FollowUpQuestion(
                consultation, 2L, 1, "카드 분실 여부", "", "[]", "test",
                OffsetDateTime.now(ZoneOffset.UTC));
        first.answer("UNKNOWN", "모름", OffsetDateTime.now(ZoneOffset.UTC));
        FollowUpQuestion second = new FollowUpQuestion(
                consultation, 2L, 2, "부정사용 여부", "", "[]", "test",
                OffsetDateTime.now(ZoneOffset.UTC));
        second.answer("TRUE", "예", OffsetDateTime.now(ZoneOffset.UTC));
        when(followUpQuestionRepository
                .findByConsultation_IdAndCaseInputRevisionOrderBySequenceNoAsc(consultationId, 2L))
                .thenReturn(List.of(first, second));
        when(snapshotRepository
                .findByConsultation_IdAndCaseInputRevisionAndFollowUpAnswerRevision(
                        consultationId, 2L, 3L))
                .thenReturn(Optional.empty());
        when(snapshotRepository.saveAndFlush(any(ConfirmedCaseSnapshot.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        ConfirmedCaseSnapshotData result = service.capture(consultationId);

        assertThat(result.consultationId()).isEqualTo(consultationId);
        assertThat(result.caseInputRevision()).isEqualTo(2L);
        assertThat(result.facts()).extracting(ConfirmedCaseSnapshotData.Fact::type)
                .containsExactly("CATEGORY", "SITUATION", "FOLLOW_UP", "FOLLOW_UP");
        assertThat(result.facts()).extracting(ConfirmedCaseSnapshotData.Fact::value)
                .contains("UNKNOWN", "TRUE");
        assertThat(result.facts()).noneMatch(fact -> fact.type().contains("AI"));
    }

    @Test
    void corrected_consultation_revision_creates_a_new_snapshot_without_overwriting_previous() {
        UUID consultationId = UUID.randomUUID();
        when(consultation.getId()).thenReturn(consultationId);
        when(consultation.getCaseInputRevision()).thenReturn(1L);
        when(consultation.getFollowUpAnswerRevision()).thenReturn(1L);
        when(consultation.getCategory()).thenReturn(ConsultationCategory.CARD);
        when(consultation.getSituationText()).thenReturn("처음 진술");
        when(consultationRepository.findForUpdateById(consultationId))
                .thenReturn(Optional.of(consultation));
        when(followUpQuestionRepository
                .findByConsultation_IdAndCaseInputRevisionOrderBySequenceNoAsc(consultationId, 1L))
                .thenReturn(List.of());
        when(followUpQuestionRepository
                .findByConsultation_IdAndCaseInputRevisionOrderBySequenceNoAsc(consultationId, 2L))
                .thenReturn(List.of());
        when(snapshotRepository
                .findByConsultation_IdAndCaseInputRevisionAndFollowUpAnswerRevision(
                        consultationId, 1L, 1L))
                .thenReturn(Optional.empty());
        when(snapshotRepository
                .findByConsultation_IdAndCaseInputRevisionAndFollowUpAnswerRevision(
                        consultationId, 2L, 1L))
                .thenReturn(Optional.empty());
        when(snapshotRepository.saveAndFlush(any(ConfirmedCaseSnapshot.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        ConfirmedCaseSnapshotData first = service.capture(consultationId);

        when(consultation.getCaseInputRevision()).thenReturn(2L);
        when(consultation.getSituationText()).thenReturn("수정한 진술");
        ConfirmedCaseSnapshotData corrected = service.capture(consultationId);

        assertThat(first.caseInputRevision()).isEqualTo(1L);
        assertThat(first.facts()).extracting(ConfirmedCaseSnapshotData.Fact::value)
                .contains("처음 진술");
        assertThat(corrected.caseInputRevision()).isEqualTo(2L);
        assertThat(corrected.facts()).extracting(ConfirmedCaseSnapshotData.Fact::value)
                .contains("수정한 진술")
                .doesNotContain("처음 진술");
        verify(snapshotRepository, times(2)).saveAndFlush(any(ConfirmedCaseSnapshot.class));
    }
}
