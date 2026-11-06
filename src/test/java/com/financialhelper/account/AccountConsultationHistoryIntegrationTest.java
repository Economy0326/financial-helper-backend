package com.financialhelper.account;

import com.financialhelper.ai.OpenAiStructuredClient;
import com.financialhelper.law.KoreanLawOpenApiClient;
import com.financialhelper.retrieval.KureRuntimeHttpClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Real committed PostgreSQL rows, repository query, service mapping and authenticated HTTP. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AccountConsultationHistoryIntegrationTest {
    private static final String URL = "/api/v1/account/consultations";
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean OpenAiStructuredClient ai;
    @MockitoBean KureRuntimeHttpClient kure;
    @MockitoBean KoreanLawOpenApiClient law;
    private final List<UUID> accounts = new ArrayList<>();

    @AfterEach
    void clean() {
        for (UUID account : accounts) {
            jdbc.update("delete from consultation where account_id = ?", account);
            jdbc.update("delete from guest_session where account_id = ?", account);
            jdbc.update("delete from account where id = ?", account);
        }
    }

    @Test
    void listsBothReportKindsOnceAndKeepsLegacyFieldsPaginationAndOwnership() throws Exception {
        UUID owner = account();
        UUID other = account();
        OffsetDateTime base = OffsetDateTime.now(ZoneOffset.UTC).minusDays(2);
        UUID action = consultation(owner, base.plusHours(1));
        UUID information = consultation(owner, base.plusHours(2));
        UUID both = consultation(owner, base.plusHours(3));
        UUID unfinished = consultation(owner, base.plusHours(4));
        UUID foreign = consultation(other, base.plusHours(5));
        actionReport(action, base.plusHours(1));
        informationReport(information, base.plusHours(2));
        UUID older = informationReport(both, base.plusHours(3));
        UUID newer = informationReport(both, base.plusHours(4));
        actionReport(both, base.plusHours(3));
        informationReport(foreign, base.plusHours(5));

        var first = mvc.perform(get(URL).param("size", "2").with(as(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.content[0].consultationId").value(both.toString()))
                .andExpect(jsonPath("$.content[0].actionReportAvailable").value(true))
                .andExpect(jsonPath("$.content[0].informationReportAvailable").value(true))
                .andExpect(jsonPath("$.content[0].informationReportId").value(newer.toString()))
                .andExpect(jsonPath("$.content[1].consultationId").value(information.toString()))
                .andExpect(jsonPath("$.content[1].reportId").isEmpty())
                .andExpect(jsonPath("$.content[1].informationReportAvailable").value(true))
                .andReturn();
        assertThat(first.getResponse().getContentAsString()).doesNotContain(
                older.toString(), unfinished.toString(), foreign.toString(), "E2E_SYNTHETIC_");
        mvc.perform(get(URL).param("page", "1").param("size", "2").with(as(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.content[0].consultationId").value(action.toString()))
                .andExpect(jsonPath("$.content[0].actionReportAvailable").value(true))
                .andExpect(jsonPath("$.content[0].informationReportAvailable").value(false))
                .andExpect(jsonPath("$.content[0].reportId").isNotEmpty());
        mvc.perform(get(URL).with(as(other))).andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].consultationId").value(foreign.toString()));
        mvc.perform(get(URL)).andExpect(status().isForbidden());
    }

    @Test
    void olderInformationReportRemainsListedAfterRevisionChangesWithoutExposingEvidence() throws Exception {
        UUID owner = account();
        UUID consultation = consultation(owner, OffsetDateTime.now(ZoneOffset.UTC));
        informationReport(consultation, OffsetDateTime.now(ZoneOffset.UTC));
        jdbc.update("update consultation set case_input_revision = 1 where id = ?", consultation);
        var response = mvc.perform(get(URL).with(as(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].consultationId").value(consultation.toString()))
                .andExpect(jsonPath("$.content[0].informationReportAvailable").value(true))
                .andReturn();
        assertThat(response.getResponse().getContentAsString()).doesNotContain("E2E_SYNTHETIC_", "READY");
    }

    @Test
    void equalUpdateTimesUseConsultationIdForStablePageOrder() throws Exception {
        UUID owner = account();
        OffsetDateTime sameTime = OffsetDateTime.now(ZoneOffset.UTC);
        UUID left = consultation(owner, sameTime);
        UUID right = consultation(owner, sameTime);
        informationReport(left, sameTime);
        informationReport(right, sameTime);
        UUID first = left.toString().compareTo(right.toString()) > 0 ? left : right;
        UUID second = first.equals(left) ? right : left;
        mvc.perform(get(URL).param("size", "1").with(as(owner)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.content[0].consultationId").value(first.toString()));
        mvc.perform(get(URL).param("page", "1").param("size", "1").with(as(owner)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content[0].consultationId").value(second.toString()));
    }

    private UUID account() {
        UUID id = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        jdbc.update("insert into account(id,provider,provider_subject,status,created_at,updated_at) values (?,'KAKAO',?,'ACTIVE',?,?)",
                id, "history-fixture-" + id, now, now);
        accounts.add(id);
        return id;
    }

    private UUID consultation(UUID account, OffsetDateTime at) {
        UUID guest = UUID.randomUUID();
        UUID id = UUID.randomUUID();
        jdbc.update("insert into guest_session(id,token_hash,created_at,expires_at,account_id) values (?,?,?,?,?)",
                guest, "history-" + guest, at, at.plusDays(7), account);
        jdbc.update("insert into consultation(id,guest_session_id,account_id,category,status,current_step,created_at,updated_at) values (?,?,?,'CARD','COMPLETED','REPORT',?,?)",
                id, guest, account, at, at);
        return id;
    }

    private UUID informationReport(UUID consultation, OffsetDateTime at) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into information_report(id,consultation_id,case_input_revision,follow_up_answer_revision,candidates_json,snapshot_json,selected_evidence_json,generated_at) values (?,?,0,0,'[]','E2E_SYNTHETIC_','[]',?)",
                id, consultation, at);
        return id;
    }

    private void actionReport(UUID consultation, OffsetDateTime at) {
        UUID job = UUID.randomUUID();
        jdbc.update("insert into analysis_job(id,consultation_id,case_input_revision,follow_up_answer_revision,status,attempt_count,model,created_at,queued_at) values (?,?,0,0,'COMPLETED',1,'fixture',?,?)",
                job, consultation, at, at);
        jdbc.update("insert into consultation_report(id,consultation_id,analysis_job_id,case_input_revision,follow_up_answer_revision,model,result_json,generated_at) values (?,?,?,0,0,'fixture','{}',?)",
                UUID.randomUUID(), consultation, job, at);
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor as(UUID account) {
        return authentication(UsernamePasswordAuthenticationToken.authenticated(
                new AccountPrincipal(account, AccountProvider.KAKAO, "history-fixture"), null, List.of()));
    }
}
