package com.financialhelper.e2e;

import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.AriaRole;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import static org.assertj.core.api.Assertions.assertThat;

/** Runs only against an explicitly started, owned persistent local demo. */
class PersistentLocalDemoChromeSmokeTest {
    @Test
    @EnabledIfEnvironmentVariable(named = "FH_PERSISTENT_DEMO_CHROME", matches = "true")
    void anonymousReviewerPageAndApprovalBoundary() {
        try (Playwright playwright = Playwright.create();
             var browser = playwright.chromium().launch(
                     new BrowserType.LaunchOptions().setChannel("chrome"))) {
            var page = browser.newPage();
            page.navigate("http://127.0.0.1:3000/official-source-review/deposit");
            assertThat(page.getByRole(AriaRole.HEADING,
                    new com.microsoft.playwright.Page.GetByRoleOptions().setName("예금자보호 공식 자료 검토")).count())
                    .isEqualTo(1);
            assertThat(page.getByRole(AriaRole.LINK,
                    new com.microsoft.playwright.Page.GetByRoleOptions().setName("로그인 화면 열기")).count())
                    .isEqualTo(1);
            var response = page.request().post("http://127.0.0.1:8080/api/v1/source-review/deposit/ingest");
            assertThat(response.status()).isEqualTo(403);
        }
    }
}
