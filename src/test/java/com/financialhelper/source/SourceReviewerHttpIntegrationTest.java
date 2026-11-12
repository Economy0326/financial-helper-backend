package com.financialhelper.source;

import com.financialhelper.account.AccountProvider;
import com.financialhelper.account.AccountSessionCookie;
import com.financialhelper.account.AccountSessionService;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Runs only against the existing isolated PostgreSQL test harness. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class SourceReviewerHttpIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired AccountSessionService sessions;

    @Test void onlyExplicitlyGrantedRealAccountSessionCanReachReviewerWorkflow() throws Exception {
        var login = sessions.createSession(AccountProvider.KAKAO,
                "synthetic-reviewer-" + UUID.randomUUID(), "fixture");
        Cookie accountCookie = new Cookie(AccountSessionCookie.NAME, login.rawToken());

        mvc.perform(get("/api/v1/source-review/deposit/document"))
                .andExpect(status().is4xxClientError());
        mvc.perform(get("/api/v1/source-review/deposit/audit")
                        .param("documentId", UUID.randomUUID().toString()))
                .andExpect(status().is4xxClientError());
        mvc.perform(get("/api/v1/source-review/deposit/document").cookie(accountCookie))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/source-review/deposit/sources").cookie(accountCookie))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/source-review/deposit/audit")
                        .param("documentId", UUID.randomUUID().toString()).cookie(accountCookie))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/source-review/deposit/ingest").cookie(accountCookie))
                .andExpect(status().isForbidden());

        jdbc.update("""
                INSERT INTO source_reviewer_grant(account_id, granted_by)
                VALUES (?, 'isolated-test-fixture')
                """, login.account().getId());
        mvc.perform(get("/api/v1/source-review/deposit/sources").cookie(accountCookie))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/source-review/deposit/document").cookie(accountCookie))
                .andExpect(status().isConflict()); // grant works; official registry is not provisioned
        mvc.perform(post("/api/v1/source-review/deposit/ingest").cookie(accountCookie).with(csrf()))
                .andExpect(status().isConflict());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM source_review_decision_audit", Long.class))
                .isZero();
    }
}
