package com.financialhelper.e2e;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Response;
import tools.jackson.databind.json.JsonMapper;

import java.util.UUID;

/** Account-history UI assertions shared by the existing real browser flows. */
final class BrowserE2EAccountHistorySmoke {
    private BrowserE2EAccountHistorySmoke() {}

    static void informationOnly(Page page, UUID consultationId, boolean stale) throws Exception {
        String informationHref = informationHref(consultationId);
        String actionHref = actionHref(consultationId);
        page.navigate("http://localhost:3000/account");
        Locator information = page.locator("a[href='" + informationHref + "']");
        information.waitFor();
        if (information.count() != 1 || page.locator("a[href='" + actionHref + "']").count() != 0)
            throw new IllegalStateException("E2E_INFORMATION_ONLY_HISTORY_LINKS_INVALID");
        if (!information.getByText("공식 정보 결과").first().isVisible())
            throw new IllegalStateException("E2E_INFORMATION_HISTORY_LABEL_MISSING");
        if (page.viewportSize() != null && page.viewportSize().width == 390) {
            noHorizontalOverflow(page, "ACCOUNT_HISTORY");
            var box = information.boundingBox();
            if (box == null || box.x < 0 || box.x + box.width > 390)
                throw new IllegalStateException("E2E_INFORMATION_HISTORY_LINK_CLIPPED");
            boolean focused = false;
            for (int i = 0; i < 40; i++) {
                page.keyboard().press("Tab");
                focused = Boolean.TRUE.equals(page.evaluate("href => document.activeElement?.getAttribute('href') === href", informationHref));
                if (focused) break;
            }
            if (!focused || !Boolean.TRUE.equals(page.evaluate("() => document.activeElement?.matches(':focus-visible')")))
                throw new IllegalStateException("E2E_INFORMATION_HISTORY_TAB_FOCUS_MISSING");
            Response response = page.waitForResponse(r -> r.url().endsWith("/information-report") && r.request().method().equals("GET"),
                    () -> page.keyboard().press("Enter"));
            checkInformationResponse(response, stale);
        } else {
            Response response = page.waitForResponse(r -> r.url().endsWith("/information-report") && r.request().method().equals("GET"), information::click);
            checkInformationResponse(response, stale);
        }
        page.waitForURL("**" + informationHref);
        if (stale) {
            page.getByText("입력 내용이나 근거가 바뀌어 이전 결과를 표시할 수 없어요.").waitFor();
            if (page.getByText("합성 카드 문서").count() != 0) throw new IllegalStateException("E2E_STALE_HISTORY_EXPOSED_EVIDENCE");
            System.out.println("E2E_STAGE_STALE_HISTORY_CLICK_VERIFIED");
        } else {
            page.getByText("상세 출처와 조건 보기").first().click();
            page.getByText("합성 카드 문서").first().waitFor();
            Response reloaded = page.waitForResponse(r -> r.url().endsWith("/information-report") && r.request().method().equals("GET"), page::reload);
            checkInformationResponse(reloaded, false);
            page.getByText("상세 출처와 조건 보기").first().click();
            page.getByText("합성 카드 문서").first().waitFor();
            page.getByText("상세 출처와 조건 보기").first().click();
            System.out.println("E2E_STAGE_INFORMATION_HISTORY_CLICK_RELOAD_VERIFIED");
        }
    }

    static void actionOnly(Page page, UUID consultationId) {
        page.navigate("http://localhost:3000/account");
        Locator action = page.locator("a[href='" + actionHref(consultationId) + "']");
        action.waitFor();
        if (action.count() != 1 || page.locator("a[href='" + informationHref(consultationId) + "']").count() != 0)
            throw new IllegalStateException("E2E_ACTION_ONLY_HISTORY_LINKS_INVALID");
        action.click();
        page.waitForURL("**" + actionHref(consultationId));
        page.getByText("합성 카드 자료의 조건을 확인합니다.").first().waitFor();
        System.out.println("E2E_STAGE_ACTION_HISTORY_CLICK_VERIFIED");
    }

    static void both(Page page, UUID consultationId) throws Exception {
        page.setViewportSize(390, 844);
        page.navigate("http://localhost:3000/account");
        Locator action = page.locator("a[href='" + actionHref(consultationId) + "']");
        Locator information = page.locator("a[href='" + informationHref(consultationId) + "']");
        action.waitFor(); information.waitFor();
        if (action.count() != 1 || information.count() != 1 ||
                page.locator("li").filter(new Locator.FilterOptions().setHas(action)).count() != 1)
            throw new IllegalStateException("E2E_BOTH_HISTORY_LINKS_INVALID");
        noHorizontalOverflow(page, "BOTH_HISTORY");
        for (Locator link : new Locator[] {action, information}) {
            var box = link.boundingBox();
            if (box == null || box.x < 0 || box.x + box.width > 390)
                throw new IllegalStateException("E2E_BOTH_HISTORY_LINK_CLIPPED");
        }
        action.click();
        page.waitForURL("**" + actionHref(consultationId));
        page.getByText("합성 카드 자료의 조건을 확인합니다.").first().waitFor();
        page.navigate("http://localhost:3000/account");
        Response info = page.waitForResponse(r -> r.url().endsWith("/information-report") && r.request().method().equals("GET"),
                () -> page.locator("a[href='" + informationHref(consultationId) + "']").click());
        checkInformationResponse(info, false);
        page.waitForURL("**" + informationHref(consultationId));
        page.getByText("상세 출처와 조건 보기").first().click();
        page.getByText("합성 카드 문서").first().waitFor();
        System.out.println("E2E_STAGE_BOTH_HISTORY_LINKS_VERIFIED");
    }

    static void noHorizontalOverflow(Page page, String stage) {
        Object overflow = page.evaluate("() => document.documentElement.scrollWidth > window.innerWidth");
        if (Boolean.TRUE.equals(overflow)) throw new IllegalStateException("E2E_MOBILE_OVERFLOW_" + stage);
    }

    private static void checkInformationResponse(Response response, boolean stale) throws Exception {
        String expected = stale ? "STALE" : "READY_CANDIDATES";
        if (response.status() != 200 || !expected.equals(JsonMapper.builder().build().readTree(response.body()).get("status").asText()))
            throw new IllegalStateException("E2E_HISTORY_INFORMATION_GET_INVALID");
    }

    private static String informationHref(UUID id) { return "/consultation/information?consultationId=" + id; }
    private static String actionHref(UUID id) { return "/consultation/report?consultationId=" + id; }
}
