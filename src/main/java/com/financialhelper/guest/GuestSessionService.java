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

    // 유효한 Guest Session을 조회하고,
    // 없거나 만료된 경우 새 Guest Session을 생성
    @Transactional
    public GuestSessionResolution resolveOrCreate(
            String rawToken
    ) {
        if (rawToken == null || rawToken.isBlank()) {
            return createNewSession();
        }

        return findValidSessionInternal(rawToken)
            .map(GuestSessionResolution::existing)
            .orElseGet(this::createNewSession);
    }

    // 기존 Guest Session이 유효한지 조회하고,
    // 유효하지 않으면 만료 예외 발생
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

    // Raw Token에 대응하는 유효한 Guest Session을 조회
    @Transactional(readOnly = true)
    public Optional<GuestSession> findValidSession(
            String rawToken
    ) {
        return findValidSessionInternal(rawToken);
    }

    // Guest Session과 진행 중 Consultation 존재 여부를 조회해
    // 현재 세션 상태를 반환
    @Transactional(readOnly = true)
    public SessionResponse getSessionState(
            String rawToken
    ) {
        Optional<GuestSession> guestSession =
                findValidSessionInternal(rawToken);

        if (guestSession.isEmpty()) {
            return new SessionResponse(
                    // Guest는 존재
                    true,
                    // 이어갈 상담은 없음
                    false
            );
        }

        boolean hasActiveConsultation =
                consultationRepository
                        .existsByGuestSession_IdAndStatusIn(
                                guestSession.get().getId(),
                                ConsultationStatus.resumableStatuses()
                        );

        return new SessionResponse(
                true,
                hasActiveConsultation
        );
    }

    // 새 Guest Token을 생성하고 Hash만 DB에 저장
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

    // Raw Token을 Hash한 뒤 만료되지 않은 Guest Session을 조회
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