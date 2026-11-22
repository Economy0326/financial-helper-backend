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

/** Reviewer authorization and CSRF are unchanged for the six-source workflow. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class OfficialInformationReviewerHttpIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired AccountSessionService sessions;

    @Test void reviewerGrantIsRequiredAndCandidateRegistrationIsNotAnApproval() throws Exception {
        String base = "/api/v1/source-review/information/fsc-silsong24-guide";
        long reviewCountBefore = jdbc.queryForObject("SELECT count(*) FROM source_applicability_review", Long.class);
        long auditCountBefore = jdbc.queryForObject("SELECT count(*) FROM source_review_decision_audit", Long.class);
        var login = sessions.createSession(AccountProvider.NAVER,
                "synthetic-information-reviewer-" + UUID.randomUUID(), "fixture");
        Cookie cookie = new Cookie(AccountSessionCookie.NAME, login.rawToken());
        mvc.perform(get(base + "/document")).andExpect(status().is4xxClientError());
        mvc.perform(get(base + "/document").cookie(cookie)).andExpect(status().isForbidden());
        mvc.perform(post(base + "/ingest").cookie(cookie).with(csrf()))
                .andExpect(status().isForbidden());
        jdbc.update("INSERT INTO source_reviewer_grant(account_id, granted_by) VALUES (?, 'isolated-test')",
                login.account().getId());
        mvc.perform(get(base + "/document").cookie(cookie)).andExpect(status().isConflict());
        mvc.perform(post(base + "/ingest").cookie(cookie)).andExpect(status().isForbidden());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM source_applicability_review", Long.class))
                .isEqualTo(reviewCountBefore);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM source_review_decision_audit", Long.class))
                .isEqualTo(auditCountBefore);
    }
}
