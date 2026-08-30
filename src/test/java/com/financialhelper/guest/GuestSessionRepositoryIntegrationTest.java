package com.financialhelper.guest;

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.test.context.ActiveProfiles;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest(showSql = false)
@ActiveProfiles("test")
@AutoConfigureTestDatabase(
        replace =
                AutoConfigureTestDatabase.Replace.NONE
)
class GuestSessionRepositoryIntegrationTest {

    @Autowired
    private GuestSessionRepository repository;

    // 만료되지 않은 Guest Session만 조회되는지 검증
    @Test
    void findsOnlyNonExpiredSession() {
        OffsetDateTime now =
                OffsetDateTime.now(
                        ZoneOffset.UTC
                );

        GuestSession validSession =
                repository.save(
                        new GuestSession(
                                "valid-test-token-hash",
                                now,
                                now.plusHours(1)
                        )
                );

        repository.save(
                new GuestSession(
                        "expired-test-token-hash",
                        now.minusHours(2),
                        now.minusHours(1)
                )
        );

        assertThat(
                repository
                        .findByTokenHashAndExpiresAtAfter(
                                validSession.getTokenHash(),
                                now
                        )
        ).isPresent();

        assertThat(
                repository
                        .findByTokenHashAndExpiresAtAfter(
                                "expired-test-token-hash",
                                now
                        )
        ).isEmpty();
    }
}