package com.financialhelper.guest;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

// Cookie의 Guest Token을 hash한 뒤 해당 Guest Session을 DB에서 조회하기 위해 사용
public interface GuestSessionRepository
        extends JpaRepository<GuestSession, UUID> {

    // Opitonal => Cookie 없음 / Token 잘못됨 / Session 만료 상태일 수도 있으니 Optional로 감싸서 반환
    Optional<GuestSession> findByTokenHashAndExpiresAtAfter(
        String tokenHash,
        // 세션 만료 조건
        OffsetDateTime now
    );
}