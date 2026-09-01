package com.financialhelper.ai.understanding;

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
import java.util.Optional;
import java.util.UUID;

@Service

// DB 관련 Transaction 담당
public class CaseUnderstandingPersistenceService {

    private final ConsultationRepository consultationRepository;

    private final GuestSessionService guestSessionService;

    private final CaseUnderstandingRepository
            caseUnderstandingRepository;

    private final JsonMapper jsonMapper;

    public CaseUnderstandingPersistenceService(
            ConsultationRepository consultationRepository,
            GuestSessionService guestSessionService,
            CaseUnderstandingRepository caseUnderstandingRepository,
            JsonMapper jsonMapper
    ) {
        this.consultationRepository =
                consultationRepository;

        this.guestSessionService =
                guestSessionService;

        this.caseUnderstandingRepository =
                caseUnderstandingRepository;

        this.jsonMapper =
                jsonMapper;
    }

    // 현재 상담 데이터 읽기
    @Transactional(readOnly = true)
    public CaseUnderstandingData.Snapshot loadSnapshot(
            UUID consultationId,
            String rawToken
    ) {

        GuestSession guestSession =
                guestSessionService
                        .requireValidSession(rawToken);

        Consultation consultation =
                consultationRepository
                        .findByIdAndGuestSession_Id(
                                consultationId,
                                guestSession.getId()
                        )
                        .orElseThrow(
                                ConsultationNotFoundException::new
                        );

        ensureReadyForUnderstanding(
                consultation
        );

        return new CaseUnderstandingData.Snapshot(
                consultation.getId(),
                guestSession.getId(),
                consultation.getCategory(),
                consultation.getSituationText(),
                consultation.getCaseInputRevision()
        );
    }

    // 같은 revision AI 결과 있는지 확인
    @Transactional(readOnly = true)
    public Optional<CaseUnderstandingData.Document>
    findExisting(
            CaseUnderstandingData.Snapshot snapshot
    ) {

        return caseUnderstandingRepository
                .findByConsultation_IdAndCaseInputRevision(
                        snapshot.consultationId(),
                        snapshot.caseInputRevision()
                )
                .map(this::toDocument);
    }

    // AI 호출 중 입력이 안 바뀌었는지 재확인 후 저장
    @Transactional
    public CaseUnderstandingData.Document saveIfCurrent(
            CaseUnderstandingData.Snapshot snapshot,
            CaseUnderstandingAiResult result,
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

        // OpenAI가 응답하는 동안 사용자가 Situation을 수정했다면
        // 이전 Revision 결과를 저장하면 안 됨
        if (
                consultation.getCaseInputRevision()
                        != snapshot.caseInputRevision()
        ) {
            throw new AiInputChangedException();
        }

        ensureReadyForUnderstanding(
                consultation
        );

        // 동시에 같은 Revision 결과를 저장하려는 다른 요청이 있을 수 있음
        // 그럴 경우를 대비해 저장 전 한 번 더 확인해서 중복 방지
        Optional<CaseUnderstanding> existing =
                caseUnderstandingRepository
                        .findByConsultation_IdAndCaseInputRevision(
                                consultation.getId(),
                                consultation.getCaseInputRevision()
                        );

        if (existing.isPresent()) {
            return toDocument(
                    existing.get()
            );
        }

        String resultJson =
                serialize(result);

        CaseUnderstanding understanding =
                new CaseUnderstanding(
                        consultation,
                        consultation.getCaseInputRevision(),
                        model,
                        resultJson,
                        OffsetDateTime.now(
                                ZoneOffset.UTC
                        )
                );

        CaseUnderstanding saved =
                caseUnderstandingRepository
                        .save(understanding);

        return toDocument(saved);
    }

    private void ensureReadyForUnderstanding(
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
                        != ConsultationStep.FOLLOW_UP
        ) {
            throw new InvalidConsultationStateException();
        }

        if (
                consultation.getCategory() == null
                        || consultation.getSituationText() == null
                        || consultation
                        .getSituationText()
                        .isBlank()
        ) {
            throw new InvalidConsultationStateException();
        }
    }

    // Java 객체 => JSON String
    private String serialize(
            CaseUnderstandingAiResult result
    ) {

        try {
            return jsonMapper
                    .writeValueAsString(result);

        } catch (JacksonException exception) {
            throw new IllegalStateException(
                    "Failed to serialize case understanding result"
            );
        }
    }

    // JSON String => Java 객체
    private CaseUnderstandingAiResult deserialize(
            String resultJson
    ) {

        try {
            return jsonMapper.readValue(
                    resultJson,
                    CaseUnderstandingAiResult.class
            );

        } catch (JacksonException exception) {

            /*
             * 실제 JSON 내용을 Exception에 포함하지 않는다.
             */
            throw new IllegalStateException(
                    "Failed to deserialize case understanding result"
            );
        }
    }

    // JPA Entity => Service에서 사용하는 Document DTO
    private CaseUnderstandingData.Document toDocument(
            CaseUnderstanding understanding
    ) {

        return new CaseUnderstandingData.Document(
                understanding
                        .getConsultation()
                        .getId(),

                understanding
                        .getCaseInputRevision(),

                understanding
                        .getModel(),

                deserialize(
                        understanding
                                .getResultJson()
                ),

                understanding
                        .getGeneratedAt()
        );
    }
}