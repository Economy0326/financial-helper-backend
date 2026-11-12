package com.financialhelper.e2e;

import com.financialhelper.account.AccountProperties;
import com.financialhelper.account.AccountProvider;
import com.financialhelper.account.AccountSessionService;
import com.financialhelper.guest.GuestSessionCookie;
import com.financialhelper.retrieval.KureRuntimeClient;
import com.financialhelper.source.VerifiedPdfMetadata;
import com.microsoft.playwright.*;
import com.microsoft.playwright.options.AriaRole;
import com.microsoft.playwright.options.Cookie;
import com.microsoft.playwright.options.SameSiteAttribute;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Real Chrome → Next → Spring → PostgreSQL → pinned local KURE, with test-only review. */
final class BrowserE2EDepositFlow {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final List<Case> ANSWERABLE = List.of(
            new Case("예금자보호 한도가 얼마인가요?", 6, true),
            new Case("같은 금융기관에 통장이 여러 개라면?", 8, false),
            new Case("서로 다른 금융기관의 예금은?", 9, false),
            new Case("ISA 안의 금융상품은 무조건 보호되나요?", 2, true),
            new Case("원금과 소정의 이자는 어떻게 계산되나요?", 6, true),
            new Case("퇴직연금·연금저축 등의 별도 보호한도는?", 10, true));

    private BrowserE2EDepositFlow() { }

