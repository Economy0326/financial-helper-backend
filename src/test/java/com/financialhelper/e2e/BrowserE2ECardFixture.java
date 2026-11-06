package com.financialhelper.e2e;

import com.financialhelper.source.*;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

/** Synthetic corpus, exclusively for the guarded disposable browser-e2e database. */
final class BrowserE2ECardFixture {
    private BrowserE2ECardFixture() {}
    static void seed(ConfigurableApplicationContext context, JdbcTemplate jdbc, FollowUpBrowserE2EHarness.Guard guard) throws Exception {
        if (!guard.equals(FollowUpBrowserE2EHarness.guard(System.getenv())) ||
                !java.util.Set.of(context.getEnvironment().getActiveProfiles()).equals(java.util.Set.of("browser-e2e"))) {
            throw new IllegalStateException("E2E_FIXTURE_PROFILE_MISMATCH");
        }
        FollowUpBrowserE2EHarness.verifyContainer(guard);
        if (!guard.databaseName().equals(jdbc.queryForObject("select current_database()", String.class))) {
            throw new IllegalStateException("E2E_FIXTURE_DATABASE_MISMATCH");
        }
        new TransactionTemplate(context.getBean(PlatformTransactionManager.class)).executeWithoutResult(status -> {
            var registries = context.getBean(SourceRegistryRepository.class);
            var documents = context.getBean(SourceDocumentRepository.class);
            var configs = context.getBean(SourceChunkConfigurationRepository.class);
            var chunks = context.getBean(SourceChunkRepository.class);
            var generations = context.getBean(RetrievalGenerationRepository.class);
            var indexings = context.getBean(SourceChunkIndexingRepository.class);
            var active = context.getBean(ActiveRetrievalGenerationPersistenceService.class);
            var now = OffsetDateTime.now(ZoneOffset.UTC);
            String key = "browser-e2e-" + guard.runId();
            String url = "https://synthetic.invalid/" + guard.runId();
            UUID registryId = UUID.randomUUID();
            jdbc.update("INSERT INTO official_source_domain (domain, organization_name, enabled) VALUES ('synthetic.invalid', '합성 기관', TRUE) ON CONFLICT (domain) DO NOTHING");
            jdbc.update("INSERT INTO source_registry (id, source_key, organization_name, official_domain, canonical_url, acquisition_type, enabled, created_at, updated_at) VALUES (?, ?, '합성 기관', 'synthetic.invalid', ?, 'HTML', TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)", registryId, key, url);
            var registry = registries.findById(registryId).orElseThrow();
            String[] text = {"E2E_SYNTHETIC_ 카드 분실 관련 합성 정보입니다.", "E2E_SYNTHETIC_ 카드 유형을 확인해야 합니다.", "E2E_SYNTHETIC_ 예외는 별도 확인이 필요합니다."};
            String body = String.join("\n", text);
            byte[] raw = body.getBytes(StandardCharsets.UTF_8);
            var document = new SourceDocument(registry, 1, new SourceIngestionData.Parsed(url, "합성 카드 문서", null, now, "text/html", null, null, SourceHashing.sha256(raw), SourceHashing.sha256(body), raw, body));
            document.setApplicabilityWindow(LocalDate.of(2000, 1, 1), LocalDate.of(2099, 12, 31));
            document = documents.saveAndFlush(document);
            var config = configs.saveAndFlush(new SourceChunkConfiguration(key, "{}", now));
            var generation = generations.saveAndFlush(new RetrievalGeneration(new RetrievalGenerationData.Definition(key, "representation-v1", "model", "revision-1", "tokenizer", "revision-1", "{}", "{}", "{}"), now));
            UUID review = UUID.randomUUID();
            jdbc.update("INSERT INTO source_applicability_review (id, source_document_id, reviewed_raw_sha256, scope_kind, scenario, product_type, applicability_start_date, applicability_end_date, review_status, conditions_reviewed, exceptions_reviewed, condition_required, exception_required, reviewed_by, reviewed_at) VALUES (?, ?, ?, 'COMMON', 'CARD_LOSS_UNAUTHORIZED_USE', '개인 신용카드', DATE '2000-01-01', DATE '2099-12-31', 'APPROVED', TRUE, TRUE, TRUE, TRUE, 'synthetic-fixture', CURRENT_TIMESTAMP)", review, document.getId(), document.getRawSha256());
            int offset = 0;
            for (int i = 0; i < text.length; i++) {
                var chunk = new SourceChunk(document, config, new SourceChunkData.Definition(key, "{}", i, text[i], null, "합성 제" + (i + 1) + "항", "1", "synthetic:" + i, offset, offset + text[i].length(), "{}"), now);
                chunk.approve(now); chunk = chunks.saveAndFlush(chunk);
                var indexing = indexings.saveAndFlush(new SourceChunkIndexing(chunk, generation, now));
                indexing.markProcessing(now.plusSeconds(1)); indexing.markReady(now.plusSeconds(2), "{}"); indexings.saveAndFlush(indexing);
                jdbc.update("INSERT INTO source_applicability_chunk (review_id, source_document_id, source_chunk_id, evidence_role) VALUES (?, ?, ?, ?)", review, document.getId(), chunk.getId(), List.of("SUPPORT", "CONDITION", "EXCEPTION").get(i));
                offset += text[i].length() + 1;
            }
            generation.markProcessing(now.plusSeconds(1)); generation.markReady(3, now.plusSeconds(3)); generations.saveAndFlush(generation);
            active.activate(generation.getId());
            System.out.println("SYNTHETIC_CARD_FIXTURE_READY review=" + review);
        });
    }
}