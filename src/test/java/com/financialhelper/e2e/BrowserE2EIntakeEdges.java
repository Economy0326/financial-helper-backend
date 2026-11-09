package com.financialhelper.e2e;

import com.financialhelper.account.*;
import com.microsoft.playwright.*;
import com.microsoft.playwright.options.*;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Independent synthetic sessions, real browser and HTTP, isolated database only. */
final class BrowserE2EIntakeEdges {
    private BrowserE2EIntakeEdges() {}
    static void run(ConfigurableApplicationContext context, JdbcTemplate jdbc, FollowUpBrowserE2EHarness.Guard guard) throws Exception {
        if (!guard.equals(FollowUpBrowserE2EHarness.guard(System.getenv())) || !Set.of(context.getEnvironment().getActiveProfiles()).equals(Set.of("browser-e2e"))) throw new IllegalStateException("E2E_EDGES_PROFILE_MISMATCH");
        FollowUpBrowserE2EHarness.verifyContainer(guard);
        if (!guard.databaseName().equals(jdbc.queryForObject("select current_database()", String.class))) throw new IllegalStateException("E2E_EDGES_DATABASE_MISMATCH");
        var properties = context.getBean(AccountProperties.class);
        var accounts = context.getBean(AccountSessionService.class);
        try (Playwright pw = Playwright.create(); Browser browser = pw.chromium().launch(new BrowserType.LaunchOptions().setChannel("chrome"));
             BrowserContext a = browser.newContext(); BrowserContext b = browser.newContext()) {
            for (var session : List.of(a, b)) {
                String raw = accounts.createSession(AccountProvider.KAKAO, BrowserE2EAccountAuthFixture.syntheticSubject(guard.runId()), "E2E_SYNTHETIC_USER").rawToken();
                session.addCookies(List.of(new Cookie(properties.cookieName(), raw).setUrl("http://localhost:8080").setHttpOnly(true).setSecure(false).setSameSite(SameSiteAttribute.LAX)));
            }
            Page page = a.newPage(); page.setDefaultTimeout(20000);
            page.navigate("http://localhost:3000/");
            Locator entry = page.locator("a[href='/consultation/entry']");
            if (entry.count() != 1) throw new IllegalStateException("E2E_HOME_CTA_NOT_UNIQUE");
            entry.click();
            Response ambiguous = submit(page, "E2E_SYNTHETIC_ 카드를 분실하고 보이스피싱으로 송금했어요.");
            var ambiguousBody = JsonMapper.builder().build().readTree(ambiguous.body());
            if (ambiguous.status() != 200 || !"NEEDS_CLARIFICATION".equals(ambiguousBody.get("status").asText()) || ambiguousBody.get("scenarioCandidates").size() < 2 || page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("맞아요, 계속하기")).count() != 0) throw new IllegalStateException("E2E_AMBIGUOUS_FAILED");
            System.out.println("E2E_STAGE_AMBIGUOUS_VERIFIED");
            String id = ambiguousBody.get("consultationId").asText();
            if (!page.getByText("상황을 구분하기 위해").isVisible()) throw new IllegalStateException("E2E_CLARIFICATION_UI_MISSING");
            Response unsupported = submit(page, "E2E_SYNTHETIC_ 체크카드를 분실했어요.");
            if (unsupported.status() != 200 || !"UNSUPPORTED_SCOPE".equals(JsonMapper.builder().build().readTree(unsupported.body()).get("status").asText())) throw new IllegalStateException("E2E_UNSUPPORTED_FAILED");
            System.out.println("E2E_STAGE_UNSUPPORTED_VERIFIED");
            Response single = submit(page, "E2E_SYNTHETIC_ 개인 신용카드를 분실했어요.");
            var singleBody = JsonMapper.builder().build().readTree(single.body());
            if (!"SCENARIO_CONFIRMATION_REQUIRED".equals(singleBody.get("status").asText())) throw new IllegalStateException("E2E_SINGLE_CANDIDATE_FAILED");
            Response confirmed = page.waitForResponse(r -> r.url().endsWith("/information-intake/confirm") && r.request().method().equals("POST"), () -> page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("맞아요, 계속하기")).click());
            if (confirmed.status() != 200) throw new IllegalStateException("E2E_EDGE_CONFIRM_FAILED");
            page.waitForURL("**/consultation/follow-up");
            page.navigate("http://localhost:3000/consultation/intake");
            Response corrected = submit(page, "E2E_SYNTHETIC_ 카드를 분실하고 보이스피싱으로 송금했어요.");
            var correctedBody = JsonMapper.builder().build().readTree(corrected.body());
            if (!"NEEDS_CLARIFICATION".equals(correctedBody.get("status").asText()) || !correctedBody.get("confirmedScenario").isNull()) throw new IllegalStateException("E2E_CORRECTION_NOT_INVALIDATED");
            System.out.println("E2E_STAGE_CORRECTION_VERIFIED");
            long rev = correctedBody.get("caseInputRevision").asLong();
            String draft = "E2E_SYNTHETIC_ 개인 신용카드 분실과 모르는 결제";
            page.locator("#situation").fill(draft);
            String csrfA = a.request().get("http://localhost:8080/api/v1/session").headers().get("x-xsrf-token");
            if (csrfA == null) throw new IllegalStateException("E2E_CSRF_A_MISSING");
            var concurrent = a.request().put("http://localhost:8080/api/v1/consultations/" + id + "/information-intake", RequestOptions.create().setHeader("X-XSRF-TOKEN", csrfA).setData(Map.of("situationText", "E2E_SYNTHETIC_ 신용카드를 잃어버렸어요.", "expectedCaseInputRevision", rev)));
            if (concurrent.status() != 200) throw new IllegalStateException("E2E_CONCURRENT_UPDATE_FAILED");
            Response[] conflictHolder = new Response[1];
            page.waitForResponse(r -> r.url().endsWith("/information-intake") && r.request().method().equals("GET"), () ->
                    conflictHolder[0] = page.waitForResponse(r -> r.url().endsWith("/information-intake") && r.request().method().equals("PUT"), () -> page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("입력 내용 확인")).click()));
            if (conflictHolder[0].status() != 409 || !draft.equals(page.locator("#situation").inputValue())) throw new IllegalStateException("E2E_CONFLICT_DRAFT_LOST");
            page.getByText("상담 내용이 변경됐어요.").waitFor();
            Response retry = page.waitForResponse(r -> r.url().endsWith("/information-intake") && r.request().method().equals("PUT"), () -> page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("입력 내용 확인")).click());
            if (retry.status() != 200) throw new IllegalStateException("E2E_CONFLICT_RETRY_FAILED");
            System.out.println("E2E_STAGE_INTAKE_EDGES_VERIFIED");
            Page other = b.newPage(); other.setDefaultTimeout(20000); other.navigate("http://localhost:3000/");
            Locator otherEntry = other.locator("a[href='/consultation/entry']");
            if (otherEntry.count() != 1) throw new IllegalStateException("E2E_HOME_CTA_NOT_UNIQUE");
            otherEntry.click();
            other.locator("#situation").waitFor();
            Response ownB = submit(other, "E2E_SYNTHETIC_ 개인 신용카드를 분실했어요.");
            if (ownB.status() != 200) throw new IllegalStateException("E2E_CSRF_B_OWN_REQUEST_FAILED");
            String csrfB = b.request().get("http://localhost:8080/api/v1/session").headers().get("x-xsrf-token");
            if (csrfB == null) throw new IllegalStateException("E2E_CSRF_B_MISSING");
            b.addCookies(List.of(new Cookie("XSRF-TOKEN", csrfB).setUrl("http://localhost:8080").setHttpOnly(false).setSecure(false).setSameSite(SameSiteAttribute.LAX)));
            String browserCookies = b.cookies("http://localhost:8080").stream().map(cookie -> cookie.name + "=" + cookie.value).collect(Collectors.joining("; "));
            System.out.println("E2E_STAGE_B_COOKIE_PRESENT_XSRF_" + b.cookies("http://localhost:8080").stream().anyMatch(cookie -> cookie.name.equals("XSRF-TOKEN")) + "_GUEST_" + b.cookies("http://localhost:8080").stream().anyMatch(cookie -> cookie.name.equals("financial_helper_guest")));
            String base = "http://localhost:8080/api/v1/consultations/" + id;
            int intakeGet = b.request().get(base + "/information-intake").status();
            int reportGet = b.request().get(base + "/information-report").status();
            var ownBody = JsonMapper.builder().build().readTree(ownB.body());
            String ownUrl = "http://localhost:8080/api/v1/consultations/" + ownBody.get("consultationId").asText() + "/information-intake";
            var ownUpdate = b.request().put(ownUrl, RequestOptions.create().setHeader("X-XSRF-TOKEN", csrfB).setHeader("Cookie", browserCookies).setData(Map.of("situationText", "E2E_SYNTHETIC_ 카드 분실", "expectedCaseInputRevision", ownBody.get("caseInputRevision").asLong())));
            int ownPut = ownUpdate.status();
            System.out.println("E2E_STAGE_OWNER_CONTROL_" + ownPut);
            if (ownPut != 200) throw new IllegalStateException("E2E_CROSS_OWNER_CONTROL_FAILED_" + ownPut);
            var beforeA = a.request().get(base + "/information-intake");
            var crossUpdate = b.request().put(base + "/information-intake", RequestOptions.create().setHeader("X-XSRF-TOKEN", csrfB).setHeader("Cookie", browserCookies).setData(Map.of("situationText", "E2E_SYNTHETIC_ 타인 상담 변경 시도", "expectedCaseInputRevision", rev)));
            int intakePut = crossUpdate.status();
            var afterA = a.request().get(base + "/information-intake");
            System.out.println("E2E_STAGE_CROSS_OWNER_STATUS_" + intakeGet + "_" + reportGet + "_" + intakePut);
            if (beforeA.status() != 200 || afterA.status() != 200 || !beforeA.text().equals(afterA.text())) throw new IllegalStateException("E2E_CROSS_OWNER_MUTATED_A");
            if (intakeGet != 404 || reportGet != 404 || (intakePut != 403 && intakePut != 404)) throw new IllegalStateException("E2E_CROSS_OWNER_FAILED");
            System.out.println("E2E_STAGE_CROSS_OWNER_VERIFIED");
            Response reconfirm = page.waitForResponse(r -> r.url().endsWith("/information-intake/confirm") && r.request().method().equals("POST"), () -> page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("맞아요, 계속하기")).click());
            if (reconfirm.status() != 200) throw new IllegalStateException("E2E_UNKNOWN_INSTITUTION_CONFIRM_FAILED");
            page.waitForURL("**/consultation/follow-up");
            page.getByRole(AriaRole.LINK, new Page.GetByRoleOptions().setName("공식 정보 확인하기")).click();
            Response info = page.waitForResponse(r -> r.url().endsWith("/information-report/prepare") && r.request().method().equals("POST"), () -> page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("공식 정보 찾기")).click());
            var body = JsonMapper.builder().build().readTree(info.body());
            if (info.status() != 200) throw new IllegalStateException("E2E_UNKNOWN_INSTITUTION_HTTP_FAILED");
            String status = body.get("status").asText();
            if ("READY_CANDIDATES".equals(status)) {
                if (!body.get("snapshot").get("missingFacts").toString().contains("INSTITUTION")) throw new IllegalStateException("E2E_UNKNOWN_INSTITUTION_FACT_MISSING");
                for (var evidence : body.get("selectedEvidence")) {
                    if (!"COMMON".equals(evidence.get("scopeKind").asText()) || !evidence.get("applicableInstitution").isNull()) throw new IllegalStateException("E2E_INSTITUTION_SCOPE_CONFUSED");
                }
            } else if (!"INSUFFICIENT_EVIDENCE".equals(status)) throw new IllegalStateException("E2E_UNKNOWN_INSTITUTION_UNSAFE_STATUS");
            System.out.println("E2E_STAGE_UNKNOWN_INSTITUTION_" + status);
            page.route("**/information-report/prepare", route -> route.abort());
            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("공식 정보 찾기")).click();
            page.getByText("서버에 연결하지 못했어요. 연결 상태를 확인해 주세요.").waitFor();
            System.out.println("E2E_STAGE_NETWORK_ERROR_VERIFIED");
            page.unroute("**/information-report/prepare");
        }
    }    private static Response submit(Page page, String text) {
        page.locator("#situation").fill(text);
        return page.waitForResponse(r -> r.url().endsWith("/information-intake") && r.request().method().equals("PUT"), () -> page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("입력 내용 확인")).click());
    }
}