    static void run(ConfigurableApplicationContext context, JdbcTemplate jdbc,
                    FollowUpBrowserE2EHarness.Guard guard) throws Exception {
        if (!guard.equals(FollowUpBrowserE2EHarness.guard(System.getenv()))
                || !Set.of(context.getEnvironment().getActiveProfiles()).equals(Set.of("browser-e2e"))
                || !(context.getBean(KureRuntimeClient.class) instanceof com.financialhelper.retrieval.KureRuntimeHttpClient)
                || !guard.databaseName().equals(jdbc.queryForObject("select current_database()", String.class))) {
            throw new IllegalStateException("DEPOSIT_E2E_REAL_BOUNDARY_INVALID");
        }
        FollowUpBrowserE2EHarness.verifyContainer(guard);
        AccountProperties properties = context.getBean(AccountProperties.class);
        var sessions = context.getBean(AccountSessionService.class);
        StaleTarget staleTarget = null;
        try (Playwright playwright = Playwright.create();
             Browser browser = playwright.chromium().launch(new BrowserType.LaunchOptions().setChannel("chrome"))) {
            for (Case item : ANSWERABLE) {
                System.out.println("DEPOSIT_BROWSER_START_Q" + item.questionNumber());
                String token = sessions.createSession(AccountProvider.KAKAO,
                        BrowserE2EAccountAuthFixture.syntheticSubject(guard.runId()),
                        "E2E_SYNTHETIC_USER").rawToken();
                try (BrowserContext bc = browser.newContext()) {
                    bc.addCookies(List.of(new Cookie(properties.cookieName(), token)
                            .setUrl("http://localhost:8080").setHttpOnly(true)
                            .setSecure(false).setSameSite(SameSiteAttribute.LAX)));
                    Page page = bc.newPage();
                    page.setDefaultTimeout(30000);
                    page.setViewportSize(390, 844);
                    page.navigate("http://localhost:3000/consultation/entry");
                    page.locator("#situation").waitFor();
                    page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("예금·계좌")).click();
                    page.locator("#situation").fill(item.question());
                    Response update = page.waitForResponse(r -> r.url().endsWith("/information-intake")
                            && "PUT".equals(r.request().method()),
                            () -> page.getByRole(AriaRole.BUTTON,
                                    new Page.GetByRoleOptions().setName("입력 내용 확인")).click());
                    if (update.status() != 200) throw new IllegalStateException("DEPOSIT_E2E_INTAKE_HTTP_FAILED");
                    var intake = JSON.readTree(update.body());
                    if (!"DEPOSITS_ACCOUNTS".equals(intake.path("topicCandidates").path(0).asText())) {
                        throw new IllegalStateException("DEPOSIT_E2E_TOPIC_CANDIDATE_MISMATCH");
                    }
                    Response confirm = page.waitForResponse(r -> r.url().endsWith("/information-intake/confirm-topic")
                            && "POST".equals(r.request().method()),
                            () -> page.getByRole(AriaRole.BUTTON,
                                    new Page.GetByRoleOptions().setName("이 주제 확인하기")).click());
                    var confirmed = JSON.readTree(confirm.body());
                    if (confirm.status() != 200
                            || !"TOPIC_CONFIRMED_SEARCH_AVAILABLE".equals(confirmed.path("status").asText())
                            || !confirmed.path("generalInformationSearchAvailable").asBoolean()) {
                        throw new IllegalStateException("DEPOSIT_E2E_TOPIC_COVERAGE_NOT_READY");
                    }
                    Response initial = page.waitForResponse(r -> r.url().endsWith("/information-report")
                            && "GET".equals(r.request().method()),
                            () -> page.getByRole(AriaRole.LINK,
                                    new Page.GetByRoleOptions().setName("공식 정보 확인하기")).click());
                    if (initial.status() != 200) throw new IllegalStateException("DEPOSIT_E2E_REPORT_GET_FAILED");
                    Response prepared = page.waitForResponse(r -> r.url().endsWith("/information-report/prepare")
                            && "POST".equals(r.request().method()),
                            () -> page.getByRole(AriaRole.BUTTON,
                                    new Page.GetByRoleOptions().setName("공식 정보 찾기")).click());
                    JsonNode report = JSON.readTree(prepared.body());
                    if (prepared.status() != 200 || !"READY_CANDIDATES".equals(report.path("status").asText())
                            || report.path("selectedEvidence").size() != 1
                            || report.path("selectedEvidence").path(0).path("questionNumber").asInt(-1) != item.questionNumber()) {
                        throw new IllegalStateException("DEPOSIT_E2E_REPORT_WRONG_QUESTION");
                    }
                    JsonNode evidence = report.path("selectedEvidence").path(0);
                    if (!VerifiedPdfMetadata.DEPOSIT_PDF_URL.equals(evidence.path("canonicalUrl").asText())
                            || !VerifiedPdfMetadata.DEPOSIT_POST_URL.equals(evidence.path("metadataSourceUrl").asText())
                            || !VerifiedPdfMetadata.DEPOSIT_RAW_SHA256.equals(evidence.path("rawSha256").asText())
                            || !evidence.path("pageReference").asText().startsWith("p.")
                            || !"DEPOSITS_ACCOUNTS".equals(report.path("snapshot").path("financialTopic").asText())
                            || report.path("snapshot").path("referenceDate").asText().isBlank()
                            || report.path("nextActions").isEmpty()) {
                        throw new IllegalStateException("DEPOSIT_E2E_EVIDENCE_OR_ACTION_INVALID");
                    }
                    if (item.conditionRequired() && evidence.path("conditions").isEmpty()) {
                        throw new IllegalStateException("DEPOSIT_E2E_REQUIRED_CONDITION_MISSING");
                    }
                    if (item.questionNumber() == 10
                            && (!evidence.path("supportText").asText().contains("사고보험금")
                            || !evidence.path("supportText").asText().contains("※ (예)"))) {
                        throw new IllegalStateException("DEPOSIT_E2E_Q10_CONTEXT_MISSING");
                    }
                    UUID consultationId = UUID.fromString(report.path("consultationId").asText());
                    if (staleTarget == null) {
                        String guestToken = bc.cookies("http://localhost:8080").stream()
                                .filter(cookie -> GuestSessionCookie.NAME.equals(cookie.name))
                                .map(cookie -> cookie.value).findFirst().orElseThrow();
                        staleTarget = new StaleTarget(consultationId, token, guestToken,
                                UUID.fromString(evidence.path("supportChunkId").asText()));
                    }
                    Integer stored = jdbc.queryForObject("SELECT count(*) FROM information_report WHERE consultation_id = ?",
                            Integer.class, consultationId);
                    if (stored == null || stored != 1) throw new IllegalStateException("DEPOSIT_E2E_REPORT_NOT_PERSISTED");
                    page.getByText("상세 출처와 조건 보기").first().click();
                    Locator sourceDetails = page.locator("details").first();
                    for (JsonNode condition : evidence.path("conditions")) {
                        if (!sourceDetails.getByText(condition.path("text").asText()).first().isVisible()) {
                            throw new IllegalStateException("DEPOSIT_E2E_CONDITION_UI_MISSING");
                        }
                    }
                    for (JsonNode exception : evidence.path("exceptions")) {
                        if (!sourceDetails.getByText(exception.path("text").asText()).first().isVisible()) {
                            throw new IllegalStateException("DEPOSIT_E2E_EXCEPTION_UI_MISSING");
                        }
                    }
                    if (item.questionNumber() == 10
                            && (!sourceDetails.getByText("사고보험금").first().isVisible()
                            || !sourceDetails.getByText("※ (예)").first().isVisible())) {
                        throw new IllegalStateException("DEPOSIT_E2E_Q10_FOOTNOTE_UI_MISSING");
                    }
                    if (!page.getByText("금융위원회 · 예금보호한도 상향 관련 주요 QA · 문답 "
                            + item.questionNumber()).isVisible()
                            || !page.getByRole(AriaRole.LINK,
                            new Page.GetByRoleOptions().setName("공식 원문 열기 (새 창)")).isVisible()
                            || !page.getByText("직접 확인할 다음 단계").isVisible()
                            || !page.getByText("기준일").first().isVisible()) {
                        throw new IllegalStateException("DEPOSIT_E2E_REPORT_UI_MISSING");
                    }
                    String href = page.getByRole(AriaRole.LINK,
                            new Page.GetByRoleOptions().setName("공식 원문 열기 (새 창)")).getAttribute("href");
                    if (!VerifiedPdfMetadata.DEPOSIT_PDF_URL.equals(href)) {
                        throw new IllegalStateException("DEPOSIT_E2E_PDF_LINK_MISMATCH");
                    }
                    BrowserE2EAccountHistorySmoke.noHorizontalOverflow(page, "DEPOSIT_REPORT");
                    Response reload = page.waitForResponse(r -> r.url().endsWith("/information-report")
                            && "GET".equals(r.request().method()), page::reload);
                    if (reload.status() != 200 || !"READY_CANDIDATES".equals(
                            JSON.readTree(reload.body()).path("status").asText())) {
                        throw new IllegalStateException("DEPOSIT_E2E_REPORT_RELOAD_FAILED");
                    }
                    System.out.println("DEPOSIT_BROWSER_Q" + item.questionNumber()
                            + "_READY page=" + evidence.path("pageReference").asText()
                            + " consultation=" + consultationId);
                }
            }
            for (String question : List.of("외화예금도 보호되나요?",
                    "제가 가입한 ISA의 실제 보호금액을 알려주세요.",
                    "제가 가입한 ISA는 반드시 보호되나요?",
                    "2025년 9월 이전에 발생한 제 보호사고도 새 한도가 적용되나요?",
                    "공식 근거에 없는 배상·환급을 보장해 주세요.",
                    "제가 가입한 상품도 되나요?")) {
                declined(context, jdbc, guard, properties, sessions, browser, question);
            }
            privacy(jdbc, guard, properties, sessions, browser);
            stale(jdbc, properties, browser, staleTarget);
        }
        System.out.println("DEPOSIT_BROWSER_COMPLETE");
    }

    private static void stale(JdbcTemplate jdbc, AccountProperties properties, Browser browser,
                              StaleTarget target) throws Exception {
        if (target == null) throw new IllegalStateException("DEPOSIT_E2E_STALE_TARGET_MISSING");
        int changed = jdbc.update("""
                UPDATE source_applicability_review SET review_status = 'REVOKED',
                  revoked_at = CURRENT_TIMESTAMP, revoked_by = 'isolated browser fixture',
                  revocation_reason = 'browser stale check'
                WHERE id IN (SELECT review_id FROM source_applicability_chunk
                  WHERE source_chunk_id = ? AND evidence_role = 'SUPPORT')
                  AND review_status = 'APPROVED'
                """, target.chunkId());
        if (changed != 1) throw new IllegalStateException("DEPOSIT_E2E_REVIEW_REVOKE_FAILED");
        try (BrowserContext bc = browser.newContext()) {
            bc.addCookies(List.of(new Cookie(properties.cookieName(), target.token())
                    .setUrl("http://localhost:8080").setHttpOnly(true)
                    .setSecure(false).setSameSite(SameSiteAttribute.LAX),
                    new Cookie(GuestSessionCookie.NAME, target.guestToken())
                            .setUrl("http://localhost:8080").setHttpOnly(true)
                            .setSecure(false).setSameSite(SameSiteAttribute.LAX)));
            Page page = bc.newPage();
            Response response = page.waitForResponse(r -> r.url().endsWith("/information-report")
                    && "GET".equals(r.request().method()),
                    () -> page.navigate("http://localhost:3000/consultation/information?consultationId=" + target.id()));
            JsonNode body = JSON.readTree(response.body());
            System.out.println("DEPOSIT_STALE_RESPONSE http=" + response.status()
                    + " status=" + body.path("status").asText()
                    + " evidence=" + body.path("selectedEvidence").size()
                    + " explanations=" + body.path("explanations").size());
            if (response.status() != 200 || !"STALE".equals(body.path("status").asText())
                    || !body.path("selectedEvidence").isEmpty() || !body.path("explanations").isEmpty()) {
                throw new IllegalStateException("DEPOSIT_E2E_REVOKED_REPORT_EXPOSED");
            }
            page.getByText("입력 내용이나 근거가 바뀌어 이전 결과를 표시할 수 없어요.").waitFor();
            if (page.getByText("예금보호한도 상향 관련 주요 QA").count() != 0) {
                throw new IllegalStateException("DEPOSIT_E2E_STALE_UI_EXPOSED");
            }
        }
        System.out.println("DEPOSIT_BROWSER_REVIEW_STALE_VERIFIED");
    }

    private static void privacy(JdbcTemplate jdbc, FollowUpBrowserE2EHarness.Guard guard,
                                AccountProperties properties, AccountSessionService sessions,
                                Browser browser) throws Exception {
        String token = sessions.createSession(AccountProvider.KAKAO,
                BrowserE2EAccountAuthFixture.syntheticSubject(guard.runId()),
                "E2E_SYNTHETIC_USER").rawToken();
        try (BrowserContext bc = browser.newContext()) {
            bc.addCookies(List.of(new Cookie(properties.cookieName(), token)
                    .setUrl("http://localhost:8080").setHttpOnly(true)
                    .setSecure(false).setSameSite(SameSiteAttribute.LAX)));
            Page page = bc.newPage();
            page.navigate("http://localhost:3000/consultation/entry");
            page.locator("#situation").waitFor();
            page.locator("#situation").fill("예금자보호 질문과 카드번호 4111 1111 1111 1111");
            Response response = page.waitForResponse(r -> r.url().endsWith("/information-intake")
                    && "PUT".equals(r.request().method()),
                    () -> page.getByRole(AriaRole.BUTTON,
                            new Page.GetByRoleOptions().setName("입력 내용 확인")).click());
            if (response.status() != 400 || !"CONSULTATION_SENSITIVE_INPUT".equals(
                    JSON.readTree(response.body()).path("error").path("code").asText())) {
                throw new IllegalStateException("DEPOSIT_E2E_SENSITIVE_INPUT_NOT_BLOCKED");
            }
            page.getByText("개인정보를 제외하고 다시 입력해 주세요.").waitFor();
            if (jdbc.queryForObject("SELECT count(*) FROM consultation WHERE situation_text LIKE '%4111%'",
                    Integer.class) != 0) {
                throw new IllegalStateException("DEPOSIT_E2E_SENSITIVE_INPUT_PERSISTED");
            }
        }
        System.out.println("DEPOSIT_BROWSER_SENSITIVE_INPUT_BLOCKED");
    }

    private static void declined(ConfigurableApplicationContext context, JdbcTemplate jdbc,
                                 FollowUpBrowserE2EHarness.Guard guard, AccountProperties properties,
                                 AccountSessionService sessions, Browser browser, String question) throws Exception {
        String token = sessions.createSession(AccountProvider.KAKAO,
                BrowserE2EAccountAuthFixture.syntheticSubject(guard.runId()),
                "E2E_SYNTHETIC_USER").rawToken();
        try (BrowserContext bc = browser.newContext()) {
            bc.addCookies(List.of(new Cookie(properties.cookieName(), token)
                    .setUrl("http://localhost:8080").setHttpOnly(true)
                    .setSecure(false).setSameSite(SameSiteAttribute.LAX)));
            Page page = bc.newPage();
            page.setDefaultTimeout(30000);
            page.navigate("http://localhost:3000/consultation/entry");
            page.locator("#situation").waitFor();
            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("예금·계좌")).click();
            page.locator("#situation").fill(question);
            Response update = page.waitForResponse(r -> r.url().endsWith("/information-intake")
                    && "PUT".equals(r.request().method()),
                    () -> page.getByRole(AriaRole.BUTTON,
                            new Page.GetByRoleOptions().setName("입력 내용 확인")).click());
            if (update.status() != 200) throw new IllegalStateException("DEPOSIT_E2E_LIMIT_INTAKE_FAILED");
            JsonNode state = JSON.readTree(update.body());
            UUID consultationId = UUID.fromString(state.path("consultationId").asText());
            if ("DEPOSITS_ACCOUNTS".equals(state.path("topicCandidates").path(0).asText())) {
                Response confirmed = page.waitForResponse(r -> r.url().endsWith("/information-intake/confirm-topic")
                        && "POST".equals(r.request().method()),
                        () -> page.getByRole(AriaRole.BUTTON,
                                new Page.GetByRoleOptions().setName("이 주제 확인하기")).click());
                if (confirmed.status() != 200) throw new IllegalStateException("DEPOSIT_E2E_LIMIT_TOPIC_CONFIRM_FAILED");
                JsonNode confirmedBody = JSON.readTree(confirmed.body());
                if (confirmedBody.path("generalInformationSearchAvailable").asBoolean()) {
                    page.getByRole(AriaRole.LINK,
                            new Page.GetByRoleOptions().setName("공식 정보 확인하기")).waitFor();
                    page.getByRole(AriaRole.LINK,
                            new Page.GetByRoleOptions().setName("공식 정보 확인하기")).click();
                    Response result = page.waitForResponse(r -> r.url().endsWith("/information-report/prepare")
                            && "POST".equals(r.request().method()),
                            () -> page.getByRole(AriaRole.BUTTON,
                                    new Page.GetByRoleOptions().setName("공식 정보 찾기")).click());
                    JsonNode response = JSON.readTree(result.body());
                    if (result.status() != 200 || "READY_CANDIDATES".equals(response.path("status").asText())
                            || !response.path("selectedEvidence").isEmpty()) {
                        throw new IllegalStateException("DEPOSIT_E2E_LIMIT_UNGROUNDED_REPORT");
                    }
                }
            }
            Integer stored = jdbc.queryForObject("SELECT count(*) FROM information_report WHERE consultation_id = ?",
                    Integer.class, consultationId);
            if (stored == null || stored != 0) throw new IllegalStateException("DEPOSIT_E2E_LIMIT_REPORT_PERSISTED");
            System.out.println("DEPOSIT_BROWSER_LIMIT_SAFE consultation=" + consultationId);
        }
    }

    private record Case(String question, int questionNumber, boolean conditionRequired) { }
    private record StaleTarget(UUID id, String token, String guestToken, UUID chunkId) { }
}
