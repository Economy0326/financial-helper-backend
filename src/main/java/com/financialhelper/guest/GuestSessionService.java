package com.financialhelper.guest;

import com.financialhelper.consultation.ConsultationRepository;
import com.financialhelper.consultation.ConsultationStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

@Service
public class GuestSessionService {

    private final GuestSessionRepository guestSessionRepository;
    private final ConsultationRepository consultationRepository;
    private final GuestSessionTokenService tokenService;
    private final Duration sessionTtl;

    public GuestSessionService(
            GuestSessionRepository guestSessionRepository,
            ConsultationRepository consultationRepository,
            GuestSessionTokenService tokenService,
            @Value("${app.guest-session.ttl}") Duration sessionTtl
    ) {
        this.guestSessionRepository = guestSessionRepository;
        this.consultationRepository = consultationRepository;
        this.tokenService = tokenService;
        this.sessionTtl = sessionTtl;
    }

    @Transactional
    public GuestSessionResolution resolveOrCreateForConsultation(
            String rawToken
    ) {
        if (rawToken == null || rawToken.isBlank()) {
            return createNewSession();
        }

        GuestSession guestSession =
                findValidSessionInternal(rawToken)
                        .orElseThrow(
                                GuestSessionExpiredException::new
                        );

        return GuestSessionResolution.existing(
                guestSession
        );
    }

    @Transactional(readOnly = true)
    public GuestSession requireValidSession(
            String rawToken
    ) {
        if (rawToken == null || rawToken.isBlank()) {
            throw new GuestSessionExpiredException();
        }

        return findValidSessionInternal(rawToken)
                .orElseThrow(
                        GuestSessionExpiredException::new
                );
    }

    @Transactional(readOnly = true)
    public Optional<GuestSession> findValidSession(
            String rawToken
    ) {
        return findValidSessionInternal(rawToken);
    }

    @Transactional(readOnly = true)
    public SessionResponse getSessionState(
            String rawToken
    ) {
        Optional<GuestSession> guestSession =
                findValidSessionInternal(rawToken);

        if (guestSession.isEmpty()) {
            return new SessionResponse(
                    true,
                    false
            );
        }

        boolean hasActiveConsultation =
                consultationRepository
                        .existsByGuestSession_IdAndStatusIn(
                                guestSession.get().getId(),
                                ConsultationStatus.activeStatuses()
                        );

        return new SessionResponse(
                true,
                hasActiveConsultation
        );
    }

    private GuestSessionResolution createNewSession() {
        String rawToken =
                tokenService.generateRawToken();

        OffsetDateTime now =
                OffsetDateTime.now(ZoneOffset.UTC);

        GuestSession guestSession =
                new GuestSession(
                        tokenService.hashToken(rawToken),
                        now,
                        now.plus(sessionTtl)
                );

        GuestSession savedSession =
                guestSessionRepository.save(
                        guestSession
                );

        return GuestSessionResolution.created(
                savedSession,
                rawToken
        );
    }

    private Optional<GuestSession> findValidSessionInternal(
            String rawToken
    ) {
        if (rawToken == null || rawToken.isBlank()) {
            return Optional.empty();
        }

        String tokenHash =
                tokenService.hashToken(rawToken);

        return guestSessionRepository
                .findByTokenHashAndExpiresAtAfter(
                        tokenHash,
                        OffsetDateTime.now(ZoneOffset.UTC)
                );
    }
}