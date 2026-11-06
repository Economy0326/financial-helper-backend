package com.financialhelper.e2e;

import com.financialhelper.account.AccountProperties;
import com.financialhelper.account.AccountProvider;
import com.financialhelper.account.AccountSessionService;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.Response;
import com.microsoft.playwright.options.Cookie;
import com.microsoft.playwright.options.SameSiteAttribute;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Test-only account fixture. The raw session token stays in this JVM and Playwright's browser context. */
final class BrowserE2EAccountAuthFixture {
    private static final String FRONTEND = "http://localhost:3000/consultation/entry";
    private static final String SESSION = "http://localhost:8080/api/v1/session";

    private BrowserE2EAccountAuthFixture() { }

    static void verify(ConfigurableApplicationContext context, JdbcTemplate jdbc,
            FollowUpBrowserE2EHarness.Guard guard) throws Exception {
        requireBoundary(context, jdbc, guard);
        AccountProperties properties = context.getBean(AccountProperties.class);
        if (!"financial_helper_account".equals(properties.cookieName()) || properties.cookieSecure()
                || !"Lax".equals(properties.cookieSameSite()) || !properties.generalConsultationRequired()) {
            throw new IllegalStateException("AUTH_BROWSER_COOKIE_CONTRACT_MISMATCH");
        }
        String subject = syntheticSubject(guard.runId());
        String rawToken = context.getBean(AccountSessionService.class)
                .createSession(AccountProvider.KAKAO, subject, "E2E_SYNTHETIC_USER").rawToken();
        try (Playwright playwright = Playwright.create();
             Browser browser = playwright.chromium().launch(
                     new BrowserType.LaunchOptions().setChannel("chrome"));
             BrowserContext authenticated = browser.newContext();
             BrowserContext isolated = browser.newContext();
             BrowserContext invalid = browser.newContext()) {
            authenticated.addCookies(List.of(new Cookie(properties.cookieName(), rawToken)
                    .setUrl("http://localhost:8080")
                    .setHttpOnly(true).setSecure(false).setSameSite(SameSiteAttribute.LAX)));
            invalid.addCookies(List.of(new Cookie(properties.cookieName(), "invalid-synthetic-session")
                    .setUrl("http://localhost:8080")
                    .setHttpOnly(true).setSecure(false).setSameSite(SameSiteAttribute.LAX)));
            assertSession(authenticated, true);
            assertSession(isolated, false);
            assertSession(invalid, false);
        }
        System.out.println("AUTH_BROWSER_VERIFIED authenticated=true isolated=false invalid=false");
    }

    static String syntheticSubject(String runId) {
        if (runId == null || !runId.matches("[a-z][a-z0-9]{7,23}")) {
            throw new IllegalArgumentException("AUTH_BROWSER_INVALID_RUN_ID");
        }
        return "browser-e2e:" + runId + ":" + UUID.randomUUID();
    }

    private static void requireBoundary(ConfigurableApplicationContext context, JdbcTemplate jdbc,
            FollowUpBrowserE2EHarness.Guard guard) throws Exception {
        FollowUpBrowserE2EHarness.Guard current = FollowUpBrowserE2EHarness.guard(System.getenv());
        if (!current.equals(guard) || !Set.of(context.getEnvironment().getActiveProfiles()).equals(Set.of("browser-e2e"))) {
            throw new IllegalStateException("AUTH_BROWSER_PROFILE_OR_RUN_MISMATCH");
        }
        FollowUpBrowserE2EHarness.verifyContainer(guard);
        String database = jdbc.queryForObject("select current_database()", String.class);
        if (!guard.databaseName().equals(database)) {
            throw new IllegalStateException("AUTH_BROWSER_DATABASE_MISMATCH");
        }
        for (String key : List.of("app.auth.kakao.enabled", "app.auth.naver.enabled",
                "app.law-open-api.enabled", "app.source-ingestion.run-on-startup",
                "app.ai.smoke-test-enabled")) {
            if (!"false".equalsIgnoreCase(context.getEnvironment().getProperty(key))) {
                throw new IllegalStateException("AUTH_BROWSER_EXTERNAL_INTEGRATION_ENABLED");
            }
        }
        if (!"browser-e2e-stub-only".equals(context.getEnvironment().getProperty("app.ai.openai.api-key"))) {
            throw new IllegalStateException("AUTH_BROWSER_AI_STUB_MISMATCH");
        }
    }

    private static void assertSession(BrowserContext browserContext, boolean expected) {
        Page page = browserContext.newPage();
        Response response = page.waitForResponse(
                candidate -> SESSION.equals(candidate.url()) && "GET".equals(candidate.request().method()),
                () -> page.navigate(FRONTEND));
        if (response.status() != 200) {
            throw new IllegalStateException("AUTH_BROWSER_SESSION_HTTP_FAILED");
        }
        try {
            var body = JsonMapper.builder().build().readTree(response.body());
            if (body == null || body.get("authenticated") == null
                    || body.get("authenticated").asBoolean() != expected) {
                throw new IllegalStateException("AUTH_BROWSER_SESSION_STATE_MISMATCH");
            }
        } catch (IllegalStateException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException("AUTH_BROWSER_SESSION_RESPONSE_INVALID");
        }
    }
}
