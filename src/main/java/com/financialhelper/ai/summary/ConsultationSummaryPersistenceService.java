package com.financialhelper.ai.summary;

import com.financialhelper.ai.followup.FollowUpQuestion;
import com.financialhelper.ai.followup.FollowUpQuestionRepository;

import com.financialhelper.ai.understanding.AiInputChangedException;

import com.financialhelper.consultation.Consultation;
import com.financialhelper.consultation.ConsultationNotFoundException;
import com.financialhelper.consultation.ConsultationRepository;
import com.financialhelper.consultation.ConsultationStatus;
import com.financialhelper.consultation.ConsultationStep;
import com.financialhelper.consultation.InvalidConsultationStateException;

import com.financialhelper.guest.GuestSession;
import com.financialhelper.guest.GuestSessionService;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class ConsultationSummaryPersistenceService {

    private final ConsultationRepository
            consultationRepository;

    private final GuestSessionService
            guestSessionService;

    private final FollowUpQuestionRepository
            followUpQuestionRepository;

    private final ConsultationSummaryRepository
            consultationSummaryRepository;

    private final JsonMapper jsonMapper;

    public ConsultationSummaryPersistenceService(
            ConsultationRepository consultationRepository,
            GuestSessionService guestSessionService,
            FollowUpQuestionRepository followUpQuestionRepository,
            ConsultationSummaryRepository consultationSummaryRepository,
            JsonMapper jsonMapper
    ) {
        this.consultationRepository =
                consultationRepository;

        this.guestSessionService =
                guestSessionService;

        this.followUpQuestionRepository =
                followUpQuestionRepository;

        this.consultationSummaryRepository =
                consultationSummaryRepository;

        this.jsonMapper =
                jsonMapper;
    }

    @Transactional(readOnly = true)
    public ConsultationSummaryData.Snapshot loadSnapshot(
            UUID consultationId,
            String rawToken
    ) {

        Consultation consultation =
                findOwnedConsultation(
                        consultationId,
                        rawToken
                );

        ensureReadyForSummary(
                consultation
        );

        List<FollowUpQuestion> questions =
                followUpQuestionRepository
                        .findByConsultation_IdAndCaseInputRevisionOrderBySequenceNoAsc(
                                consultation.getId(),
                                consultation.getCaseInputRevision()
                        );

        boolean hasUnansweredQuestion =
                questions.stream()
                        .anyMatch(
                                question ->
                                        !question.isAnswered()
                        );

        if (hasUnansweredQuestion) {
            throw new InvalidConsultationStateException();
        }

        List<ConsultationSummaryData.FollowUpAnswer>
                answers =
                questions.stream()
                        .map(
                                question ->
                                        new ConsultationSummaryData.FollowUpAnswer(
                                                question.getSequenceNo(),
                                                question.getQuestionText(),
                                                question.getAnswerValue(),
                                                question.getAnswerLabel()
                                        )
                        )
                        .toList();

        GuestSession guestSession =
                guestSessionService
                        .requireValidSession(
                                rawToken
                        );

        return new ConsultationSummaryData.Snapshot(
                consultation.getId(),
                guestSession.getId(),
                consultation.getCategory(),
                consultation.getSituationText(),
                consultation.getCaseInputRevision(),
                consultation.getFollowUpAnswerRevision(),
                answers
        );
    }

    @Transactional(readOnly = true)
    public Optional<ConsultationSummaryData.Document>
    findExisting(
            ConsultationSummaryData.Snapshot snapshot
    ) {

        return consultationSummaryRepository
                .findByConsultation_IdAndCaseInputRevisionAndFollowUpAnswerRevision(
                        snapshot.consultationId(),
                        snapshot.caseInputRevision(),
                        snapshot.followUpAnswerRevision()
                )
                .map(this::toDocument);
    }

    @Transactional
    public ConsultationSummaryData.Document saveIfCurrent(
            ConsultationSummaryData.Snapshot snapshot,
            ConsultationSummaryAiResult result,
            String model
    ) {

        Consultation consultation =
                consultationRepository
                        .findByIdAndGuestSession_Id(
                                snapshot.consultationId(),
                                snapshot.guestSessionId()
                        )
                        .orElseThrow(
                                ConsultationNotFoundException::new
                        );

        ensureReadyForSummary(
                consultation
        );

        if (
                consultation.getCaseInputRevision()
                        != snapshot.caseInputRevision()
                || consultation.getFollowUpAnswerRevision()
                        != snapshot.followUpAnswerRevision()
        ) {
            throw new AiInputChangedException();
        }

        Optional<ConsultationSummary> existing =
                consultationSummaryRepository
                        .findByConsultation_IdAndCaseInputRevisionAndFollowUpAnswerRevision(
                                consultation.getId(),
                                consultation.getCaseInputRevision(),
                                consultation.getFollowUpAnswerRevision()
                        );

        // 현재 case revision + follow revision으로 만들어진 summary가 DB에 이미 있을 경우
        if (existing.isPresent()) {
            // 이미 존재하는 summary를 그대로 반환
            return toDocument(
                    existing.get()
            );
        }

        ConsultationSummary summary =
                new ConsultationSummary(
                        consultation,
                        consultation.getCaseInputRevision(),
                        consultation.getFollowUpAnswerRevision(),
                        model,
                        serialize(result),
                        OffsetDateTime.now(
                                ZoneOffset.UTC
                        )
                );

        ConsultationSummary saved =
                consultationSummaryRepository
                        .save(summary);

        return toDocument(saved);
    }

    @Transactional(readOnly = true)
    public ConsultationSummaryStateResponse getState(
            UUID consultationId,
            String rawToken
    ) {

        Consultation consultation =
                findOwnedConsultation(
                        consultationId,
                        rawToken
                );

        ensureReadyForSummary(
                consultation
        );

        return consultationSummaryRepository
                .findByConsultation_IdAndCaseInputRevisionAndFollowUpAnswerRevision(
                        consultation.getId(),
                        consultation.getCaseInputRevision(),
                        consultation.getFollowUpAnswerRevision()
                )
                .map(this::toDocument)
                .map(ConsultationSummaryStateResponse::ready)
                .orElseGet(
                        ConsultationSummaryStateResponse::notPrepared
                );
    }

    @Transactional
    public ConfirmConsultationSummaryResponse confirm(
            UUID consultationId,
            String rawToken
    ) {

        Consultation consultation =
                findOwnedConsultation(
                        consultationId,
                        rawToken
                );

        ensureReadyForSummary(
                consultation
        );

        ConsultationSummary summary =
                consultationSummaryRepository
                        .findByConsultation_IdAndCaseInputRevisionAndFollowUpAnswerRevision(
                                consultation.getId(),
                                consultation.getCaseInputRevision(),
                                consultation.getFollowUpAnswerRevision()
                        )
                        .orElseThrow(
                                ConsultationSummaryNotFoundException::new
                        );

        OffsetDateTime now =
                OffsetDateTime.now(
                        ZoneOffset.UTC
                );

        // Summary가 사용자에게 확인되었음을 기록
        summary.confirm(now);
        // 상담 단계를 ANALYSIS로 변경
        consultation.moveToAnalysis(now);

        return new ConfirmConsultationSummaryResponse(
                consultation.getId(),
                "ANALYSIS"
        );
    }

    private Consultation findOwnedConsultation(
            UUID consultationId,
            String rawToken
    ) {

        GuestSession guestSession =
                guestSessionService
                        .requireValidSession(
                                rawToken
                        );

        return consultationRepository
                .findByIdAndGuestSession_Id(
                        consultationId,
                        guestSession.getId()
                )
                .orElseThrow(
                        ConsultationNotFoundException::new
                );
    }

    private void ensureReadyForSummary(
            Consultation consultation
    ) {

        if (
                consultation.getStatus()
                        != ConsultationStatus.IN_PROGRESS
        ) {
            throw new InvalidConsultationStateException();
        }

        if (
                consultation.getCurrentStep()
                        != ConsultationStep.SUMMARY
        ) {
            throw new InvalidConsultationStateException();
        }

        if (
                consultation.getCategory() == null
                || consultation.getSituationText() == null
                || consultation.getSituationText().isBlank()
        ) {
            throw new InvalidConsultationStateException();
        }
    }

    private String serialize(
            ConsultationSummaryAiResult result
    ) {

        try {
            return jsonMapper.writeValueAsString(
                    result
            );
        } catch (JacksonException exception) {
            throw new IllegalStateException(
                    "Failed to serialize consultation summary"
            );
        }
    }

    private ConsultationSummaryAiResult deserialize(
            String resultJson
    ) {

        try {
            return jsonMapper.readValue(
                    resultJson,
                    ConsultationSummaryAiResult.class
            );
        } catch (JacksonException exception) {
            throw new IllegalStateException(
                    "Failed to deserialize consultation summary"
            );
        }
    }

    private ConsultationSummaryData.Document toDocument(
            ConsultationSummary summary
    ) {

        return new ConsultationSummaryData.Document(
                summary.getConsultation().getId(),
                summary.getCaseInputRevision(),
                summary.getFollowUpAnswerRevision(),
                summary.getModel(),
                deserialize(summary.getResultJson()),
                summary.getGeneratedAt(),
                summary.getConfirmedAt()
        );
    }
}