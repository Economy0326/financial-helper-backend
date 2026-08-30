package com.financialhelper.consultation;

import com.financialhelper.guest.GuestSession;
import com.financialhelper.guest.GuestSessionRepository;

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.test.context.ActiveProfiles;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest(showSql = false)
@ActiveProfiles("test")
@AutoConfigureTestDatabase(
        replace =
                AutoConfigureTestDatabase.Replace.NONE
)
class ConsultationRepositoryIntegrationTest {

    @Autowired
    private ConsultationRepository consultationRepository;

    @Autowired
    private GuestSessionRepository guestSessionRepository;

    // Guest 소유권 기반 조회 검증
    @Test
    void findsConsultationOnlyForOwner() {
        OffsetDateTime now =
                OffsetDateTime.now(
                        ZoneOffset.UTC
                );

        GuestSession guestA =
                guestSessionRepository.save(
                        new GuestSession(
                                UUID.randomUUID().toString(),
                                now,
                                now.plusHours(1)
                        )
                );

        GuestSession guestB =
                guestSessionRepository.save(
                        new GuestSession(
                                UUID.randomUUID().toString(),
                                now,
                                now.plusHours(1)
                        )
                );

        Consultation consultation =
                consultationRepository.save(
                        new Consultation(
                                guestA,
                                now
                        )
                );

        Optional<Consultation> ownerResult =
                consultationRepository
                        .findByIdAndGuestSession_Id(
                                consultation.getId(),
                                guestA.getId()
                        );

        Optional<Consultation> otherGuestResult =
                consultationRepository
                        .findByIdAndGuestSession_Id(
                                consultation.getId(),
                                guestB.getId()
                        );

        assertThat(ownerResult).isPresent();
        assertThat(otherGuestResult).isEmpty();
    }

    // Active Consultation 조회 검증
    @Test
    void findsActiveConsultationForGuest() {
        OffsetDateTime now =
                OffsetDateTime.now(
                        ZoneOffset.UTC
                );

        GuestSession guestSession =
                guestSessionRepository.save(
                        new GuestSession(
                                UUID.randomUUID().toString(),
                                now,
                                now.plusHours(1)
                        )
                );

        Consultation consultation =
                consultationRepository.save(
                        new Consultation(
                                guestSession,
                                now
                        )
                );

        Optional<Consultation> result =
                consultationRepository
                        .findFirstByGuestSession_IdAndStatusInOrderByUpdatedAtDesc(
                                guestSession.getId(),
                                ConsultationStatus.activeStatuses()
                        );

        assertThat(result).isPresent();

        assertThat(
                result.orElseThrow().getId()
        ).isEqualTo(
                consultation.getId()
        );
    }
}