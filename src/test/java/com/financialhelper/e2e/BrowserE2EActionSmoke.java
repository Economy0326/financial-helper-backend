package com.financialhelper.e2e;

import com.financialhelper.account.*;
import com.microsoft.playwright.*;
import com.microsoft.playwright.options.*;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.List;
import java.util.Set;

/** Legacy ACTION path through real Chrome, Next.js, Spring HTTP and disposable PostgreSQL. */
final class BrowserE2EActionSmoke {
    private BrowserE2EActionSmoke() {}
    static void run(ConfigurableApplicationContext context, JdbcTemplate jdbc, FollowUpBrowserE2EHarness.Guard guard) throws Exception {
        if (!guard.equals(FollowUpBrowserE2EHarness.guard(System.getenv())) || !Set.of(context.getEnvironment().getActiveProfiles()).equals(Set.of("browser-e2e"))) throw new IllegalStateException("E2E_ACTION_PROFILE_MISMATCH");
        FollowUpBrowserE2EHarness.verifyContainer(guard);
        if (!guard.databaseName().equals(jdbc.queryForObject("select current_database()", String.class))) throw new IllegalStateException("E2E_ACTION_DATABASE_MISMATCH");
        var properties = context.getBean(AccountProperties.class);
        String token = context.getBean(AccountSessionService.class).createSession(AccountProvider.KAKAO,
                BrowserE2EAccountAuthFixture.syntheticSubject(guard.runId()), "E2E_SYNTHETIC_USER").rawToken();
        try (Playwright pw = Playwright.create(); Browser browser = pw.chromium().launch(new BrowserType.LaunchOptions().setChannel("chrome")); BrowserContext bc = browser.newContext()) {
            bc.addCookies(List.of(new Cookie(properties.cookieName(), token).setUrl("http://localhost:8080").setHttpOnly(true).setSecure(false).setSameSite(SameSiteAttribute.LAX)));
            Page page = bc.newPage(); page.setDefaultTimeout(20000);
            page.onResponse(response -> { if (response.url().contains("/procedure-follow-up/prepare")) System.out.println("E2E_STAGE_ACTION_PREPARE_HTTP_" + response.status()); });
            page.navigate("http://localhost:3000/consultation/problem-category");
            page.locator("label[for='consultation-type-CARD_LOSS_UNAUTHORIZED_USE']").click();
            Response category = page.waitForResponse(r -> r.url().endsWith("/category") && r.request().method().equals("PUT"), () -> page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("다음")).click());
            if (category.status() != 200) throw new IllegalStateException("E2E_ACTION_CATEGORY_FAILED");
            page.waitForURL("**/consultation/situation");
            System.out.println("E2E_STAGE_ACTION_SITUATION_FORM");
            page.locator("#situation-content").fill("E2E_SYNTHETIC_ KB국민카드 개인 신용카드를 분실했어요.");
            Response situation = page.waitForResponse(r -> r.url().endsWith("/situation") && r.request().method().equals("PUT"), () -> page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("다음")).click());
            if (situation.status() != 200) throw new IllegalStateException("E2E_ACTION_SITUATION_FAILED_" + situation.status());
            System.out.println("E2E_STAGE_ACTION_SITUATION_HTTP_" + situation.status());
            var situationJson = tools.jackson.databind.json.JsonMapper.builder().build().readTree(situation.body());
            System.out.println("E2E_STAGE_ACTION_ALIGNMENT_" + (situationJson.hasNonNull("scenarioAlignment") ? situationJson.get("scenarioAlignment").asText() : "NONE") + "_STEP_" + (situationJson.hasNonNull("currentStep") ? situationJson.get("currentStep").asText() : "NONE"));
            page.waitForURL("**/consultation/follow-up");
            System.out.println("E2E_STAGE_ACTION_FOLLOWUP_STARTED");
            if (page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("질문 준비하기")).count() > 0) {
                Response prep = page.waitForResponse(r -> r.url().endsWith("/procedure-follow-up/prepare") && r.request().method().equals("POST"), () -> page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("질문 준비하기")).click());
                if (prep.status() != 200) throw new IllegalStateException("E2E_ACTION_FOLLOWUP_PREPARE_FAILED");
            }
            for (int i = 0; i < 12 && page.url().contains("/follow-up"); i++) {
                page.waitForFunction("() => location.pathname.includes('/summary') || !!document.querySelector('form')");
                if (page.url().contains("/summary")) break;
                Locator form = page.locator("form");
                Locator date = form.locator("input[type=date]");
                Locator shortText = form.locator("input[type=text]");
                Locator radios = form.locator("input[type=radio]");
                String oldId = form.locator("input").first().getAttribute("id");
                if (date.count() > 0) date.fill("2026-06-01");
                else if (shortText.count() > 0) shortText.fill("E2E_SYNTHETIC_");
                else if (radios.count() > 0) {
                    String questionText = form.innerText();
                    boolean answerNo = questionText.contains("본인이 하지 않은 카드 결제가 있나요?") || questionText.contains("최근 카드 이용내역에서 기억나지 않는 결제") || questionText.contains("분실·도난 신고를 했나요?") || questionText.contains("KB국민카드에 카드 분실 사실을 이미 접수했는지");
                    Locator answerRadio = answerNo ? radios.nth(1) : radios.first();
                    page.locator("label[for='" + answerRadio.getAttribute("id") + "']").click();
                }
                else throw new IllegalStateException("E2E_ACTION_FOLLOWUP_INPUT_MISSING");
                Response answer = page.waitForResponse(r -> r.url().contains("/procedure-follow-up/questions/") && r.request().method().equals("PUT"), () -> form.locator("button[type=submit]").click());
                if (answer.status() != 200) throw new IllegalStateException("E2E_ACTION_FOLLOWUP_ANSWER_FAILED_" + answer.status());
                page.waitForFunction("id => !location.pathname.includes('/follow-up') || !document.getElementById(id)", oldId);
            }
            page.waitForURL("**/consultation/summary");
            System.out.println("E2E_STAGE_ACTION_SUMMARY");
            Response confirm = page.waitForResponse(r -> r.url().endsWith("/summary/confirm") && r.request().method().equals("POST"), () -> page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("맞아요")).click());
            if (confirm.status() != 200) throw new IllegalStateException("E2E_ACTION_SUMMARY_CONFIRM_FAILED_" + confirm.status());
            page.waitForURL("**/consultation/analysis");
            System.out.println("E2E_STAGE_ACTION_ANALYSIS");
            try {
                page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("결과 보기")).waitFor();
            } catch (TimeoutError timeout) {
                var jobs = jdbc.queryForList("SELECT status, failure_code, evidence_failure_reason FROM analysis_job ORDER BY created_at DESC LIMIT 1");
                if (!jobs.isEmpty()) {
                    var job = jobs.getFirst();
                    String statusCode = String.valueOf(job.get("status")).replaceAll("[^A-Z0-9_]", "");
                    String failureCode = String.valueOf(job.get("failure_code")).replaceAll("[^A-Z0-9_]", "");
                    String evidenceCode = String.valueOf(job.get("evidence_failure_reason")).replaceAll("[^A-Z0-9_]", "");
                    System.out.println("E2E_STAGE_ACTION_JOB_" + statusCode + "_" + failureCode + "_" + evidenceCode);
                    java.util.UUID analysisConsultation = jdbc.queryForObject("SELECT consultation_id FROM analysis_job ORDER BY created_at DESC LIMIT 1", java.util.UUID.class);
                    var plan = context.getBean(com.financialhelper.procedure.FinancialActionPlanService.class).buildForCurrent(analysisConsultation);
                    System.out.println("E2E_STAGE_ACTION_PLAN_" + plan.status() + "_MISSING_" + String.join("_", plan.unresolvedFacts()) + "_GAPS_" + String.join("_", plan.coverageGaps()));
                    throw new IllegalStateException("E2E_ACTION_ANALYSIS_TERMINAL");
                }
                throw new IllegalStateException("E2E_ACTION_ANALYSIS_NO_JOB");
            }
            Response report = page.waitForResponse(r -> r.url().endsWith("/report/prepare") && r.request().method().equals("POST"), () -> page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("결과 보기")).click());
            if (report.status() != 200) throw new IllegalStateException("E2E_ACTION_REPORT_PREPARE_FAILED_" + report.status());
            page.waitForURL("**/consultation/report?consultationId=*");
            page.getByText("분실·도난 신고").first().waitFor();
            page.getByText("합성 카드 자료의 조건을 확인합니다.").first().waitFor();
            System.out.println("E2E_STAGE_ACTION_REPORT_READY");
            page.reload();
            page.getByText("분실·도난 신고").first().waitFor();
            page.getByText("합성 카드 자료의 조건을 확인합니다.").first().waitFor();
            System.out.println("E2E_STAGE_ACTION_REPORT_RELOADED");
        }
    }
}
