package com.financialhelper.ai.followup;

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
import java.util.UUID;

@Service
// Persistence => 데이터를 DB에 저장하고 다시 꺼내는 책임
public class FollowUpPersistenceService {

    private final ConsultationRepository
            consultationRepository;

    private final GuestSessionService
            guestSessionService;

    private final FollowUpQuestionRepository
            followUpQuestionRepository;

    private final JsonMapper jsonMapper;

    public FollowUpPersistenceService(
            ConsultationRepository consultationRepository,
            GuestSessionService guestSessionService,
            FollowUpQuestionRepository followUpQuestionRepository,
            JsonMapper jsonMapper
    ) {
        this.consultationRepository =
                consultationRepository;

        this.guestSessionService =
                guestSessionService;

        this.followUpQuestionRepository =
                followUpQuestionRepository;

        this.jsonMapper =
                jsonMapper;
    }

    @Transactional(readOnly = true)
    public FollowUpStateResponse getState(
            UUID consultationId,
            String rawToken,
            Integer questionNumber
    ) {

        Consultation consultation =
                findOwnedConsultation(
                        consultationId,
                        rawToken
                );

        // 상담이 진행 중인지 확인 -> 그 외에는 호출 거부
        ensureInProgress(
                consultation
        );

        // Follow-up을 이미 모두 완료한 상담은
        // 질문을 다시 보여주지 않고 complete 상태를 반환
        if (
                consultation.getCurrentStep()
                        == ConsultationStep.SUMMARY
        ) {
            return FollowUpStateResponse
                    .complete();
        }

        // Follow-up 단계인지 확인 -> 그 외에는 호출 거부
        ensureFollowUpStep(
                consultation
        );

        List<FollowUpQuestion> questions =
                currentQuestions(
                        consultation
                );

        // 질문이 없을 때 -> Follow-up 질문이 아직 준비되지 않은 상태(ex, AI 호출 실패)
        // 추가 질문이 필요하지 않은 경우에도 AI 호출을 통해 빈 질문 리스트를 반환하도록 구현되어 있으므로
        // 질문이 없다는 것은 Follow-up 질문이 아직 준비되지 않은 상태임
        if (questions.isEmpty()) {
            return FollowUpStateResponse
                    .notPrepared();
        }

        FollowUpQuestion target;

        if (questionNumber != null) {

            if (questionNumber <= 0) {
                throw new FollowUpQuestionNotFoundException();
            }

            target =
                    questions.stream()
                            .filter(
                                    question ->
                                            question
                                                    .getSequenceNo()
                                                    == questionNumber
                            )
                            .findFirst()
                            .orElseThrow(
                                    FollowUpQuestionNotFoundException::new
                            );

        } else {

            target =
                    questions.stream()
                            .filter(
                                    question ->
                                            !question.isAnswered()
                            )
                            .findFirst()
                            .orElse(null);

            if (target == null) {
                return FollowUpStateResponse
                        .complete();
            }
        }

        return toResponse(
                target,
                questions.size()
        );
    }

    @Transactional
    public FollowUpStateResponse saveQuestionsIfCurrent(
            UUID consultationId,
            String rawToken,
            long expectedCaseInputRevision,
            FollowUpQuestionAiResult aiResult,
            String model
    ) {

        Consultation consultation =
                findOwnedConsultation(
                        consultationId,
                        rawToken
                );

        ensureInProgress(
                consultation
        );

        ensureFollowUpStep(
                consultation
        );

        // Case Input Revision이 변경되었는지 확인
        // AI가 읽은 revision과 현재 DB revision이 다르면 오래된 질문을 DB에 저장하지 않는다
        if (
                consultation.getCaseInputRevision()
                        != expectedCaseInputRevision
        ) {
            throw new AiInputChangedException();
        }

        List<FollowUpQuestion> existing =
                currentQuestions(
                        consultation
                );

        if (!existing.isEmpty()) {
            return toResponse(
                    existing.getFirst(),
                    existing.size()
            );
        }

        OffsetDateTime now =
                OffsetDateTime.now(
                        ZoneOffset.UTC
                );

        List<FollowUpQuestion> questions =
                java.util.stream.IntStream
                        .range(
                                0,
                                aiResult.questions.size()
                        )
                        .mapToObj(index -> {

                            FollowUpQuestionAiResult.Question
                                    question =
                                    aiResult.questions
                                            .get(index);

                            String optionsJson =
                                    serializeOptions(
                                            question.options
                                    );

                            return new FollowUpQuestion(
                                    consultation,
                                    expectedCaseInputRevision,
                                    index + 1,
                                    question.question,
                                    question.description,
                                    optionsJson,
                                    model,
                                    now
                            );
                        })
                        .toList();

        List<FollowUpQuestion> saved =
                followUpQuestionRepository
                        .saveAll(
                                questions
                        );

        return toResponse(
                saved.getFirst(),
                saved.size()
        );
    }

    // 추가 질문 없이 Follow-up을 완료 처리하는 경우
    @Transactional
    public FollowUpStateResponse completeWithoutQuestions(
            UUID consultationId,
            String rawToken,
            long expectedCaseInputRevision
    ) {

        Consultation consultation =
                findOwnedConsultation(
                        consultationId,
                        rawToken
                );

        ensureInProgress(
                consultation
        );

        ensureFollowUpStep(
                consultation
        );

        if (
                consultation.getCaseInputRevision()
                        != expectedCaseInputRevision
        ) {
            throw new AiInputChangedException();
        }

        consultation.moveToSummary(
                OffsetDateTime.now(
                        ZoneOffset.UTC
                )
        );

        return FollowUpStateResponse
                .complete();
    }

