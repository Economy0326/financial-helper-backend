package com.financialhelper.source;

import com.financialhelper.account.AccountProvider;
import com.financialhelper.account.AccountSessionCookie;
import com.financialhelper.account.AccountSessionService;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Opt-in, read-only FSC preview against an isolated database; never stores source content. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "server.address=127.0.0.1")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named="FH_REAL_FSC_PREVIEW", matches="true")
class FscReviewerLivePreviewIntegrationTest {
    private static final int UI_PORT = allocatePort();
    private static int allocatePort() {
        try (var socket = new java.net.ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))) { return socket.getLocalPort(); }
        catch (java.io.IOException exception) { throw new IllegalStateException(exception); }
    }
    @org.springframework.test.context.DynamicPropertySource
    static void uiCors(org.springframework.test.context.DynamicPropertyRegistry properties) {
        properties.add("app.cors.allowed-origin", () -> "http://localhost:" + UI_PORT);
    }
    @org.springframework.boot.test.web.server.LocalServerPort int httpPort;
    @Autowired JdbcTemplate jdbc;
    @Autowired AccountSessionService sessions;
    @Autowired MockMvc mvc;
    @org.springframework.test.context.bean.override.mockito.MockitoBean com.financialhelper.ai.OpenAiStructuredClient ai;

    @Test
    @EnabledIfEnvironmentVariable(named="FH_FSC_CHROME", matches="true")
    void reviewerCanPreviewRealFscMetadataInChromeWithoutSavingDocument() throws Exception {
        var login = sessions.createSession(AccountProvider.NAVER, "fsc-browser-" + UUID.randomUUID(), "fixture");
        jdbc.update("INSERT INTO source_reviewer_grant(account_id,granted_by) VALUES (?,'isolated-fixture')", login.account().getId());
        var frontend = java.nio.file.Path.of("..", "financial-helper-frontend").toAbsolutePath().normalize();
        var builder = new ProcessBuilder("node", "tests/isolated-document-evidence-e2e.mjs").directory(frontend.toFile());
        builder.redirectError(ProcessBuilder.Redirect.INHERIT);
        builder.environment().put("FH_UI_BACKEND_PORT", Integer.toString(httpPort));
        builder.environment().put("FH_UI_FRONTEND_PORT", Integer.toString(UI_PORT));
        builder.environment().put("FH_UI_ACCOUNT_SESSION", login.rawToken());
        builder.environment().put("FH_UI_OTHER_SESSION", login.rawToken());
        builder.environment().put("FH_UI_FSC_PREVIEW_ONLY", "true");
        builder.environment().put("FH_UI_E2E", "true");
        var process = builder.start();
        try {
            assertThat(process.waitFor(180, java.util.concurrent.TimeUnit.SECONDS)).as("FSC browser completion").isTrue();
            assertThat(process.exitValue()).as("FSC browser result").isZero();
            assertThat(new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8)).contains("READY");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM source_document d JOIN source_registry r ON r.id=d.source_registry_id WHERE r.canonical_url=?",
                    Long.class, "https://www.fsc.go.kr/no010101/86471")).isZero();
        } finally {
            if (process.isAlive()) { process.descendants().forEach(ProcessHandle::destroyForcibly); process.destroyForcibly(); }
        }
    }

    @Test void officialPagesReturnMetadataWithoutCreatingDocumentsOrRights() throws Exception {
        var login = sessions.createSession(AccountProvider.NAVER, "fsc-preview-" + UUID.randomUUID(), "fixture");
        jdbc.update("INSERT INTO source_reviewer_grant(account_id,granted_by) VALUES (?,'isolated-fixture')", login.account().getId());
        Cookie cookie = new Cookie(AccountSessionCookie.NAME, login.rawToken());
        String base = "/api/v1/source-review/document-claims/registry-candidates";
        var mapper = tools.jackson.databind.json.JsonMapper.builder().build();
        for (String url : java.util.List.of("https://www.fsc.go.kr/no010101/86471", "https://www.fsc.go.kr/no010101/85901")) {
            String body = mapper.writeValueAsString(java.util.Map.of("domain", "fsc.go.kr", "url", url));
            String registry = mvc.perform(post(base).cookie(cookie).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
            UUID id = UUID.fromString(mapper.readTree(registry).get("registryId").asString());
            String result = mvc.perform(post(base + "/" + id + "/preview").cookie(cookie).with(csrf()))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
            var metadata = mapper.readTree(result);
            assertThat(metadata.get("title").asString()).isNotBlank();
            assertThat(metadata.get("publishedAt").asString()).isNotBlank();
            assertThat(metadata.get("officialUrl").asString()).isEqualTo(url);
            assertThat(metadata.get("usageStatus").asString()).isEqualTo("NOT_REVIEWED");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM source_document WHERE source_registry_id=?", Long.class, id)).isZero();
        }
        org.mockito.Mockito.verifyNoInteractions(ai);
    }
}
