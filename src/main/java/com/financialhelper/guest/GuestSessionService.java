package com.financialhelper.guest;

import com.financialhelper.account.Account;
import com.financialhelper.account.AccountOwnershipException;
import com.financialhelper.account.AccountSessionService;
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
    private final AccountSessionService accountSessionService;

    public GuestSessionService(
            GuestSessionRepository guestSessionRepository,
            ConsultationRepository consultationRepository,
            GuestSessionTokenService tokenService,
            @Value("${app.guest-session.ttl}") Duration sessionTtl,
            AccountSessionService accountSessionService
    ) {
        this.guestSessionRepository = guestSessionRepository;
        this.consultationRepository = consultationRepository;
        this.tokenService = tokenService;
        this.sessionTtl = sessionTtl;
        this.accountSessionService = accountSessionService;
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
        // account가 바뀐 뒤에도 브라우저에 guest cookie가 남을 수 있다.
        // 정상 전환이 403 소유권 오류가 되지 않도록 새 상담에는 account가 소유한
        // 새로운 guest session을 만든다. 기존 상담의 읽기/쓰기 소유권 검사는
        // requireValidSession/findValidSession에서 계속 엄격하게 적용한다.
            .filter(this::isUsableForSessionState)
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
                .map(this::requireAccountOwnership)
                .orElseThrow(
                        GuestSessionExpiredException::new
                );
    }

    // Raw Token에 대응하는 유효한 Guest Session을 조회
    @Transactional(readOnly = true)
    public Optional<GuestSession> findValidSession(
            String rawToken
    ) {
        return findValidSessionInternal(rawToken)
                .map(this::requireAccountOwnership);
    }

    // Guest Session과 진행 중 Consultation 존재 여부를 조회해
    // 현재 세션 상태를 반환
    @Transactional(readOnly = true)
    public SessionResponse getSessionState(
            String rawToken
    ) {
        Optional<GuestSession> guestSession =
                findValidSessionInternal(rawToken)
        // /session은 인증/CSRF bootstrap endpoint다. 다른 account에 묶인 오래된
        // guest cookie 때문에 이 상태 확인이 ACCOUNT_OWNERSHIP_REQUIRED가 되면 안 된다.
        // 소유권 보호가 필요한 상담 작업은 계속 위 메서드의 requireAccountOwnership을 사용한다.
                        .filter(this::isUsableForSessionState);

        if (guestSession.isEmpty()) {
            return new SessionResponse(
                    // Guest는 존재
                    true,
                    // 이어갈 상담은 없음
                    false,
                    accountSessionService.isAuthenticated(),
                    accountSessionService.currentAccountId().orElse(null),
                    accountSessionService.currentAccount().map(account -> account.getProvider().name()).orElse(null)
            );
        }

        boolean hasActiveConsultation = accountSessionService.isAuthenticated()
                ? consultationRepository.findFirstByAccount_IdAndStatusInOrderByUpdatedAtDesc(
                        accountSessionService.currentAccountId().orElseThrow(), ConsultationStatus.resumableStatuses()).isPresent()
                : consultationRepository.existsByGuestSession_IdAndStatusIn(
                        guestSession.get().getId(), ConsultationStatus.resumableStatuses());

        return new SessionResponse(
                true,
                hasActiveConsultation,
                accountSessionService.isAuthenticated(),
                accountSessionService.currentAccountId().orElse(null),
                accountSessionService.currentAccount().map(account -> account.getProvider().name()).orElse(null)
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

    private GuestSession requireAccountOwnership(GuestSession session) {
        Account owner = session.getAccount();
        if (owner == null) {
            return session;
        }
        Account current = accountSessionService.currentAccount()
                .orElseThrow(AccountOwnershipException::new);
        if (!owner.getId().equals(current.getId())) {
            throw new AccountOwnershipException();
        }
        return session;
    }

    private boolean isUsableForSessionState(GuestSession session) {
        Account owner = session.getAccount();
        if (owner == null) {
            return true;
        }

        return accountSessionService.currentAccount()
                .map(current -> owner.getId().equals(current.getId()))
                .orElse(false);
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
