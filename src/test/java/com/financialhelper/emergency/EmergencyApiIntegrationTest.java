package com.financialhelper.emergency;

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
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class EmergencyApiIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private EmergencySelectionRepository selectionRepository;

    @Autowired
    private GuestSessionRepository guestSessionRepository;

    @Autowired
    private GuestSessionTokenService tokenService;

    @BeforeEach
    void cleanEmergencyState() {
        selectionRepository.deleteAll();
    }

    // 긴급대응 유형 순서 검증
    @Test
    void returnsEmergencyTypesInDisplayOrder()
            throws Exception {
        mockMvc.perform(
                        get("/api/v1/emergency/types")
                )
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath("$[0].value")
                                .value("TRANSFER")
                )
                .andExpect(
                        jsonPath("$[1].value")
                                .value("UNKNOWN_PAYMENT")
                )
                .andExpect(
                        jsonPath("$[2].value")
                                .value("SUSPICIOUS_APP")
                )
                .andExpect(
                        jsonPath("$[3].value")
                                .value("PERSONAL_INFO")
                )
                .andExpect(
                        jsonPath("$[4].value")
                                .value("UNKNOWN")
                );
    }

    // Guest 없는 첫 선택 시 세션 선택 생성 검증
    @Test
    void selectionWithoutCookieCreatesGuestAndSelection()
            throws Exception {
        long guestCountBefore =
                guestSessionRepository.count();

        MvcResult result =
                mockMvc.perform(
                                put("/api/v1/emergency/selection")
                                        .with(csrf())
                                        .contentType(
                                                MediaType.APPLICATION_JSON
                                        )
                                        .content(
                                                """
                                                {
                                                  "type": "TRANSFER"
                                                }
                                                """
                                        )
                        )
                        .andExpect(status().isOk())
                        .andExpect(
                                jsonPath("$.selectedType")
                                        .value("TRANSFER")
                        )
                        .andExpect(
                                jsonPath("$.scenarioKey")
                                        .value("TRANSFER_SCENARIO")
                        )
                        .andReturn();

        List<String> setCookies =
                result.getResponse()
                        .getHeaders(HttpHeaders.SET_COOKIE);

        assertThat(setCookies)
                .anyMatch(value ->
                        value.contains(
                                GuestSessionCookie.NAME + "="
                        ) && value.contains("HttpOnly")
                );

        assertThat(guestSessionRepository.count())
                .isEqualTo(guestCountBefore + 1);

        assertThat(selectionRepository.count())
                .isEqualTo(1);
    }

    // 선택 없음 상태 검증
    @Test
    void scenarioWithoutSelectionReturnsNotSelected()
            throws Exception {
        TestGuest guest = createGuest();

        mockMvc.perform(
                        get("/api/v1/emergency/scenario")
                                .cookie(guest.cookie())
                )
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath("$.kind")
                                .value("not-selected")
                )
                .andExpect(
                        jsonPath("$.scenario")
                                .value(nullValue())
                );
    }

    // 선택한 긴급대응 Scenario 조회 검증
    @Test
    void selectedScenarioIsReturnedForGuest()
            throws Exception {
        TestGuest guest = createGuest();

        select(guest.cookie(), "TRANSFER");

        mockMvc.perform(
                        get("/api/v1/emergency/scenario")
                                .cookie(guest.cookie())
                )
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath("$.kind")
                                .value("ready")
                )
                .andExpect(
                        jsonPath("$.scenario.type")
                                .value("TRANSFER")
                )
                .andExpect(
                        jsonPath("$.scenario.scenarioKey")
                                .value("TRANSFER_SCENARIO")
                )
                .andExpect(
                        jsonPath("$.scenario.actionsToDo")
                                .isArray()
                )
                .andExpect(
                        jsonPath("$.scenario.contacts")
                                .isArray()
                );
    }

    // UNKNOWN 공통 Scenario 매핑 검증
    @Test
    void unknownMapsToCommonEmergencyScenario()
            throws Exception {
        TestGuest guest = createGuest();

        select(guest.cookie(), "UNKNOWN");

        mockMvc.perform(
                        get("/api/v1/emergency/scenario")
                                .cookie(guest.cookie())
                )
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath("$.scenario.type")
                                .value("UNKNOWN")
                )
                .andExpect(
                        jsonPath("$.scenario.scenarioKey")
                                .value("COMMON_EMERGENCY_SCENARIO")
                );
    }

    // 같은 Guest 재선택 시 기존 Selection 수정 검증
    @Test
    void selectingAgainUpdatesSameGuestSelection()
            throws Exception {
        TestGuest guest = createGuest();

        select(guest.cookie(), "TRANSFER");
        select(guest.cookie(), "PERSONAL_INFO");

        assertThat(selectionRepository.count())
                .isEqualTo(1);

        EmergencySelection selection =
                selectionRepository
                        .findByGuestSession_Id(
                                guest.session().getId()
                        )
                        .orElseThrow();

        assertThat(selection.getSelectedType())
                .isEqualTo(EmergencyType.PERSONAL_INFO);
    }

    // Guest별 Selection 격리 검증
    @Test
    void guestsHaveIsolatedSelections()
            throws Exception {
        TestGuest first = createGuest();
        TestGuest second = createGuest();

        select(first.cookie(), "TRANSFER");
        select(second.cookie(), "SUSPICIOUS_APP");

        mockMvc.perform(
                        get("/api/v1/emergency/scenario")
                                .cookie(first.cookie())
                )
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath("$.scenario.type")
                                .value("TRANSFER")
                );

        mockMvc.perform(
                        get("/api/v1/emergency/scenario")
                                .cookie(second.cookie())
                )
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath("$.scenario.type")
                                .value("SUSPICIOUS_APP")
                );
    }

    // CSRF 없는 Selection 요청 차단 검증
    @Test
    void selectionWithoutCsrfIsRejected()
            throws Exception {
        mockMvc.perform(
                        put("/api/v1/emergency/selection")
                                .contentType(
                                        MediaType.APPLICATION_JSON
                                )
                                .content(
                                        """
                                        {
                                          "type": "TRANSFER"
                                        }
                                        """
                                )
                )
                .andExpect(status().isForbidden());
    }

    // 잘못된 Emergency Type 요청 검증
    @Test
    void invalidEmergencyTypeReturnsValidationError()
            throws Exception {
        mockMvc.perform(
                        put("/api/v1/emergency/selection")
                                .with(csrf())
                                .contentType(
                                        MediaType.APPLICATION_JSON
                                )
                                .content(
                                        """
                                        {
                                          "type": "NOT_A_TYPE"
                                        }
                                        """
                                )
                )
                .andExpect(status().isBadRequest())
                .andExpect(
                        jsonPath("$.error.code")
                                .value("VALIDATION_ERROR")
                );
    }

    private void select(
            Cookie cookie,
            String type
    ) throws Exception {
        mockMvc.perform(
                        put("/api/v1/emergency/selection")
                                .cookie(cookie)
                                .with(csrf())
                                .contentType(
                                        MediaType.APPLICATION_JSON
                                )
                                .content(
                                        """
                                        {
                                          "type": "%s"
                                        }
                                        """.formatted(type)
                                )
                )
                .andExpect(status().isOk());
    }

    private TestGuest createGuest() {
        String rawToken =
                tokenService.generateRawToken();

        OffsetDateTime now =
                OffsetDateTime.now(ZoneOffset.UTC);

        GuestSession guestSession =
                guestSessionRepository.save(
                        new GuestSession(
                                tokenService.hashToken(rawToken),
                                now,
                                now.plusHours(1)
                        )
                );

        return new TestGuest(
                guestSession,
                new Cookie(
                        GuestSessionCookie.NAME,
                        rawToken
                )
        );
    }

    private record TestGuest(
            GuestSession session,
            Cookie cookie
    ) {
    }
}