    // 답변을 저장하는 메서드
    @Transactional
    public FollowUpStateResponse saveAnswer(
            UUID consultationId,
            UUID questionId,
            String rawToken,
            String answerValue
    ) {

        Consultation consultation =
                findOwnedConsultation(
                        consultationId,
                        rawToken
                );

        ensureInProgress(
                consultation
        );

        ensureFollowUpStep(
                consultation
        );

        FollowUpQuestion question =
                followUpQuestionRepository
                        .findByIdAndConsultation_IdAndCaseInputRevision(
                                questionId,
                                consultation.getId(),
                                consultation
                                        .getCaseInputRevision()
                        )
                        .orElseThrow(
                                FollowUpQuestionNotFoundException::new
                        );

        List<FollowUpQuestion> questions =
                currentQuestions(
                        consultation
                );

        // API를 직접 호출해 뒤 질문부터 답하는것을 막는다
        boolean hasUnansweredPreviousQuestion =
                questions.stream()
                        .filter(
                                // candidate -> 전체 질문 중 하나씩
                                candidate ->
                                        candidate
                                                .getSequenceNo()
                                                // question -> 지금 사용자가 답하려고 하는 대상 질문
                                                < question
                                                .getSequenceNo()
                        )
                        .anyMatch(
                                candidate ->
                                        !candidate.isAnswered()
                        );

        if (hasUnansweredPreviousQuestion) {
            throw new InvalidConsultationStateException();
        }

        FollowUpStateResponse.Option selected =
                findSelectedOption(
                        question,
                        answerValue
                );

        OffsetDateTime now =
                OffsetDateTime.now(
                        ZoneOffset.UTC
                );

        boolean changed =
                question.answer(
                        selected.value(),
                        selected.label(),
                        now
                );

        if (changed) {
            consultation
                    .recordFollowUpAnswerChanged(
                            now
                    );
        }

        // 다음 질문이 있으면 다음 질문을 반환하고, 
        // 없으면 Follow-up 완료 상태를 반환
        FollowUpQuestion nextQuestion =
                questions.stream()
                        .filter(
                                candidate ->
                                        !candidate.isAnswered()
                        )
                        .findFirst()
                        .orElse(null);

        if (nextQuestion == null) {

            consultation.moveToSummary(
                    now
            );

            return FollowUpStateResponse
                    .complete();
        }

        return toResponse(
                nextQuestion,
                questions.size()
        );
    }

    // readOptions로 가져오고, filter로 사용자가 고른 Option 하나를 반환
    private FollowUpStateResponse.Option findSelectedOption(
            FollowUpQuestion question,
            String answerValue
    ) {

        return readOptions(question)
                .stream()
                .filter(
                        option ->
                                option.value()
                                        .equals(
                                                answerValue
                                        )
                )
                .findFirst()
                .orElseThrow(
                        InvalidFollowUpAnswerException::new
                );
    }

    // 전체 옵션을 가져옴
    private List<FollowUpStateResponse.Option> readOptions(
            FollowUpQuestion question
    ) {

        try {
            FollowUpOptionsPayload payload =
                    jsonMapper.readValue(
                            question.getOptionsJson(),
                            FollowUpOptionsPayload.class
                    );

            return payload.options()
                    .stream()
                    .map(
                            option ->
                                    new FollowUpStateResponse
                                            .Option(
                                                    option.value(),
                                                    option.label(),
                                                    option.description()
                                            )
                    )
                    .toList();

        } catch (JacksonException exception) {

            // DB JSON 자체를 Exception message에 포함하지 않는다.
            throw new IllegalStateException(
                    "Failed to deserialize follow-up options"
            );
        }
    }

    // serializeOptions => 객체를 JSON 문자열로 변환
    private String serializeOptions(
            List<FollowUpQuestionAiResult.Option> options
    ) {

        try {
            return jsonMapper
                    .writeValueAsString(
                            // PayLoad => AI Option DTO → 필요한 값만 JSON 저장용 Payload 변환
                            FollowUpOptionsPayload
                                    .from(options)
                    );

        } catch (JacksonException exception) {

            throw new IllegalStateException(
                    "Failed to serialize follow-up options"
            );
        }
    }

    private FollowUpStateResponse toResponse(
            FollowUpQuestion question,
            int totalQuestions
    ) {

        return FollowUpStateResponse
                .question(
                        question,
                        readOptions(question),
                        totalQuestions
                );
    }

    private List<FollowUpQuestion> currentQuestions(
            Consultation consultation
    ) {

        return followUpQuestionRepository
                .findByConsultation_IdAndCaseInputRevisionOrderBySequenceNoAsc(
                        consultation.getId(),
                        consultation
                                .getCaseInputRevision()
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

    private void ensureInProgress(
            Consultation consultation
    ) {

        if (
                consultation.getStatus()
                        != ConsultationStatus.IN_PROGRESS
        ) {
            throw new InvalidConsultationStateException();
        }
    }

    private void ensureFollowUpStep(
            Consultation consultation
    ) {

        if (
                consultation.getCurrentStep()
                        != ConsultationStep.FOLLOW_UP
        ) {
            throw new InvalidConsultationStateException();
        }
    }
}