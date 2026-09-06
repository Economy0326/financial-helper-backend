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

    // 종료성 상태라도 Home Resume 대상이면 조회 가능한지 검증
    @Test
    void findsResumableInsufficientInformationConsultationForGuest() {

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
                new Consultation(
                        guestSession,
                        now
                );

        consultation.updateCategory(
                ConsultationCategory.INSURANCE,
                now.plusSeconds(1)
        );

        consultation.updateSituation(
                "보험 해지환급금 문제입니다.",
                now.plusSeconds(2)
        );

        consultation.moveToAnalysis(
                now.plusSeconds(3)
        );

        consultation.markInsufficientInformation(
                now.plusSeconds(4)
        );

        consultation =
                consultationRepository.saveAndFlush(
                        consultation
                );

        Optional<Consultation> resumableResult =
                consultationRepository
                        .findFirstByGuestSession_IdAndStatusInOrderByUpdatedAtDesc(
                                guestSession.getId(),
                                ConsultationStatus.resumableStatuses()
                        );

        Optional<Consultation> activeResult =
                consultationRepository
                        .findFirstByGuestSession_IdAndStatusInOrderByUpdatedAtDesc(
                                guestSession.getId(),
                                ConsultationStatus.activeStatuses()
                        );

        // Home에서 다시 접근 가능한가
        assertThat(resumableResult).isPresent();

        assertThat(
                resumableResult
                        .orElseThrow()
                        .getStatus()
        ).isEqualTo(
                ConsultationStatus.INSUFFICIENT_INFORMATION
        );

        // 접근 가능할 때 계속 진행/재시도가 가능한가
        assertThat(activeResult).isEmpty();

        // INSUFFICIENT_INFORMATION은 Resumable이지만 Active는 아님
    }
}