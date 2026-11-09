package com.financialhelper.consultation;

import com.financialhelper.ai.OpenAiStructuredClient;
import com.financialhelper.guest.*;
import com.financialhelper.law.KoreanLawOpenApiClient;
import com.financialhelper.retrieval.KureRuntimeHttpClient;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.stream.Stream;
import org.junit.jupiter.params.provider.Arguments;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.mockito.Mockito.verifyNoInteractions;

@SpringBootTest @AutoConfigureMockMvc @ActiveProfiles("test") @Transactional
class InformationIntakeApiIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired GuestSessionRepository guests;
    @Autowired GuestSessionTokenService tokens;
    @Autowired ConsultationRepository consultations;
    @Autowired jakarta.persistence.EntityManager entityManager;
    @MockitoBean OpenAiStructuredClient openAi;
    @MockitoBean KureRuntimeHttpClient kure;
    @MockitoBean KoreanLawOpenApiClient law;

    @Test void startsThroughExistingHttpEndpointWithoutCategory() throws Exception {
        var started = mvc.perform(post("/api/v1/consultations").with(csrf()))
                .andExpect(status().isOk()).andReturn().getResponse();
        String id = com.jayway.jsonpath.JsonPath.read(started.getContentAsString(), "$.consultationId");
        String cookieHeader = started.getHeader("Set-Cookie");
        org.assertj.core.api.Assertions.assertThat(cookieHeader).startsWith(GuestSessionCookie.NAME + "=");
        String raw = cookieHeader.substring((GuestSessionCookie.NAME + "=").length()).split(";", 2)[0];
        String url = "/api/v1/consultations/" + id + "/information-intake";
        var cookie = new Cookie(GuestSessionCookie.NAME, raw);
        mvc.perform(get(url).cookie(cookie)).andExpect(status().isOk())
                .andExpect(jsonPath("$.caseInputRevision").value(0));
        mvc.perform(put(url).cookie(cookie).with(csrf()).contentType("application/json")
                        .content("{\"situationText\":\"카드를 잃어버렸어요\",\"expectedCaseInputRevision\":0}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("SCENARIO_CONFIRMATION_REQUIRED"));
        verifyNoInteractions(openAi, kure, law);
    }

    @Test void sensitiveIntakeIsRejectedWithoutChangingPersistedSituationOrRevision() throws Exception {
        var now = OffsetDateTime.now(ZoneOffset.UTC);
        String raw = tokens.generateRawToken();
        var guest = guests.saveAndFlush(new GuestSession(tokens.hashToken(raw), now, now.plusHours(1)));
        var consultation = consultations.saveAndFlush(new Consultation(guest, now));
        String url = "/api/v1/consultations/" + consultation.getId() + "/information-intake";
        var cookie = new Cookie(GuestSessionCookie.NAME, raw);
        mvc.perform(put(url).cookie(cookie).with(csrf()).contentType("application/json")
                .content("{\"situationText\":\"카드를 잃어버렸어요\",\"expectedCaseInputRevision\":0}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.caseInputRevision").value(1));
        var response = mvc.perform(put(url).cookie(cookie).with(csrf()).contentType("application/json")
                .content("{\"situationText\":\"카드를 잃어버렸고 인증번호는 123456입니다\",\"expectedCaseInputRevision\":1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("CONSULTATION_SENSITIVE_INPUT"))
                .andReturn().getResponse();
        org.assertj.core.api.Assertions.assertThat(response.getContentAsString()).doesNotContain("123456");
        entityManager.flush();
        entityManager.clear();
        mvc.perform(get(url).cookie(cookie)).andExpect(status().isOk())
                .andExpect(jsonPath("$.caseInputRevision").value(1))
                .andExpect(jsonPath("$.situationText").value("카드를 잃어버렸어요"));
        verifyNoInteractions(openAi, kure, law);
    }

    @ParameterizedTest @MethodSource("supportedScenarios")
    void eachScenarioCanBeConfirmedWithoutCategorySelection(String text, String scenario) throws Exception {
        var now = OffsetDateTime.now(ZoneOffset.UTC);
        String raw = tokens.generateRawToken();
        var guest = guests.saveAndFlush(new GuestSession(tokens.hashToken(raw), now, now.plusHours(1)));
        var c = consultations.saveAndFlush(new Consultation(guest, now));
        String url = "/api/v1/consultations/" + c.getId() + "/information-intake";
        var cookie = new Cookie(GuestSessionCookie.NAME, raw);
        String body = tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(
                java.util.Map.of("situationText", text, "expectedCaseInputRevision", 0));
        mvc.perform(put(url).cookie(cookie).with(csrf()).contentType("application/json").content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.scenarioCandidates[0]").value(scenario));
        mvc.perform(post(url + "/confirm").cookie(cookie).with(csrf()).contentType("application/json")
                .content("{\"scenario\":\"" + scenario + "\",\"expectedCaseInputRevision\":1}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.confirmedScenario").value(scenario));
        verifyNoInteractions(openAi, kure, law);
    }

    static Stream<Arguments> supportedScenarios() {
        return Stream.of(Arguments.of("카드를 잃어버렸는데 모르는 결제가 있어요", "CARD_LOSS_UNAUTHORIZED_USE"),
                Arguments.of("보이스피싱인 것 같아서 송금했어요", "VOICE_PHISHING_SUSPICIOUS_TRANSFER"),
                Arguments.of("제 계좌에서 제가 하지 않은 이체가 발생했어요", "UNAUTHORIZED_ACCOUNT_TRANSFER"),
                Arguments.of("이상한 문자 링크를 눌렀어요", "PERSONAL_INFO_SMISHING_MALICIOUS_APP"));
    }

    @Test void generalTopicRemainsUnsearchableAndConfirmationTracksRevisionAndOwnership() throws Exception {
        var now = OffsetDateTime.now(ZoneOffset.UTC);
        String raw = tokens.generateRawToken(), otherRaw = tokens.generateRawToken();
        var guest = guests.saveAndFlush(new GuestSession(tokens.hashToken(raw), now, now.plusHours(1)));
        guests.saveAndFlush(new GuestSession(tokens.hashToken(otherRaw), now, now.plusHours(1)));
        var c = consultations.saveAndFlush(new Consultation(guest, now));
        String url = "/api/v1/consultations/" + c.getId() + "/information-intake";
        var owner = new Cookie(GuestSessionCookie.NAME, raw);
        var other = new Cookie(GuestSessionCookie.NAME, otherRaw);
        mvc.perform(put(url).cookie(owner).with(csrf()).contentType("application/json")
                .content("{\"situationText\":\"예금자보호 제도가 궁금해요\",\"selectedTopic\":\"INSURANCE\",\"expectedCaseInputRevision\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("TOPIC_MISMATCH_CONFIRMATION_REQUIRED"))
                .andExpect(jsonPath("$.topicCandidates[0]").value("DEPOSITS_ACCOUNTS"))
                .andExpect(jsonPath("$.selectedTopic").value("INSURANCE"))
                .andExpect(jsonPath("$.suggestedRoute").value("INFORMATION"))
                .andExpect(jsonPath("$.evidenceAvailability").value("NOT_EVALUATED"))
                .andExpect(jsonPath("$.generalInformationSearchAvailable").value(false));
        mvc.perform(get(url).cookie(owner)).andExpect(status().isOk())
                .andExpect(jsonPath("$.caseInputRevision").value(1));
        String confirmation = "{\"topic\":\"DEPOSITS_ACCOUNTS\",\"expectedCaseInputRevision\":1}";
        mvc.perform(post(url + "/confirm-topic").cookie(other).with(csrf())
                .contentType("application/json").content(confirmation)).andExpect(status().isNotFound());
        mvc.perform(post(url + "/confirm-topic").cookie(owner)
                .contentType("application/json").content(confirmation)).andExpect(status().isForbidden());
        mvc.perform(post(url + "/confirm-topic").cookie(owner).with(csrf())
                .contentType("application/json").content(confirmation))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("TOPIC_CONFIRMED_NO_SEARCH"))
                .andExpect(jsonPath("$.confirmedTopic").value("DEPOSITS_ACCOUNTS"))
                .andExpect(jsonPath("$.caseInputRevision").value(2));
        mvc.perform(post(url + "/confirm-topic").cookie(owner).with(csrf())
                .contentType("application/json").content(confirmation)).andExpect(status().isConflict());
        mvc.perform(put(url).cookie(owner).with(csrf()).contentType("application/json")
                .content("{\"situationText\":\"보험금 청구 방법이 궁금해요\",\"selectedTopic\":\"INSURANCE\",\"expectedCaseInputRevision\":2}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.confirmedTopic").doesNotExist())
                .andExpect(jsonPath("$.caseInputRevision").value(3));
        entityManager.flush(); entityManager.clear();
        mvc.perform(get(url).cookie(owner)).andExpect(status().isOk())
                .andExpect(jsonPath("$.confirmedTopic").doesNotExist())
                .andExpect(jsonPath("$.topicCandidates[0]").value("INSURANCE"));
        verifyNoInteractions(openAi, kure, law);
    }

    @Test void nonFinancialUnsupportedAndSensitiveInputsRemainDistinct() throws Exception {
        var now = OffsetDateTime.now(ZoneOffset.UTC);
        String raw = tokens.generateRawToken();
        var guest = guests.saveAndFlush(new GuestSession(tokens.hashToken(raw), now, now.plusHours(1)));
        var c = consultations.saveAndFlush(new Consultation(guest, now));
        String url = "/api/v1/consultations/" + c.getId() + "/information-intake";
        var cookie = new Cookie(GuestSessionCookie.NAME, raw);
        mvc.perform(put(url).cookie(cookie).with(csrf()).contentType("application/json")
                .content("{\"situationText\":\"점심 메뉴 알려줘\",\"selectedTopic\":\"CARD_PAYMENTS\",\"expectedCaseInputRevision\":0}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("OUT_OF_SCOPE_NON_FINANCIAL"));
        mvc.perform(put(url).cookie(cookie).with(csrf()).contentType("application/json")
                .content("{\"situationText\":\"가상자산 스테이킹의 적용 규칙은?\",\"expectedCaseInputRevision\":1}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UNSUPPORTED_FINANCIAL_TOPIC"));
        mvc.perform(put(url).cookie(cookie).with(csrf()).contentType("application/json")
                .content("{\"situationText\":\"예금자보호가 궁금하고 인증번호는 123456입니다\",\"expectedCaseInputRevision\":2}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("CONSULTATION_SENSITIVE_INPUT"));
        entityManager.flush(); entityManager.clear();
        mvc.perform(get(url).cookie(cookie)).andExpect(status().isOk())
                .andExpect(jsonPath("$.caseInputRevision").value(2))
                .andExpect(jsonPath("$.situationText").value("가상자산 스테이킹의 적용 규칙은?"));
        verifyNoInteractions(openAi, kure, law);
    }

    @Test void candidateConfirmationRevisionAndCorrectionAreBoundToOwner() throws Exception {
        var now=OffsetDateTime.now(ZoneOffset.UTC);
        String raw=tokens.generateRawToken(), otherRaw=tokens.generateRawToken();
        var guest=guests.saveAndFlush(new GuestSession(tokens.hashToken(raw), now, now.plusHours(1)));
        guests.saveAndFlush(new GuestSession(tokens.hashToken(otherRaw), now, now.plusHours(1)));
        var c=consultations.saveAndFlush(new Consultation(guest, now));
        String url="/api/v1/consultations/"+c.getId()+"/information-intake";
        var cookie=new Cookie(GuestSessionCookie.NAME,raw);
        var other=new Cookie(GuestSessionCookie.NAME,otherRaw);
        mvc.perform(get(url)).andExpect(status().isUnauthorized());
        mvc.perform(get(url).cookie(other)).andExpect(status().isNotFound());
        mvc.perform(get(url).cookie(cookie)).andExpect(status().isOk()).andExpect(jsonPath("$.caseInputRevision").value(0));
        String body="{\"situationText\":\"카드를 잃어버렸어요\",\"expectedCaseInputRevision\":0}";
        mvc.perform(put(url).cookie(cookie).contentType("application/json").content(body)).andExpect(status().isForbidden());
        mvc.perform(put(url).cookie(other).with(csrf()).contentType("application/json").content(body)).andExpect(status().isNotFound());
        mvc.perform(put(url).cookie(cookie).with(csrf()).contentType("application/json").content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("SCENARIO_CONFIRMATION_REQUIRED"))
                .andExpect(jsonPath("$.scenarioCandidates[0]").value("CARD_LOSS_UNAUTHORIZED_USE"))
                .andExpect(jsonPath("$.confirmedScenario").doesNotExist()).andExpect(jsonPath("$.caseInputRevision").value(1));
        mvc.perform(post(url+"/confirm").cookie(cookie).with(csrf()).contentType("application/json")
                .content("{\"scenario\":\"CARD_LOSS_UNAUTHORIZED_USE\",\"expectedCaseInputRevision\":0}"))
                .andExpect(status().isConflict());
        mvc.perform(post(url+"/confirm").cookie(cookie).with(csrf()).contentType("application/json")
                .content("{\"scenario\":\"CARD_LOSS_UNAUTHORIZED_USE\",\"expectedCaseInputRevision\":1}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("READY"))
                .andExpect(jsonPath("$.caseInputRevision").value(2));
        mvc.perform(put(url).cookie(cookie).with(csrf()).contentType("application/json")
                .content("{\"situationText\":\"문자 링크를 눌렀고 보이스피싱으로 송금했어요\",\"expectedCaseInputRevision\":2}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("NEEDS_CLARIFICATION"))
                .andExpect(jsonPath("$.scenarioCandidates.length()").value(2))
                .andExpect(jsonPath("$.caseInputRevision").value(3));
        mvc.perform(post(url+"/confirm").cookie(cookie).with(csrf()).contentType("application/json")
                .content("{\"scenario\":\"VOICE_PHISHING_SUSPICIOUS_TRANSFER\",\"expectedCaseInputRevision\":3}"))
                .andExpect(status().isConflict());
        mvc.perform(post("/api/v1/consultations/" + c.getId() + "/information-report/prepare")
                        .cookie(cookie).with(csrf()).contentType("application/json")
                        .content("{\"expectedCaseInputRevision\":3}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("INSUFFICIENT_EVIDENCE"))
                .andExpect(jsonPath("$.selectedEvidence").isEmpty());
        mvc.perform(put(url).cookie(cookie).with(csrf()).contentType("application/json")
                .content("{\"situationText\":\"점심 메뉴 알려줘\",\"expectedCaseInputRevision\":3}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("OUT_OF_SCOPE_NON_FINANCIAL"))
                .andExpect(jsonPath("$.scenarioCandidates").isEmpty());
        mvc.perform(put(url).cookie(cookie).with(csrf()).contentType("application/json")
                .content("{\"situationText\":\"체크카드를 분실했어요\",\"expectedCaseInputRevision\":4}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UNSUPPORTED_SCOPE"));
        mvc.perform(post(url+"/confirm").cookie(cookie).with(csrf()).contentType("application/json")
                .content("{\"scenario\":\"CARD_LOSS_UNAUTHORIZED_USE\",\"expectedCaseInputRevision\":5}"))
                .andExpect(status().isUnprocessableEntity());
        verifyNoInteractions(openAi,kure,law);
    }
}
