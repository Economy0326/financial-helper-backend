package com.financialhelper.e2e;

import com.financialhelper.procedure.*;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Set;

/** Synthetic ACTION procedure only inside the guarded, disposable browser E2E database. */
final class BrowserE2EActionFixture {
    private BrowserE2EActionFixture() {}
    static void seed(ConfigurableApplicationContext context, JdbcTemplate jdbc, FollowUpBrowserE2EHarness.Guard guard) throws Exception {
        if (!guard.equals(FollowUpBrowserE2EHarness.guard(System.getenv())) || !Set.of(context.getEnvironment().getActiveProfiles()).equals(Set.of("browser-e2e"))) throw new IllegalStateException("E2E_ACTION_FIXTURE_PROFILE_MISMATCH");
        FollowUpBrowserE2EHarness.verifyContainer(guard);
        if (!guard.databaseName().equals(jdbc.queryForObject("select current_database()", String.class))) throw new IllegalStateException("E2E_ACTION_FIXTURE_DATABASE_MISMATCH");
        new TransactionTemplate(context.getBean(PlatformTransactionManager.class)).executeWithoutResult(status -> {
            String key = "browser-e2e-" + guard.runId();
            String hash = jdbc.queryForObject("SELECT d.raw_sha256 FROM source_document d JOIN source_registry s ON s.id = d.source_registry_id WHERE s.source_key = ? AND d.status = 'ACTIVE'", String.class, key);
            var repo = context.getBean(ProcedureVersionRepository.class);
            var candidates = repo.findByScenarioAndInstitutionAndProductTypeAndStatus(ProcedureVersionService.CARD_LOSS_ONLY_SCENARIO, ProcedureVersionService.KB_INSTITUTION, ProcedureVersionService.PERSONAL_CREDIT_CARD, ProcedureStatus.APPROVED);
            if (candidates.size() != 1) throw new IllegalStateException("E2E_ACTION_BASE_PROCEDURE_MISSING");
            var base = candidates.getFirst();
            if (jdbc.update("UPDATE procedure_version SET status = 'RETIRED' WHERE id = ? AND status = 'APPROVED'", base.getId()) != 1) throw new IllegalStateException("E2E_ACTION_BASE_RETIRE_FAILED");
            OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
            String actions = "[{\"actionId\":\"report-loss\",\"order\":1,\"title\":\"E2E_SYNTHETIC_ 합성 카드 자료 확인\",\"description\":\"합성 카드 자료의 조건을 확인합니다.\",\"channelRef\":\"" + key + "\",\"evidenceRoles\":[\"REPORT_CHANNEL\"]}]";
            String refs = "[{\"sourceKey\":\"" + key + "\",\"documentVersion\":1,\"expectedRawSha256\":\"" + hash + "\",\"role\":\"REPORT_CHANNEL\"}]";
            var fixture = new ProcedureVersion(base.getScenario(), base.getInstitution(), base.getProductType(), base.getVersion() + 1, ProcedureStatus.APPROVED,
                    base.getApplicabilityStartDate(), base.getApplicabilityEndDate(), base.getRequiredFactsJson(), base.getConditionRulesJson(), actions,
                    "[]", refs, "[]", "[]", "[\"E2E_SYNTHETIC_ 기술 검증 전용이며 실제 금융 행동 안내가 아닙니다.\"]",
                    "browser-e2e:" + guard.runId(), "synthetic-fixture", now, now);
            repo.saveAndFlush(fixture);
            System.out.println("E2E_STAGE_ACTION_SYNTHETIC_PROCEDURE_READY");
        });
    }
}
