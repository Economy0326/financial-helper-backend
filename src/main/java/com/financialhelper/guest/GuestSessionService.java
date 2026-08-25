package com.financialhelper.guest;

import com.financialhelper.consultation.ConsultationRepository;
import com.financialhelper.consultation.ConsultationStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;

@Service
public class GuestSessionService {

    private static final Set<ConsultationStatus> ACTIVE_STATUSES =
            EnumSet.of(
                    ConsultationStatus.IN_PROGRESS,
                    ConsultationStatus.ANALYZING,
                    ConsultationStatus.NEEDS_MORE_INFO
            );

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

    // @Transactional => DB 작업을 하나의 논리적 작업 단위로 묶음
    @Transactional
    // GuestSession 생성
    public GuestSessionCreationResult createSession() {
        String rawToken = tokenService.generateRawToken();

        OffsetDateTime now = now();

        GuestSession guestSession = new GuestSession(
                tokenService.hashToken(rawToken),
                now,
                now.plus(sessionTtl)
        );

        GuestSession savedSession =
                guestSessionRepository.save(guestSession);

        return new GuestSessionCreationResult(
                savedSession,
                rawToken
        );
    }

    // GuestSession 조회
    @Transactional(readOnly = true)
    public Optional<GuestSession> findValidSession(String rawToken) {
        return findValidSessionInternal(rawToken);
    }

    @Transactional(readOnly = true)
    public SessionResponse getSessionState(String rawToken) {
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
                                ACTIVE_STATUSES
                        );

        return new SessionResponse(
                true,
                hasActiveConsultation
        );
    }

    private Optional<GuestSession> findValidSessionInternal(
            String rawToken
    ) {
        if (rawToken == null || rawToken.isBlank()) {
            return Optional.empty();
        }

        String tokenHash = tokenService.hashToken(rawToken);

        return guestSessionRepository
                .findByTokenHashAndExpiresAtAfter(
                        tokenHash,
                        now()
                );
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now(ZoneOffset.UTC);
    }
}