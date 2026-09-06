package com.financialhelper.consultation;

import com.financialhelper.guest.GuestSession;
import com.financialhelper.guest.GuestSessionResolution;
import com.financialhelper.guest.GuestSessionService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;

@Service
public class ConsultationService {

    private final ConsultationRepository consultationRepository;
    private final GuestSessionService guestSessionService;

    public ConsultationService(
            ConsultationRepository consultationRepository,
            GuestSessionService guestSessionService
    ) {
        this.consultationRepository = consultationRepository;
        this.guestSessionService = guestSessionService;
    }

    private static final Set<ConsultationStep>
        CATEGORY_EDITABLE_STEPS =
        EnumSet.of(
                ConsultationStep.CATEGORY,
                ConsultationStep.SITUATION
        );

    private static final Set<ConsultationStep>
        SITUATION_EDITABLE_STEPS =
        EnumSet.of(
                ConsultationStep.SITUATION,
                ConsultationStep.FOLLOW_UP,
                // 요약 수정 => Situation에서 진행
                ConsultationStep.SUMMARY
        );

    // 새로운 상담 생성
    @Transactional
    public ConsultationStartResult startConsultation(
            String rawToken
    ) {
        GuestSessionResolution sessionResolution =
                guestSessionService
                        .resolveOrCreateForConsultation(
                                rawToken
                        );

        GuestSession guestSession =
                sessionResolution.getGuestSession();

        Optional<Consultation> activeConsultation =
                consultationRepository
                        .findFirstByGuestSession_IdAndStatusInOrderByUpdatedAtDesc(
                                guestSession.getId(),
                                ConsultationStatus.activeStatuses()
                        );

        if (activeConsultation.isPresent()) {
            return new ConsultationStartResult(
                    ConsultationCreateResponse.from(
                            activeConsultation.get()
                    ),
                    sessionResolution
                            .getRawTokenToSet()
                            .orElse(null)
            );
        }

        OffsetDateTime now =
                OffsetDateTime.now(ZoneOffset.UTC);

        Consultation consultation =
                new Consultation(
                        guestSession,
                        now
                );

        Consultation savedConsultation =
                consultationRepository.save(
                        consultation
                );

        return new ConsultationStartResult(
                ConsultationCreateResponse.from(
                        savedConsultation
                ),
                sessionResolution
                        .getRawTokenToSet()
                        .orElse(null)
        );
    }

    // 이어서하기 조회
    @Transactional(readOnly = true)
    public ActiveConsultationResponse getActiveConsultation(
            String rawToken
    ) {
        GuestSession guestSession =
                guestSessionService
                        .requireValidSession(rawToken);

        Consultation consultation =
                consultationRepository
                        .findFirstByGuestSession_IdAndStatusInOrderByUpdatedAtDesc(
                                guestSession.getId(),
                                ConsultationStatus.resumableStatuses()
                        )
                        .orElseThrow(
                                ConsultationNotFoundException::new
                        );

        return ActiveConsultationResponse.from(
                consultation
        );
    }

    // Consultation 상세 조회
    @Transactional(readOnly = true)
    public ConsultationDetailResponse getConsultation(
            UUID consultationId,
            String rawToken
    ) {
        GuestSession guestSession =
                guestSessionService.requireValidSession(
                        rawToken
                );

        Consultation consultation =
                findOwnedConsultation(
                        consultationId,
                        guestSession
                );

        return ConsultationDetailResponse.from(
                consultation
        );
    }
    
    // Category
    @Transactional
    public UpdateConsultationCategoryResponse updateCategory(
            UUID consultationId,
            String rawToken,
            UpdateConsultationCategoryRequest request
    ) {
        // 해당 Guest는 유효한가
        GuestSession guestSession =
                guestSessionService
                        .requireValidSession(rawToken);
        
        Consultation consultation =
                findOwnedConsultation(
                        consultationId,
                        guestSession
                );

        ensureInProgress(consultation);

        // 현재 단계에서 Category 수정이 가능한가
        if (!CATEGORY_EDITABLE_STEPS.contains(
                consultation.getCurrentStep()
        )) {
            throw new InvalidConsultationStateException();
        }

        // 실제 카테고리 수정
        // Entity 값을 변경하면 JPA Dirty Checking으로 Transaction 종료 시 DB에 자동 반영된다
        // 그래서 update 후 repository.save()를 다시 호출하지 않는다
        consultation.updateCategory(
                request.category(),
                OffsetDateTime.now(ZoneOffset.UTC)
        );

        return UpdateConsultationCategoryResponse.from(
                consultation
        );
    }

    // Situation
    @Transactional
    public UpdateConsultationSituationResponse updateSituation(
            UUID consultationId,
            String rawToken,
            UpdateConsultationSituationRequest request
    ) {
        GuestSession guestSession =
                guestSessionService
                        .requireValidSession(rawToken);

        Consultation consultation =
                findOwnedConsultation(
                        consultationId,
                        guestSession
                );

        ensureInProgress(consultation);

        if (!SITUATION_EDITABLE_STEPS.contains(
                consultation.getCurrentStep()
        )) {
            throw new InvalidConsultationStateException();
        }

        if (consultation.getCategory() == null) {
            throw new InvalidConsultationStateException();
        }

        consultation.updateSituation(
                request.situationText(),
                OffsetDateTime.now(ZoneOffset.UTC)
        );

        return UpdateConsultationSituationResponse.from(
                consultation
        );
    }

    // 해당 상담이 Guest 소속인가
    private Consultation findOwnedConsultation(
            UUID consultationId,
            GuestSession guestSession
    ) {
        return consultationRepository
                // guestSessionId와 consultationId가 일치해야함
                .findByIdAndGuestSession_Id(
                        consultationId,
                        guestSession.getId()
                )
                .orElseThrow(
                        ConsultationNotFoundException::new
                );
    }

    // 상담이 현재 진행중인가
    private void ensureInProgress(
            Consultation consultation
    ) {
        if (consultation.getStatus()
                != ConsultationStatus.IN_PROGRESS) {

            throw new InvalidConsultationStateException();
        }
    }
}