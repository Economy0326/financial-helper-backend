package com.financialhelper.e2e;

import com.financialhelper.retrieval.KureRuntimeClient;
import com.financialhelper.retrieval.KureRuntimeHttpClient;
import com.financialhelper.source.*;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Official PDF bytes with synthetic approval, only in the guarded disposable E2E database. */
final class BrowserE2EDepositFixture {
    private BrowserE2EDepositFixture() { }

    static void seed(ConfigurableApplicationContext context, JdbcTemplate jdbc,
                     FollowUpBrowserE2EHarness.Guard guard) throws Exception {
        if (!guard.equals(FollowUpBrowserE2EHarness.guard(System.getenv()))
                || !Set.of(context.getEnvironment().getActiveProfiles()).equals(Set.of("browser-e2e"))
                || !"true".equalsIgnoreCase(context.getEnvironment().getProperty("app.retrieval.kure.enabled"))) {
            throw new IllegalStateException("DEPOSIT_E2E_BOUNDARY_INVALID");
        }
        FollowUpBrowserE2EHarness.verifyContainer(guard);
        if (!guard.databaseName().equals(jdbc.queryForObject("select current_database()", String.class))
                || !"e2e_".concat(guard.runId()).equals(jdbc.queryForObject("select current_user", String.class))) {
            throw new IllegalStateException("DEPOSIT_E2E_DATABASE_MISMATCH");
        }
        Path pdf = Path.of(System.getenv("DEPOSIT_E2E_VERIFIED_PDF"));
        byte[] raw = Files.readAllBytes(pdf);
        if (!VerifiedPdfMetadata.DEPOSIT_RAW_SHA256.equals(SourceHashing.sha256(raw))) {
            throw new IllegalStateException("DEPOSIT_E2E_PDF_HASH_MISMATCH");
        }
        var kure = context.getBean(KureRuntimeClient.class);
        if (!(kure instanceof KureRuntimeHttpClient)
                || !"nlpai-lab/KURE-v2".equals(kure.metadata().modelIdentifier())
                || !"3431f86d399d666083890dbb882aced6708873bc".equals(kure.metadata().modelRevision())) {
            throw new IllegalStateException("DEPOSIT_E2E_REAL_KURE_REQUIRED");
        }
        UUID registryId = UUID.randomUUID();
        String key = "deposit-e2e-" + guard.runId();
        jdbc.update("""
                INSERT INTO source_registry (id, source_key, organization_name, official_domain,
                  canonical_url, acquisition_type, enabled, created_at, updated_at, content_selector)
                VALUES (?, ?, '금융위원회', 'fsc.go.kr', ?, 'PDF', TRUE,
                  CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, NULL)
                """, registryId, key, VerifiedPdfMetadata.DEPOSIT_PDF_URL);
        var snapshot = new SourceIngestionData.Snapshot(registryId, key, "금융위원회",
                "fsc.go.kr", VerifiedPdfMetadata.DEPOSIT_PDF_URL, SourceAcquisitionType.PDF, null);
        var parsed = context.getBean(PdfSourceDocumentParser.class).parse(snapshot,
                new SourceIngestionData.Fetched(VerifiedPdfMetadata.DEPOSIT_PDF_URL,
                        "application/octet-stream", null, null, raw, OffsetDateTime.now(ZoneOffset.UTC)));
        context.getBean(SourceDocumentWriter.class).saveIfCurrent(snapshot, parsed);
        var documents = context.getBean(SourceDocumentRepository.class);
        var document = documents.findBySourceRegistry_IdAndStatus(registryId, SourceDocumentStatus.ACTIVE).orElseThrow();
        if (!VerifiedPdfMetadata.DEPOSIT_RAW_SHA256.equals(document.getRawSha256())
                || !"예금보호한도 상향 관련 주요 QA".equals(document.getTitle())) {
            throw new IllegalStateException("DEPOSIT_E2E_DOCUMENT_IDENTITY_INVALID");
        }
        document.setApplicabilityWindow(LocalDate.of(2025, 9, 1), null);
        documents.saveAndFlush(document);
        var chunks = context.getBean(SourceChunkingService.class).chunk(document);
        var saved = context.getBean(SourceChunkRepository.class)
                .findAllBySourceDocument_IdOrderBySequenceAsc(document.getId());
        if (chunks.size() != 11 || saved.size() != 11) {
            throw new IllegalStateException("DEPOSIT_E2E_CHUNK_BOUNDARY_INVALID");
        }
        var candidates = new DepositPdfReviewCandidateSelector().select(document, saved);
        var generations = context.getBean(RetrievalGenerationRepository.class);
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        var generation = generations.saveAndFlush(new RetrievalGeneration(
                new RetrievalGenerationData.Definition("deposit-e2e-" + registryId,
                        "representation-v1", "model", "revision-1", kure.metadata().tokenizerIdentifier(),
                        kure.metadata().tokenizerRevision(), "{}", "{}", "{}"), now));
        var indexed = candidates.stream().map(candidate -> {
            var chunk = saved.stream().filter(item -> item.getId().equals(candidate.supportChunkId()))
                    .findFirst().orElseThrow();
            return new KureRuntimeClient.KureRuntimeBuildRequest.Document(chunk.getId(), chunk.getBody());
        }).toList();
        // Model encoding and PLAID construction run outside any database transaction.
        var built = kure.build(new KureRuntimeClient.KureRuntimeBuildRequest(generation.getId(),
                "isolated-deposit-" + generation.getId(), "nlpai-lab/KURE-v2",
                "3431f86d399d666083890dbb882aced6708873bc",
                kure.metadata().tokenizerIdentifier(), kure.metadata().tokenizerRevision(),
                "deposit-fsc-q-v1", SourceHashing.sha256(document.getNormalizedContent()),
                "{\"queryLength\":64,\"documentMaxTokens\":8192,\"doQueryExpansion\":true,\"queryPrefix\":\"\",\"documentPrefix\":\"\"}",
                "{\"backend\":\"PLAID\",\"nbits\":4,\"seed\":42,\"useFast\":true,\"useTriton\":false}", indexed));
        if (!kure.readiness(generation.getId()).ready()
                || !Set.copyOf(built.readyDocumentIds()).equals(Set.copyOf(indexed.stream()
                .map(KureRuntimeClient.KureRuntimeBuildRequest.Document::id).toList()))) {
            throw new IllegalStateException("DEPOSIT_E2E_PLAID_NOT_READY");
        }
        var chunkRepo = context.getBean(SourceChunkRepository.class);
        var indexings = context.getBean(SourceChunkIndexingRepository.class);
        for (var candidate : candidates) {
            var chunk = chunkRepo.findWithDocumentById(candidate.supportChunkId()).orElseThrow();
            chunk.approve(now);
            chunkRepo.saveAndFlush(chunk);
            var indexing = indexings.saveAndFlush(new SourceChunkIndexing(chunk, generation, now));
            indexing.markProcessing(now.plusSeconds(1));
            indexing.markReady(now.plusSeconds(2), "{}");
            indexings.saveAndFlush(indexing);
            UUID reviewId = UUID.randomUUID();
            boolean condition = Set.of(2, 6, 10).contains(candidate.question());
            jdbc.update("""
                    INSERT INTO source_applicability_review (id, source_document_id, reviewed_raw_sha256,
                      scope_kind, scenario, review_target_kind, financial_topic, applicability_start_date,
                      review_status, condition_required, conditions_reviewed, exceptions_reviewed,
                      reviewed_by, reviewed_at)
                    VALUES (?, ?, ?, 'COMMON', NULL, 'TOPIC', 'DEPOSITS_ACCOUNTS',
                      DATE '2025-09-01', 'APPROVED', ?, TRUE, TRUE,
                      'isolated synthetic browser fixture', CURRENT_TIMESTAMP)
                    """, reviewId, document.getId(), document.getRawSha256(), condition);
            jdbc.update("""
                    INSERT INTO source_applicability_chunk (review_id, source_document_id, source_chunk_id, evidence_role)
                    VALUES (?, ?, ?, 'SUPPORT')
                    """, reviewId, document.getId(), chunk.getId());
            if (condition) jdbc.update("""
                    INSERT INTO source_applicability_chunk (review_id, source_document_id, source_chunk_id, evidence_role)
                    VALUES (?, ?, ?, 'CONDITION')
                    """, reviewId, document.getId(), chunk.getId());
        }
        generation.markProcessing(now.plusSeconds(1));
        generation.markReady(candidates.size(), now.plusSeconds(3));
        generations.saveAndFlush(generation);
        context.getBean(ActiveRetrievalGenerationPersistenceService.class).activate(generation.getId());
        Integer excluded = jdbc.queryForObject("""
                SELECT count(*) FROM source_applicability_chunk link
                JOIN source_chunk chunk ON chunk.id = link.source_chunk_id
                WHERE chunk.source_document_id = ? AND (chunk.sequence = 0 OR chunk.sequence = 4)
                """, Integer.class, document.getId());
        if (excluded == null || excluded != 0) throw new IllegalStateException("DEPOSIT_E2E_EXCLUDED_CHUNK_APPROVED");
        System.out.println("DEPOSIT_E2E_REAL_INDEX_READY generation=" + generation.getId()
                + " approvedQuestions=" + candidates.size() + " rawHash=" + document.getRawSha256());
    }
}
