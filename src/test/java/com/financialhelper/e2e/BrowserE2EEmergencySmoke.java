package com.financialhelper.e2e;

import com.microsoft.playwright.*;
import com.microsoft.playwright.options.AriaRole;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.Set;

final class BrowserE2EEmergencySmoke {
    static void run(ConfigurableApplicationContext context, JdbcTemplate jdbc, FollowUpBrowserE2EHarness.Guard guard) throws Exception {
        if (!guard.equals(FollowUpBrowserE2EHarness.guard(System.getenv())) || !Set.of(context.getEnvironment().getActiveProfiles()).equals(Set.of("browser-e2e"))) throw new IllegalStateException("E2E_EMERGENCY_PROFILE_MISMATCH");
        FollowUpBrowserE2EHarness.verifyContainer(guard);
        if (!guard.databaseName().equals(jdbc.queryForObject("select current_database()", String.class))) throw new IllegalStateException("E2E_EMERGENCY_DATABASE_MISMATCH");
        try (Playwright pw = Playwright.create(); Browser browser = pw.chromium().launch(new BrowserType.LaunchOptions().setChannel("chrome")); BrowserContext bc = browser.newContext()) {
            Page page = bc.newPage(); page.setDefaultTimeout(20000); page.navigate("http://localhost:3000/emergency/type");
            page.locator("label[for='emergency-type-TRANSFER']").click();
            Response select = page.waitForResponse(r -> r.url().endsWith("/emergency/selection") && r.request().method().equals("PUT"), () -> page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("다음")).click());
            if (select.status() != 200) throw new IllegalStateException("E2E_EMERGENCY_SELECTION_FAILED");
            page.waitForURL("**/emergency/action");
            page.getByText("지금 해야 할 행동").waitFor();
            page.getByText("이 행동은 하지 마세요").waitFor();
            System.out.println("E2E_STAGE_EMERGENCY_SMOKE_VERIFIED");
            try (BrowserContext explicit = browser.newContext()) {
                var properties = context.getBean(com.financialhelper.account.AccountProperties.class);
                String raw = context.getBean(com.financialhelper.account.AccountSessionService.class).createSession(com.financialhelper.account.AccountProvider.KAKAO, BrowserE2EAccountAuthFixture.syntheticSubject(guard.runId()), "E2E_SYNTHETIC_USER").rawToken();
                explicit.addCookies(java.util.List.of(new com.microsoft.playwright.options.Cookie(properties.cookieName(), raw).setUrl("http://localhost:8080").setHttpOnly(true).setSecure(false).setSameSite(com.microsoft.playwright.options.SameSiteAttribute.LAX)));
                Page category = explicit.newPage(); category.setDefaultTimeout(20000);
                category.navigate("http://localhost:3000/consultation/problem-category");
                category.locator("label[for='consultation-type-CARD_LOSS_UNAUTHORIZED_USE']").click();
                Response selected = category.waitForResponse(r -> r.url().endsWith("/category") && r.request().method().equals("PUT"), () -> category.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("다음")).click());
                if (selected.status() != 200) throw new IllegalStateException("E2E_EXPLICIT_CATEGORY_FAILED");
                category.waitForURL("**/consultation/situation");
                System.out.println("E2E_STAGE_EXPLICIT_CATEGORY_VERIFIED");
            }
        }
    }
}