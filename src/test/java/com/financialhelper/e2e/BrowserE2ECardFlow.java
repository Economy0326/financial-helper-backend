package com.financialhelper.e2e;

import com.financialhelper.account.AccountProperties;
import com.financialhelper.account.AccountProvider;
import com.financialhelper.account.AccountSessionService;
import com.microsoft.playwright.*;
import com.microsoft.playwright.options.Cookie;
import com.microsoft.playwright.options.SameSiteAttribute;
import com.microsoft.playwright.options.AriaRole;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;
import java.util.List;
import java.util.Set;

/** Browser flow uses real frontend and backend HTTP; no browser routing mocks. */
final class BrowserE2ECardFlow {
    private BrowserE2ECardFlow() {}
    static void run(ConfigurableApplicationContext context, JdbcTemplate jdbc, FollowUpBrowserE2EHarness.Guard guard) throws Exception {
        if (!guard.equals(FollowUpBrowserE2EHarness.guard(System.getenv())) ||
                !Set.of(context.getEnvironment().getActiveProfiles()).equals(Set.of("browser-e2e"))) throw new IllegalStateException("E2E_FLOW_PROFILE_MISMATCH");
        FollowUpBrowserE2EHarness.verifyContainer(guard);
        if (!guard.databaseName().equals(jdbc.queryForObject("select current_database()", String.class))) throw new IllegalStateException("E2E_FLOW_DATABASE_MISMATCH");
        var properties = context.getBean(AccountProperties.class);
        String token = context.getBean(AccountSessionService.class).createSession(AccountProvider.KAKAO,
                BrowserE2EAccountAuthFixture.syntheticSubject(guard.runId()), "E2E_SYNTHETIC_USER").rawToken();
        try (Playwright playwright = Playwright.create();
             Browser browser = playwright.chromium().launch(new BrowserType.LaunchOptions().setChannel("chrome"));
             BrowserContext bc = browser.newContext()) {
            bc.addCookies(List.of(new Cookie(properties.cookieName(), token).setUrl("http://localhost:8080")
                    .setHttpOnly(true).setSecure(false).setSameSite(SameSiteAttribute.LAX)));
            Page page = bc.newPage();
            page.setDefaultTimeout(20000);
            System.out.println("E2E_STAGE_HOME");
            page.navigate("http://localhost:3000/");
            page.getByRole(AriaRole.LINK, new Page.GetByRoleOptions().setName("금융 피해 상담하기")).click();
            System.out.println("E2E_STAGE_INTAKE_FORM");
            page.locator("#situation").fill("E2E_SYNTHETIC_ 개인 신용카드를 분실했어요.");
            Response update = page.waitForResponse(r -> r.url().endsWith("/information-intake") && r.request().method().equals("PUT"),
                    () -> page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("입력 내용 확인")).click());
            if (update.status() != 200 || !"SCENARIO_CONFIRMATION_REQUIRED".equals(JsonMapper.builder().build().readTree(update.body()).get("status").asText())) throw new IllegalStateException("E2E_INTAKE_UPDATE_FAILED");
            System.out.println("E2E_STAGE_INTAKE_UPDATED");
            Response confirm = page.waitForResponse(r -> r.url().endsWith("/information-intake/confirm") && r.request().method().equals("POST"),
                    () -> page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("맞아요, 계속하기")).click());
            if (confirm.status() != 200 || !"READY".equals(JsonMapper.builder().build().readTree(confirm.body()).get("status").asText())) throw new IllegalStateException("E2E_INTAKE_CONFIRM_FAILED");
            System.out.println("E2E_STAGE_CONFIRMED");
            page.waitForURL("**/consultation/follow-up");
            System.out.println("E2E_STAGE_FOLLOWUP_PREPARE");
            page.waitForFunction("() => { const t = document.body.innerText; return t.includes('질문 준비하기') || t.includes('현재 상담 안내를 확인 중입니다') || t.includes('진행 중인 상담이 없어요') || t.includes('현재 상담 단계와 맞지 않는 화면') || !!document.querySelector('form'); }");
            System.out.println("E2E_STAGE_UI_PREPARE_" + page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("질문 준비하기")).count()
                    + "_BLOCKED_" + page.getByText("현재 상담 안내를 확인 중입니다").count()
                    + "_NO_ACTIVE_" + page.getByText("진행 중인 상담이 없어요.").count()
                    + "_WRONG_STEP_" + page.getByText("현재 상담 단계와 맞지 않는 화면이에요.").count()
                    + "_FORM_" + page.locator("form").count());
            if (page.locator("form").count() == 0) {
                Response followPrepare = page.waitForResponse(r -> r.url().endsWith("/procedure-follow-up/prepare") && r.request().method().equals("POST"),
                        () -> page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("질문 준비하기")).click());
                if (followPrepare.status() != 200) throw new IllegalStateException("E2E_FOLLOWUP_PREPARE_HTTP_" + followPrepare.status());
                System.out.println("E2E_STAGE_FOLLOWUP_PREPARE_CLICKED");
            }
            page.locator("form").waitFor();
            for (int i = 0; i < 12 && page.url().contains("/follow-up"); i++) {
                page.waitForFunction("() => location.pathname.includes('/summary') || !!document.querySelector('form')");
                if (page.url().contains("/summary")) break;
                Locator form = page.locator("form");
                Locator date = form.locator("input[type=date]");
                Locator shortText = form.locator("input[type=text]");
                Locator radios = form.locator("input[type=radio]");
                System.out.println("E2E_STAGE_FOLLOWUP_INPUT_" + i + "_DATE_" + date.count() + "_TEXT_" + shortText.count() + "_RADIO_" + radios.count());
                Locator firstInput = form.locator("input").first();
                String previousInputId = firstInput.count() > 0 ? firstInput.getAttribute("id") : null;
                if (date.count() > 0) date.fill("2026-06-01");
                else if (shortText.count() > 0) shortText.fill("E2E_SYNTHETIC_");
                else if (radios.count() > 0) {
                    String radioId = radios.first().getAttribute("id");
                    if (radioId == null) throw new IllegalStateException("E2E_FOLLOWUP_RADIO_ID_MISSING");
                    page.locator("label[for='" + radioId + "']").click();
                }
                else break;
                System.out.println("E2E_STAGE_FOLLOWUP_SELECTED_" + i);
                Locator submit = form.locator("button[type=submit]");
                if (!submit.isEnabled()) throw new IllegalStateException("E2E_FOLLOW_UP_NOT_SUBMITTABLE");
                Response answer = page.waitForResponse(r -> r.url().contains("/procedure-follow-up/questions/") && r.request().method().equals("PUT"), submit::click);
                var answerBody = JsonMapper.builder().build().readTree(answer.body());
                String answerKind = answerBody.has("kind") ? answerBody.get("kind").asText() : "missing";
                String blockReason = answerBody.hasNonNull("versionBlockReason") ? answerBody.get("versionBlockReason").asText() : "none";
                System.out.println("E2E_STAGE_FOLLOWUP_RESPONSE_" + answer.status() + "_KIND_" + answerKind.replaceAll("[^a-zA-Z_]", "") + "_BLOCK_" + blockReason.replaceAll("[^a-zA-Z_]", ""));
                if (answer.status() != 200) throw new IllegalStateException("E2E_FOLLOW_UP_ANSWER_FAILED");
                if (previousInputId != null) page.waitForFunction("id => !location.pathname.includes(`/follow-up`) || !document.getElementById(id)", previousInputId);
            }
            System.out.println("E2E_STAGE_FOLLOWUP_COMPLETING");
            page.waitForURL("**/consultation/summary");
            page.getByRole(AriaRole.LINK, new Page.GetByRoleOptions().setName("공식 정보 확인하기")).click();
            System.out.println("E2E_STAGE_REPORT");
            Response prepare = page.waitForResponse(r -> r.url().endsWith("/information-report/prepare") && r.request().method().equals("POST"),
                    () -> page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("공식 정보 찾기")).click());
            var prepared = JsonMapper.builder().build().readTree(prepare.body());
            if (prepare.status() != 200 || !"READY_CANDIDATES".equals(prepared.get("status").asText())) throw new IllegalStateException("E2E_REPORT_PREPARE_FAILED");
            var selected = prepared.get("selectedEvidence");
            if (selected == null || selected.size() != 1 || selected.get(0).get("conditions").size() != 1
                    || selected.get(0).get("exceptions").size() != 1
                    || !"COMMON".equals(selected.get(0).get("scopeKind").asText())) throw new IllegalStateException("E2E_REPORT_EVIDENCE_MISSING");
            String supportId = selected.get(0).get("supportChunkId").asText();
            if (!"UNKNOWN".equals(prepared.get("evidenceProvenance").get(supportId).asText())) throw new IllegalStateException("E2E_PROVENANCE_INVALID");
            var consultationId = java.util.UUID.fromString(prepared.get("consultationId").asText());
            Integer reports = jdbc.queryForObject("select count(*) from information_report where consultation_id = ?", Integer.class, consultationId);
            if (reports == null || reports < 1) throw new IllegalStateException("E2E_REPORT_NOT_PERSISTED");
            page.getByText("상세 출처와 조건 보기").first().click();
            if (!page.getByText("합성 카드 문서").first().isVisible() || !page.getByText("출처 신원: 확인되지 않음").first().isVisible()) throw new IllegalStateException("E2E_REPORT_SOURCE_MISSING");
            page.setViewportSize(390, 844);
            Object overflow = page.evaluate("() => document.documentElement.scrollWidth > window.innerWidth");
            if (Boolean.TRUE.equals(overflow)) throw new IllegalStateException("E2E_MOBILE_OVERFLOW");
            page.getByRole(AriaRole.LINK, new Page.GetByRoleOptions().setName("상담으로 돌아가기")).focus();
            page.keyboard().press("Tab");
            Object focused = page.evaluate("() => document.activeElement !== null && document.activeElement !== document.body && document.activeElement.tagName === 'BUTTON'");
            if (!Boolean.TRUE.equals(focused)) throw new IllegalStateException("E2E_MOBILE_KEYBOARD_FOCUS");
            System.out.println("E2E_STAGE_MOBILE_KEYBOARD_VERIFIED");
            try (BrowserContext other = browser.newContext()) {
                var forbidden = other.request().get("http://localhost:8080/api/v1/consultations/" + consultationId + "/information-report");
                if (forbidden.status() != 401 && forbidden.status() != 403 && forbidden.status() != 404) throw new IllegalStateException("E2E_UNAUTHENTICATED_ACCESS_ALLOWED");
            }
            var noCsrf = bc.request().post("http://localhost:8080/api/v1/consultations/" + consultationId + "/information-report/prepare",
                    com.microsoft.playwright.options.RequestOptions.create().setHeader("Content-Type", "application/json").setData("{}"));
            if (noCsrf.status() != 403) throw new IllegalStateException("E2E_CSRF_NOT_REJECTED");
            System.out.println("E2E_STAGE_AUTH_CSRF_VERIFIED");
            Response reload = page.waitForResponse(r -> r.url().endsWith("/information-report") && r.request().method().equals("GET"), page::reload);
            if (reload.status() != 200 || !"READY_CANDIDATES".equals(JsonMapper.builder().build().readTree(reload.body()).get("status").asText())) throw new IllegalStateException("E2E_REPORT_GET_FAILED");
            int revoked = jdbc.update("UPDATE source_applicability_review SET review_status = 'REVOKED', revoked_at = CURRENT_TIMESTAMP, revoked_by = 'synthetic-fixture', revocation_reason = 'synthetic revocation' WHERE id IN (SELECT r.id FROM source_applicability_review r JOIN source_document d ON d.id = r.source_document_id JOIN source_registry s ON s.id = d.source_registry_id WHERE s.source_key = ?) AND review_status = 'APPROVED'", "browser-e2e-" + guard.runId());
            if (revoked != 1) throw new IllegalStateException("E2E_REVIEW_REVOCATION_FAILED");
            Response stale = page.waitForResponse(r -> r.url().endsWith("/information-report") && r.request().method().equals("GET"),
                    () -> page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("저장된 결과 다시 확인")).click());
            var staleBody = JsonMapper.builder().build().readTree(stale.body());
            if (stale.status() != 200 || !"STALE".equals(staleBody.get("status").asText()) || staleBody.get("selectedEvidence").size() != 0) throw new IllegalStateException("E2E_STALE_FAILED");
            if (!page.getByText("입력 내용이나 근거가 바뀌어 이전 결과를 표시할 수 없어요.").isVisible()
                    || page.getByText("합성 카드 문서").count() != 0) throw new IllegalStateException("E2E_STALE_UI_FAILED");
            System.out.println("CARD_BROWSER_HTTP_VERIFIED");
            System.out.println("E2E_STAGE_STALE_VERIFIED");
        }
    }
}