package com.financialhelper.e2e;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.Cookie;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class BrowserE2EPlaywrightProbeTest {
    @Test
    void installedChromeSupportsIsolatedContextsAndHttpOnlyCookie() {
        try (Playwright playwright = Playwright.create();
             Browser browser = playwright.chromium().launch(
                     new BrowserType.LaunchOptions().setChannel("chrome"))) {
            try (BrowserContext authenticated = browser.newContext();
                 BrowserContext isolated = browser.newContext()) {
                authenticated.addCookies(List.of(new Cookie("financial_helper_account", "synthetic-probe-only")
                        .setUrl("http://localhost:8080")
                        .setHttpOnly(true)
                        .setSecure(false)));
                assertThat(authenticated.cookies("http://localhost:8080"))
                        .anySatisfy(cookie -> {
                            assertThat(cookie.name).isEqualTo("financial_helper_account");
                            assertThat(cookie.httpOnly).isTrue();
                        });
                assertThat(isolated.cookies("http://localhost:8080")).isEmpty();
            }
        }
    }
}
