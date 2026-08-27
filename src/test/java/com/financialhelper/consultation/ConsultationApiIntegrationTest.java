package com.financialhelper.consultation;

import com.financialhelper.guest.GuestSession;
import com.financialhelper.guest.GuestSessionCookie;
import com.financialhelper.guest.GuestSessionRepository;
import com.financialhelper.guest.GuestSessionTokenService;

import jakarta.servlet.http.Cookie;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ConsultationApiIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private GuestSessionRepository guestSessionRepository;

    @Autowired
    private ConsultationRepository consultationRepository;

    @Autowired
    private GuestSessionTokenService tokenService;

    @BeforeEach
    void cleanDatabase() {
        consultationRepository.deleteAll();
        guestSessionRepository.deleteAll();
    }

    private TestGuest createGuest() {
        String rawToken =
                tokenService.generateRawToken();

        OffsetDateTime now =
                OffsetDateTime.now(
                        ZoneOffset.UTC
                );

        GuestSession guestSession =
                guestSessionRepository.save(
                        new GuestSession(
                                tokenService.hashToken(
                                        rawToken
                                ),
                                now,
                                now.plusHours(1)
                        )
                );

        Cookie cookie =
                new Cookie(
                        GuestSessionCookie.NAME,
                        rawToken
                );

        return new TestGuest(
                guestSession,
                cookie
        );
    }

    private Consultation createConsultation(
            GuestSession guestSession
    ) {
        return consultationRepository.save(
                new Consultation(
                        guestSession,
                        OffsetDateTime.now(
                                ZoneOffset.UTC
                        )
                )
        );
    }

    private record TestGuest(
            GuestSession session,
            Cookie cookie
    ) {
    }

    // 첫 Session
    @Test
    void sessionWithoutCookieHasNoActiveConsultation()
            throws Exception {

        mockMvc.perform(
                        get("/api/v1/session")
                )
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath("$.guest")
                                .value(true)
                )
                .andExpect(
                        jsonPath(
                                "$.hasActiveConsultation"
                        ).value(false)
                );
    }

    // 첫 Consultation 생성
    @Test
    void startsConsultationAndIssuesGuestCookie()
            throws Exception {

        MvcResult result =
                mockMvc.perform(
                                post(
                                        "/api/v1/consultations"
                                )
                                        .with(csrf())
                        )
                        .andExpect(
                                status().isOk()
                        )
                        .andExpect(
                                jsonPath("$.status")
                                        .value(
                                                "IN_PROGRESS"
                                        )
                        )
                        .andExpect(
                                jsonPath("$.currentStep")
                                        .value(
                                                "CATEGORY"
                                        )
                        )
                        .andReturn();

        List<String> setCookies =
                result.getResponse()
                        .getHeaders(
                                HttpHeaders.SET_COOKIE
                        );

        assertThat(setCookies)
                .anyMatch(value ->
                        value.contains(
                                GuestSessionCookie.NAME
                                        + "="
                        )
                                &&
                        value.contains("HttpOnly")
                );

        assertThat(
                guestSessionRepository.count()
        ).isEqualTo(1);

        assertThat(
                consultationRepository.count()
        ).isEqualTo(1);
    }

    // Category -> Situation -> Follow-up
    @Test
    void savesCategoryAndSituationWithStateTransition()
            throws Exception {

        TestGuest guest =
                createGuest();

        Consultation consultation =
                createConsultation(
                        guest.session()
                );

        mockMvc.perform(
                        put(
                                "/api/v1/consultations/{id}/category",
                                consultation.getId()
                        )
                                .cookie(
                                        guest.cookie()
                                )
                                .with(csrf())
                                .contentType(
                                        MediaType.APPLICATION_JSON
                                )
                                .content(
                                        """
                                        {
                                        "category": "INSURANCE"
                                        }
                                        """
                                )
                )
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath("$.currentStep")
                                .value("SITUATION")
                );

        mockMvc.perform(
                        put(
                                "/api/v1/consultations/{id}/situation",
                                consultation.getId()
                        )
                                .cookie(
                                        guest.cookie()
                                )
                                .with(csrf())
                                .contentType(
                                        MediaType.APPLICATION_JSON
                                )
                                .content(
                                        """
                                        {
                                        "situationText":
                                        "Refund amount seems too small."
                                        }
                                        """
                                )
                )
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath("$.currentStep")
                                .value("FOLLOW_UP")
                );

        Consultation saved =
                consultationRepository
                        .findById(
                                consultation.getId()
                        )
                        .orElseThrow();

        assertThat(saved.getCategory())
                .isEqualTo(
                        ConsultationCategory.INSURANCE
                );

        assertThat(saved.getSituationText())
                .isEqualTo(
                        "Refund amount seems too small."
                );

        assertThat(saved.getCurrentStep())
                .isEqualTo(
                        ConsultationStep.FOLLOW_UP
                );
    }

    // Guest Ownership
    @Test
    void hidesConsultationFromDifferentGuest()
            throws Exception {

        TestGuest guestA =
                createGuest();

        TestGuest guestB =
                createGuest();

        Consultation consultationA =
                createConsultation(
                        guestA.session()
                );

        mockMvc.perform(
                        get(
                                "/api/v1/consultations/{id}",
                                consultationA.getId()
                        )
                                .cookie(
                                        guestB.cookie()
                                )
                )
                .andExpect(
                        status().isNotFound()
                )
                .andExpect(
                        jsonPath("$.error.code")
                                .value(
                                        "CONSULTATION_NOT_FOUND"
                                )
                );
    }

    // 실제로 없는 ID => 404 
    @Test
    void returnsSameNotFoundForUnknownConsultation()
            throws Exception {

        TestGuest guest =
                createGuest();

        mockMvc.perform(
                        get(
                                "/api/v1/consultations/{id}",
                                java.util.UUID.randomUUID()
                        )
                                .cookie(
                                        guest.cookie()
                                )
                )
                .andExpect(
                        status().isNotFound()
                )
                .andExpect(
                        jsonPath("$.error.code")
                                .value(
                                        "CONSULTATION_NOT_FOUND"
                                )
                );
    }

    // CSRF 차단 -> 403
    @Test
    void rejectsWriteRequestWithoutCsrf()
            throws Exception {

        TestGuest guest =
                createGuest();

        Consultation consultation =
                createConsultation(
                        guest.session()
                );

        mockMvc.perform(
                        put(
                                "/api/v1/consultations/{id}/category",
                                consultation.getId()
                        )
                                .cookie(
                                        guest.cookie()
                                )
                                .contentType(
                                        MediaType.APPLICATION_JSON
                                )
                                .content(
                                        """
                                        {
                                        "category": "CARD"
                                        }
                                        """
                                )
                )
                .andExpect(
                        status().isForbidden()
                );
    }

    // Validation Error
    @Test
    void returnsValidationErrorForBlankSituation()
            throws Exception {

        TestGuest guest =
                createGuest();

        Consultation consultation =
                createConsultation(
                        guest.session()
                );

        consultation.updateCategory(
                ConsultationCategory.INSURANCE,
                OffsetDateTime.now(
                        ZoneOffset.UTC
                )
        );

        consultationRepository.save(
                consultation
        );

        mockMvc.perform(
                        put(
                                "/api/v1/consultations/{id}/situation",
                                consultation.getId()
                        )
                                .cookie(
                                        guest.cookie()
                                )
                                .with(csrf())
                                .contentType(
                                        MediaType.APPLICATION_JSON
                                )
                                .content(
                                        """
                                        {
                                        "situationText": "   "
                                        }
                                        """
                                )
                )
                .andExpect(
                        status().isBadRequest()
                )
                .andExpect(
                        jsonPath("$.error.code")
                                .value(
                                        "VALIDATION_ERROR"
                                )
                )
                .andExpect(
                        jsonPath(
                                "$.error.fieldErrors[0].field"
                        ).value(
                                "situationText"
                        )
                )
                .andExpect(
                        jsonPath(
                                "$.error.fieldErrors[0].reason"
                        ).value(
                                "REQUIRED"
                        )
                );
    }
}