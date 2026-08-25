package com.financialhelper.consultation;

import com.financialhelper.guest.GuestSession;
import com.financialhelper.guest.GuestSessionResolution;
import com.financialhelper.guest.GuestSessionService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

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
                                ConsultationStatus.activeStatuses()
                        )
                        .orElseThrow(
                                ConsultationNotFoundException::new
                        );

        return ActiveConsultationResponse.from(
                consultation
        );
    }
}