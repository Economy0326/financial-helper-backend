package com.financialhelper.source;

import com.financialhelper.account.AccountProvider;
import com.financialhelper.account.AccountSessionCookie;
import com.financialhelper.account.AccountSessionService;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "server.address=127.0.0.1")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CandidateDecisionIntegrationTest {
    private UUID uiRegistrationRegistryId;
    private java.util.List<java.util.Map<String, Object>> previousActiveGeneration;
    private static final int UI_PORT = allocatePort();
    private static int allocatePort() {
        try (var server = new java.net.ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))) { return server.getLocalPort(); }
        catch (java.io.IOException ex) { throw new IllegalStateException(ex); }
    }
    @org.springframework.test.context.DynamicPropertySource
    static void uiCors(org.springframework.test.context.DynamicPropertyRegistry properties) {
        properties.add("app.cors.allowed-origin", () -> "http://localhost:" + UI_PORT);
    }
    @org.springframework.boot.test.web.server.LocalServerPort int httpPort;
    @Autowired SourceRegistryRepository registries;
    @Autowired SourceDocumentRepository documents;
    @Autowired SourceDocumentWriter writer;
    @Autowired SourceChunkConfigurationRepository configurations;
    @Autowired SourceChunkRepository chunks;
    @Autowired AccountSessionService sessions;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired RetrievalGenerationRepository generations;
    @Autowired ActiveRetrievalGenerationPersistenceService active;
    @Autowired com.financialhelper.retrieval.RetrievalGenerationIndexingPersistenceService indexing;
    @Autowired com.financialhelper.retrieval.RetrievalGenerationIndexingService indexingService;
    @Autowired com.financialhelper.retrieval.OfficialSourceRetrievalService retrieval;
    @Autowired com.financialhelper.retrieval.SourceKeywordSearchService keywordSearch;
    @Autowired DocumentClaimReviewerService documentReviewer;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactionManager;
    @Autowired org.springframework.security.web.FilterChainProxy securityFilters;
    @Autowired org.springframework.security.web.csrf.CookieCsrfTokenRepository csrfRepository;
    @Autowired com.financialhelper.retrieval.KureRuntimeProperties kureProperties;
    @org.springframework.test.context.bean.override.mockito.MockitoBean com.financialhelper.retrieval.KureRuntimeHttpClient kure;
    @org.springframework.test.context.bean.override.mockito.MockitoBean com.financialhelper.retrieval.KureRuntimeCompatibility compatibility;
    @org.springframework.test.context.bean.override.mockito.MockitoBean com.financialhelper.ai.OpenAiStructuredClient openAi;

    @org.junit.jupiter.api.BeforeEach
    void captureActiveGeneration() {
        previousActiveGeneration = jdbc.queryForList("SELECT retrieval_generation_id, switched_at, activation_revision FROM active_retrieval_generation WHERE singleton_key = TRUE");
    }

    @org.junit.jupiter.api.AfterEach
    void restoreActiveGeneration() {
        jdbc.update("DELETE FROM active_retrieval_generation WHERE singleton_key = TRUE");
        if (!previousActiveGeneration.isEmpty()) {
            var previous = previousActiveGeneration.getFirst();
            jdbc.update("INSERT INTO active_retrieval_generation(singleton_key, retrieval_generation_id, switched_at, activation_revision) VALUES (TRUE, ?, ?, ?)",
                    previous.get("retrieval_generation_id"), previous.get("switched_at"), previous.get("activation_revision"));
        }
    }

    @Test
    void firstDecisionRollsBackOnFailureAndRetryIsIdempotent() throws Exception {
        boolean realKureEnabled = "true".equals(System.getenv("FH_R4B2_REAL_KURE"));
        var realKure = realKureEnabled ? new com.financialhelper.retrieval.KureRuntimeHttpClient(
                kureProperties, tools.jackson.databind.json.JsonMapper.builder().build()) : null;
        if (realKureEnabled) {
            org.mockito.Mockito.doAnswer(i -> realKure.metadata()).when(kure).metadata();
            org.mockito.Mockito.doAnswer(i -> realKure.query(i.getArgument(0))).when(kure).query(org.mockito.ArgumentMatchers.any());
        }
        String key = "fsc-card-use-qa";
        var registry = registries.findBySourceKeyAndEnabledTrue(key).orElseThrow();
        String text = "Q1) 국내에서 카드를 도난, 분실시 조치방법은 무엇인지?\n"
                + "카드를 분실하거나 도난당한 경우 2025년 9월 1일 시행\n해당 카드회사에 즉시 신고해야 합니다.\nQ2) 리볼빙";
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        var parsed = new SourceIngestionData.Parsed(registry.getCanonicalUrl(), "합성 카드 안내", null,
                OffsetDateTime.now(ZoneOffset.UTC), "text/plain;charset=UTF-8", null, null,
                SourceHashing.sha256(bytes), SourceHashing.sha256(text), bytes, text);
        writer.saveIfCurrent(SourceIngestionData.Snapshot.from(registry), parsed);
        var document = documents.findBySourceRegistry_IdAndStatus(registry.getId(), SourceDocumentStatus.ACTIVE).orElseThrow();
        var chunk = fixtureChunk(document);
        var login = sessions.createSession(AccountProvider.NAVER, "isolated-" + UUID.randomUUID(), "fixture");
        Cookie cookie = new Cookie(AccountSessionCookie.NAME, login.rawToken());
        String path = "/api/v1/source-review/information/" + key + "/candidate-decision";
        String hold = """
                {"documentId":"%s","chunkId":"%s","expectedRawSha256":"%s",
                 "temporalPolicy":"HOLD","conditionsReviewed":true,"exceptionsReviewed":true,"decision":"APPROVE"}
                """.formatted(document.getId(), chunk.getId(), document.getRawSha256());
        mvc.perform(post(path).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(hold))
                .andExpect(status().isForbidden());
        mvc.perform(post(path).cookie(cookie).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(hold))
                .andExpect(status().isForbidden());
        jdbc.update("INSERT INTO source_reviewer_grant(account_id, granted_by) VALUES (?, 'isolated-fixture')", login.account().getId());
        mvc.perform(post(path).cookie(cookie).contentType(MediaType.APPLICATION_JSON).content(hold))
                .andExpect(status().isForbidden());
        mvc.perform(post(path).cookie(cookie).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(hold))
                .andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM source_applicability_review WHERE source_document_id = ?",
                Long.class, document.getId())).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM source_review_decision_audit WHERE source_document_id = ?",
                Long.class, document.getId())).isZero();
        LocalDate now = LocalDate.now(java.time.ZoneId.of("Asia/Seoul"));
        String unverified = """
                {"documentId":"%s","chunkId":"%s","expectedRawSha256":"%s",
                 "temporalPolicy":"CURRENT_GUIDANCE","temporalVerifiedOn":"%s","temporalValidUntil":"%s",
                 "conditionsReviewed":true,"exceptionsReviewed":true,"decision":"APPROVE",
                 "correctionCode":"REVIEWER_CURRENTNESS_CHECK","evidenceLocator":"%s"}
                """.formatted(document.getId(), chunk.getId(), document.getRawSha256(), now, now.plusDays(2), chunk.getLocator());
        mvc.perform(post(path).cookie(cookie).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(unverified))
                .andExpect(status().isBadRequest())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.error.code")
                        .value("SOURCE_REVIEW_CURRENTNESS_UNVERIFIED"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM source_applicability_review WHERE source_document_id = ?",
                Long.class, document.getId())).isZero();
        String approved = """
                {"documentId":"%s","chunkId":"%s","expectedRawSha256":"%s",
                 "temporalPolicy":"EXPLICIT_EFFECTIVE","applicabilityStartDate":"2025-09-01",
                 "conditionsReviewed":true,"exceptionsReviewed":true,"decision":"APPROVE"}
                """.formatted(document.getId(), chunk.getId(), document.getRawSha256());
        mvc.perform(post(path).cookie(cookie).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(approved))
                .andExpect(status().isNoContent());
        mvc.perform(post(path).cookie(cookie).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(approved))
                .andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM source_applicability_review WHERE source_document_id = ? AND review_status = 'APPROVED'",
                Long.class, document.getId())).isEqualTo  (1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM source_review_decision_audit WHERE source_document_id = ?",
                Long.class, document.getId())).isEqualTo(1);

        // Establish an isolated baseline using the actual reviewer-approved chunk.
        String model = realKureEnabled ? "nlpai-lab/KURE-v2" : "fixture-model";
        String revision = realKureEnabled ? "3431f86d399d666083890dbb882aced6708873bc" : "fixture-revision";
        String encoding = realKureEnabled ? "{\"queryLength\":64,\"documentMaxTokens\":8192,\"doQueryExpansion\":true,\"queryPrefix\":\"\",\"documentPrefix\":\"\"}" : "{}";
        String indexConfig = realKureEnabled ? "{\"backend\":\"PLAID\",\"nbits\":4,\"seed\":42,\"useFast\":true,\"useTriton\":false}" : "{}";
        var baseDefinition = new RetrievalGenerationData.Definition("reviewed-baseline-" + UUID.randomUUID(),
                "fixture", model, revision, realKureEnabled ? model : "fixture-tokenizer", revision,
                encoding, indexConfig, "{\"chunkIds\":[\"" + chunk.getId() + "\"]}", chunk.getChunkConfigVersion(), null);
        var baseline = generations.saveAndFlush(new RetrievalGeneration(baseDefinition, OffsetDateTime.now(ZoneOffset.UTC)));
        var baselinePlan = indexing.start(baseline.getId());
        indexing.complete(baselinePlan, new com.financialhelper.retrieval.KureRuntimeClient.KureRuntimeBuildResult(
                baseline.getId(), java.util.List.copyOf(baselinePlan.expectedDocumentIds()), "{}"));
        active.activate(baseline.getId());
        org.mockito.Mockito.when(kure.build(org.mockito.ArgumentMatchers.any())).thenAnswer(invocation -> {
            com.financialhelper.retrieval.KureRuntimeClient.KureRuntimeBuildRequest request = invocation.getArgument(0);
            if (realKureEnabled) return realKure.build(request);
            return new com.financialhelper.retrieval.KureRuntimeClient.KureRuntimeBuildResult(request.generationId(),
                    request.documents().stream().map(com.financialhelper.retrieval.KureRuntimeClient.KureRuntimeBuildRequest.Document::id).toList(), "{}");
        });
        org.mockito.Mockito.when(kure.readiness(org.mockito.ArgumentMatchers.any())).thenAnswer(invocation ->
                realKureEnabled ? realKure.readiness(invocation.getArgument(0)) :
                new com.financialhelper.retrieval.KureRuntimeClient.KureRuntimeReadiness(invocation.getArgument(0), true, 1, "{}"));
        String ordinaryResponse = mvc.perform(post("/api/v1/source-review/information/" + key + "/ready")
                .param("documentId", document.getId().toString()).cookie(cookie).with(csrf()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        UUID ordinaryId = UUID.fromString(ordinaryResponse.replace("\"", ""));
        assertThat(active.current()).get().extracting(RetrievalGeneration::getId).isEqualTo(ordinaryId);
        assertThat(jdbc.queryForList("SELECT source_chunk_id FROM source_chunk_indexing WHERE retrieval_generation_id=?", UUID.class, ordinaryId))
                .containsExactly(chunk.getId());
        baseline = generations.findById(ordinaryId).orElseThrow();

        UUID genericRegistryId = UUID.randomUUID();
        jdbc.update("INSERT INTO official_source_domain(domain,organization_name) VALUES ('synthetic.invalid','Synthetic') ON CONFLICT DO NOTHING");
        String genericUrl = "https://synthetic.invalid/" + genericRegistryId;
        jdbc.update("INSERT INTO source_registry(id,source_key,organization_name,official_domain,canonical_url,acquisition_type) VALUES (?,?,'Synthetic','synthetic.invalid',?,'HTML')",
                genericRegistryId, "fixture-" + genericRegistryId, genericUrl);
        if ("true".equals(System.getenv("FH_UI_E2E"))) {
            uiRegistrationRegistryId = UUID.randomUUID();
            jdbc.update("INSERT INTO source_registry(id,source_key,organization_name,official_domain,canonical_url,acquisition_type,content_selector) VALUES (?,?,'Synthetic','synthetic.invalid',?,'HTML','.body')",
                    uiRegistrationRegistryId, "ui-registration-" + uiRegistrationRegistryId, "https://synthetic.invalid/" + uiRegistrationRegistryId);
        }
        String genericText = "대출 안내\n신규 대출은 2025년 이후 신청할 수 있습니다. 다만 기존 계약은 제외합니다.\n대출 신청은 공식 채널에서 확인해야 합니다.";
        byte[] genericRaw = genericText.getBytes(StandardCharsets.UTF_8);
        var genericParsed = new SourceIngestionData.Parsed(genericUrl, "합성 안내", LocalDate.of(2025, 1, 1), OffsetDateTime.now(ZoneOffset.UTC),
                "text/html", null, null, SourceHashing.sha256(genericRaw), SourceHashing.sha256(genericText), genericRaw, genericText);
        var generic = documents.saveAndFlush(new SourceDocument(registries.findById(genericRegistryId).orElseThrow(), 1, genericParsed));
        if ("true".equals(System.getenv("FH_UI_E2E"))) {
            var builtSizes = new java.util.concurrent.ConcurrentHashMap<UUID, Integer>();
            org.mockito.Mockito.doAnswer(invocation -> {
                com.financialhelper.retrieval.KureRuntimeClient.KureRuntimeBuildRequest request = invocation.getArgument(0);
                builtSizes.put(request.generationId(), request.documents().size());
                if (realKureEnabled) return realKure.build(request);
                return new com.financialhelper.retrieval.KureRuntimeClient.KureRuntimeBuildResult(request.generationId(),
                        request.documents().stream().map(com.financialhelper.retrieval.KureRuntimeClient.KureRuntimeBuildRequest.Document::id).toList(), "{}");
            }).when(kure).build(org.mockito.ArgumentMatchers.any());
            org.mockito.Mockito.doAnswer(invocation -> {
                UUID generationId = invocation.getArgument(0);
                if (realKureEnabled && generationId != null) return realKure.readiness(generationId);
                return new com.financialhelper.retrieval.KureRuntimeClient.KureRuntimeReadiness(generationId, true,
                        generationId == null ? 0 : builtSizes.getOrDefault(generationId, 0), "{}");
            }).when(kure).readiness(org.mockito.ArgumentMatchers.any());
        }
        Process reviewerUi = startUiTest(login.rawToken(), generic.getId(), genericUrl);
        if (reviewerUi != null) {
            try {
                assertThat(reviewerUi.waitFor(90, java.util.concurrent.TimeUnit.SECONDS)).as("reviewer browser completion").isTrue();
                assertThat(reviewerUi.exitValue()).as("reviewer browser E2E").isZero();
                assertThat(jdbc.queryForObject("SELECT count(*) FROM source_document WHERE source_registry_id=?", Long.class, uiRegistrationRegistryId)).isEqualTo(1);
                assertThat(jdbc.queryForObject("SELECT count(*) FROM source_applicability_review r JOIN source_document d ON d.id=r.source_document_id WHERE d.source_registry_id=? AND r.review_status='APPROVED'", Long.class, uiRegistrationRegistryId)).isEqualTo(1);
                assertThat(jdbc.queryForObject("""
                        SELECT count(*) FROM source_document d JOIN source_document_claim c ON c.source_document_id=d.id
                        JOIN document_claim_snapshot_item i ON i.candidate_id=c.candidate_id AND i.source_document_id=d.id
                          AND i.document_version=d.document_version AND i.raw_sha256=d.raw_sha256 AND i.content_sha256=d.content_sha256
                        JOIN document_claim_snapshot s ON s.id=i.snapshot_id
                        JOIN document_claim_report r ON r.snapshot_id=s.id AND r.account_id=s.account_id
                        JOIN source_chunk_indexing g ON g.retrieval_generation_id=s.retrieval_generation_id AND g.source_chunk_id=c.source_chunk_id
                        WHERE d.source_registry_id=? AND d.content_type LIKE 'text/plain%'
                          AND convert_from(d.original_content,'UTF8')=d.normalized_content AND g.indexing_status='READY'
                        """, Long.class, uiRegistrationRegistryId)).isEqualTo(1);
                assertThat(jdbc.queryForObject("SELECT count(*) FROM source_review_decision_audit WHERE source_document_id=?", Long.class, generic.getId())).isEqualTo(2);
                UUID uiGeneration = active.currentId().orElseThrow();
                assertThat(uiGeneration).isNotEqualTo(baseline.getId());
                assertThat(jdbc.queryForObject("""
                        SELECT count(*) FROM source_chunk_indexing i JOIN source_document_claim c ON c.source_chunk_id=i.source_chunk_id
                        WHERE i.retrieval_generation_id=? AND c.source_document_id=? AND i.indexing_status='READY'
                        """, Long.class, uiGeneration, generic.getId())).isEqualTo(1);
                assertThat(jdbc.queryForObject("""
                        SELECT count(*) FROM source_chunk_indexing i
                        JOIN source_document_claim c ON c.source_chunk_id=i.source_chunk_id
                        JOIN source_document d ON d.id=c.source_document_id
                        WHERE i.retrieval_generation_id=? AND d.source_registry_id=? AND i.indexing_status='READY'
                        """, Long.class, uiGeneration, uiRegistrationRegistryId)).isEqualTo(1);
                if (realKureEnabled) {
                    UUID depositDocument = jdbc.queryForObject("SELECT id FROM source_document WHERE source_registry_id=?", UUID.class, uiRegistrationRegistryId);
                    var evaluationDocuments = new java.util.LinkedHashMap<UUID,String>();
                    evaluationDocuments.put(depositDocument,"A"); evaluationDocuments.put(generic.getId(),"B");
                    evaluationDocuments.putAll(prepareAdditionalEvaluationDocuments(cookie, document.getId()));
                    evaluateRealHybrid(evaluationDocuments, realKure);
                }
                if ("true".equals(System.getenv("FH_R4B_KEYWORD_EVAL"))) {
                    UUID depositDocument = jdbc.queryForObject("SELECT id FROM source_document WHERE source_registry_id=?", UUID.class, uiRegistrationRegistryId);
                    evaluateSyntheticKeyword(depositDocument, generic.getId());
                }
                // Restore only the isolated fixture's baseline for the existing pointer-race regressions.
                active.activate(baseline.getId());
            } finally { if (reviewerUi.isAlive()) reviewerUi.destroyForcibly(); }
        }
        org.mockito.Mockito.when(kure.readiness(org.mockito.ArgumentMatchers.any())).thenAnswer(invocation ->
                new com.financialhelper.retrieval.KureRuntimeClient.KureRuntimeReadiness(invocation.getArgument(0), true, 1, "{}"));
        String genericPath = "/api/v1/source-review/document-claims/" + generic.getId();
        String genericVersion = "{\"expectedRawSha256\":\"" + generic.getRawSha256() + "\"}";
        String usage = """
                {"expectedRawSha256":"%s","documentVersion":1,"basisUrl":"%s","scopes":["STORE","EXTRACT","REVIEW","SEARCH","EXCERPT"],
                 "privacyReviewed":true,"thirdPartyReviewed":true,"decision":"ALLOWED","reason":"synthetic"}
                """.formatted(generic.getRawSha256(), genericUrl);
        if (reviewerUi == null) mvc.perform(post(genericPath + "/usage-review").cookie(cookie).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(usage)).andExpect(status().isOk());
        mvc.perform(post(genericPath + "/prepare").cookie(cookie).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(genericVersion)).andExpect(status().isOk());
        String genericId = jdbc.queryForObject("SELECT candidate_id FROM source_document_claim WHERE source_document_id=? ORDER BY context_start LIMIT 1", String.class, generic.getId());
        UUID genericChunk = jdbc.queryForObject("SELECT source_chunk_id FROM source_document_claim WHERE candidate_id=?", UUID.class, genericId);
        mvc.perform(post(genericPath + "/" + genericId + "/decision").cookie(cookie).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedRawSha256\":\"" + generic.getRawSha256() + "\",\"decision\":\"APPROVE\"}")).andExpect(status().isNoContent());
        var candidateResponse = mvc.perform(post("/api/v1/source-review/information/" + key + "/ready-candidate")
                .cookie(cookie).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"documentId\":\"" + document.getId() + "\",\"genericDocumentIds\":[\"" + generic.getId() + "\"]}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        UUID mixedId = UUID.fromString(candidateResponse.replace("\"", ""));
        assertThat(jdbc.queryForList("SELECT source_chunk_id FROM source_chunk_indexing WHERE retrieval_generation_id=?", UUID.class, mixedId))
                .containsExactlyInAnyOrder(chunk.getId(), genericChunk);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM source_document_claim_relation WHERE candidate_id=? AND role='EXCEPTION'", Long.class, genericId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM source_document_claim_relation WHERE candidate_id=? AND role='CONDITION'", Long.class, genericId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM source_document_claim WHERE candidate_id=? AND source_document_id=? AND document_version=? AND raw_sha256=? AND content_sha256=? AND source_chunk_id=?",
                Long.class, genericId, generic.getId(), generic.getDocumentVersion(), generic.getRawSha256(), generic.getContentSha256(), genericChunk)).isEqualTo(1);
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(genericPath).cookie(cookie)
                .param("expectedRawSha256", generic.getRawSha256())).andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$[0].currentApplicability").value("NOT_VERIFIED"));
        assertThat(active.current()).get().extracting(RetrievalGeneration::getId).isEqualTo(baseline.getId());
        org.mockito.Mockito.doAnswer(invocation -> {
            com.financialhelper.retrieval.KureRuntimeClient.KureRuntimeBuildRequest request = invocation.getArgument(0);
            return new com.financialhelper.retrieval.KureRuntimeClient.KureRuntimeBuildResult(request.generationId(),
                    request.documents().stream().map(com.financialhelper.retrieval.KureRuntimeClient.KureRuntimeBuildRequest.Document::id).toList(), "{}");
        }).when(kure).build(org.mockito.ArgumentMatchers.any());
        org.mockito.Mockito.when(kure.readiness(mixedId)).thenReturn(
                new com.financialhelper.retrieval.KureRuntimeClient.KureRuntimeReadiness(mixedId, true, 2, "{}"));
        String buildPath = "/api/v1/source-review/information/" + key + "/ready-candidate/" + mixedId + "/build";
        mvc.perform(post(buildPath).cookie(cookie).with(csrf())).andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject("SELECT status FROM retrieval_generation WHERE id=?", String.class, mixedId)).isEqualTo("READY");
        assertThat(jdbc.queryForObject("SELECT status FROM mixed_generation_build_attempt WHERE generation_id=?", String.class, mixedId)).isEqualTo("BUILT");
        assertThat(active.current()).get().extracting(RetrievalGeneration::getId).isEqualTo(baseline.getId());
        var secondCandidateResponse = mvc.perform(post("/api/v1/source-review/information/" + key + "/ready-candidate")
                .cookie(cookie).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"documentId\":\"" + document.getId() + "\",\"genericDocumentIds\":[\"" + generic.getId() + "\"]}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        UUID failingId = UUID.fromString(secondCandidateResponse.replace("\"", ""));
        var enteredBuild = new java.util.concurrent.CountDownLatch(1);
        var finishBuild = new java.util.concurrent.CountDownLatch(1);
        org.mockito.Mockito.doAnswer(invocation -> {
            enteredBuild.countDown();
            if (!finishBuild.await(30, java.util.concurrent.TimeUnit.SECONDS)) throw new IllegalStateException("fixture timeout");
            throw new com.financialhelper.retrieval.KureRuntimeException("synthetic build failure");
        }).when(kure).build(org.mockito.ArgumentMatchers.any());
        var buildExecutor = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            var firstBuild = buildExecutor.submit(() -> indexingService.buildPreparedMixed(failingId));
            assertThat(enteredBuild.await(30, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> indexingService.buildPreparedMixed(failingId))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("already claimed");
            finishBuild.countDown();
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> firstBuild.get(30, java.util.concurrent.TimeUnit.SECONDS))
                    .isInstanceOf(java.util.concurrent.ExecutionException.class);
        } finally {
            finishBuild.countDown();
            buildExecutor.shutdownNow();
        }
        assertThat(jdbc.queryForObject("SELECT status FROM retrieval_generation WHERE id=?", String.class, failingId)).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("SELECT status FROM mixed_generation_build_attempt WHERE generation_id=?", String.class, failingId)).isEqualTo("FAILED");
        assertThat(active.current()).get().extracting(RetrievalGeneration::getId).isEqualTo(baseline.getId());
        var revokeCandidateResponse = mvc.perform(post("/api/v1/source-review/information/" + key + "/ready-candidate")
                .cookie(cookie).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"documentId\":\"" + document.getId() + "\",\"genericDocumentIds\":[\"" + generic.getId() + "\"]}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        UUID revokeDuringBuildId = UUID.fromString(revokeCandidateResponse.replace("\"", ""));
        var externalBuildStarted = new java.util.concurrent.CountDownLatch(1);
        var externalBuildMayFinish = new java.util.concurrent.CountDownLatch(1);
        org.mockito.Mockito.doAnswer(invocation -> {
            com.financialhelper.retrieval.KureRuntimeClient.KureRuntimeBuildRequest request = invocation.getArgument(0);
            externalBuildStarted.countDown();
            if (!externalBuildMayFinish.await(30, java.util.concurrent.TimeUnit.SECONDS)) throw new IllegalStateException("fixture timeout");
            return new com.financialhelper.retrieval.KureRuntimeClient.KureRuntimeBuildResult(request.generationId(),
                    request.documents().stream().map(com.financialhelper.retrieval.KureRuntimeClient.KureRuntimeBuildRequest.Document::id).toList(), "{}");
        }).when(kure).build(org.mockito.ArgumentMatchers.any());
        org.mockito.Mockito.when(kure.readiness(revokeDuringBuildId)).thenReturn(
                new com.financialhelper.retrieval.KureRuntimeClient.KureRuntimeReadiness(revokeDuringBuildId, true, 2, "{}"));
        UUID previousUsage = jdbc.queryForObject("SELECT u.id FROM source_document_usage_review u WHERE u.source_document_id=? AND NOT EXISTS (SELECT 1 FROM source_document_usage_review n WHERE n.previous_review_id=u.id)", UUID.class, generic.getId());
        String revoke = """
                {"expectedRawSha256":"%s","documentVersion":1,"basisUrl":"%s","scopes":[],
                 "privacyReviewed":false,"thirdPartyReviewed":false,"decision":"HOLD","reason":"recheck","expectedPreviousReviewId":"%s"}
                """.formatted(generic.getRawSha256(), genericUrl, previousUsage);
        var revokeExecutor = java.util.concurrent.Executors.newSingleThreadExecutor();
        var usageRaceExecutor = java.util.concurrent.Executors.newFixedThreadPool(2);
        var revocationPrepared = new java.util.concurrent.CountDownLatch(1);
        var revocationMayCommit = new java.util.concurrent.CountDownLatch(1);
        var activationStarted = new java.util.concurrent.CountDownLatch(1);
        try {
            var runningBuild = revokeExecutor.submit(() -> indexingService.buildPreparedMixed(revokeDuringBuildId));
            assertThat(externalBuildStarted.await(30, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            var pendingRevoke = usageRaceExecutor.submit(() -> new org.springframework.transaction.support.TransactionTemplate(transactionManager).execute(tx -> {
                try {
                    int response = mvc.perform(post(genericPath + "/usage-review").cookie(cookie).with(csrf())
                            .contentType(MediaType.APPLICATION_JSON).content(revoke)).andReturn().getResponse().getStatus();
                    revocationPrepared.countDown();
                    if (!revocationMayCommit.await(30, java.util.concurrent.TimeUnit.SECONDS)) throw new IllegalStateException("fixture timeout");
                    return response;
                } catch (Exception failure) { throw new IllegalStateException(failure); }
            }));
            assertThat(revocationPrepared.await(30, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            var competingActivation = usageRaceExecutor.submit(() -> {
                activationStarted.countDown();
                return mvc.perform(post("/api/v1/source-review/information/" + key + "/ready-candidate/" + mixedId + "/activate")
                        .cookie(cookie).with(csrf())).andReturn().getResponse().getStatus();
            });
            assertThat(activationStarted.await(30, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            boolean waitingForDocument = false;
            long lockDeadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
            while (System.nanoTime() < lockDeadline && !waitingForDocument) {
                waitingForDocument = Boolean.TRUE.equals(jdbc.queryForObject("""
                        SELECT EXISTS (SELECT 1 FROM pg_stat_activity WHERE datname=current_database()
                          AND wait_event_type='Lock' AND query ILIKE '%source_document%'
                          AND query ILIKE '%FOR UPDATE%' AND cardinality(pg_blocking_pids(pid)) > 0)
                        """, Boolean.class));
                if (!waitingForDocument) Thread.sleep(20);
            }
            assertThat(waitingForDocument).isTrue();
            revocationMayCommit.countDown();
            assertThat(pendingRevoke.get(30, java.util.concurrent.TimeUnit.SECONDS)).isEqualTo(200);
            assertThat(competingActivation.get(30, java.util.concurrent.TimeUnit.SECONDS)).isGreaterThanOrEqualTo(400);
            externalBuildMayFinish.countDown();
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> runningBuild.get(30, java.util.concurrent.TimeUnit.SECONDS))
                    .isInstanceOf(java.util.concurrent.ExecutionException.class);
        } finally {
            revocationMayCommit.countDown();
            externalBuildMayFinish.countDown();
            revokeExecutor.shutdownNow();
            usageRaceExecutor.shutdownNow();
        }
        assertThat(jdbc.queryForObject("SELECT status FROM retrieval_generation WHERE id=?", String.class, revokeDuringBuildId)).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("SELECT status FROM mixed_generation_build_attempt WHERE generation_id=?", String.class, revokeDuringBuildId)).isEqualTo("FAILED");
        mvc.perform(post("/api/v1/source-review/information/" + key + "/ready-candidate/" + mixedId + "/activate")
                .cookie(cookie).with(csrf())).andExpect(result -> assertThat(result.getResponse().getStatus()).isGreaterThanOrEqualTo(400));
        assertThat(active.current()).get().extracting(RetrievalGeneration::getId).isEqualTo(baseline.getId());
        Long candidateCount = jdbc.queryForObject("SELECT count(*) FROM retrieval_generation WHERE metadata_json LIKE '%REVIEWED-MIXED-CANDIDATE%'", Long.class);
        mvc.perform(post("/api/v1/source-review/information/" + key + "/ready-candidate")
                .cookie(cookie).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"documentId\":\"" + document.getId() + "\",\"genericDocumentIds\":[\"" + generic.getId() + "\"]}"))
                .andExpect(status().isConflict());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM retrieval_generation WHERE metadata_json LIKE '%REVIEWED-MIXED-CANDIDATE%'", Long.class)).isEqualTo(candidateCount);
        assertThat(active.current()).get().extracting(RetrievalGeneration::getId).isEqualTo(baseline.getId());
        UUID holdId = jdbc.queryForObject("SELECT id FROM source_document_usage_review WHERE previous_review_id=?", UUID.class, previousUsage);
        String allowAgain = """
                {"expectedRawSha256":"%s","documentVersion":1,"basisUrl":"%s","scopes":["STORE","EXTRACT","REVIEW","SEARCH","EXCERPT"],
                 "privacyReviewed":true,"thirdPartyReviewed":true,"decision":"ALLOWED","reason":"rechecked","expectedPreviousReviewId":"%s"}
                """.formatted(generic.getRawSha256(), genericUrl, holdId);
        mvc.perform(post(genericPath + "/usage-review").cookie(cookie).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(allowAgain)).andExpect(status().isOk());
        jdbc.update("UPDATE source_document SET document_version=2 WHERE id=?", generic.getId());
        try {
            mvc.perform(post("/api/v1/source-review/information/" + key + "/ready-candidate/" + mixedId + "/activate")
                    .cookie(cookie).with(csrf())).andExpect(result -> assertThat(result.getResponse().getStatus()).isGreaterThanOrEqualTo(400));
            assertThat(active.current()).get().extracting(RetrievalGeneration::getId).isEqualTo(baseline.getId());
        } finally { jdbc.update("UPDATE source_document SET document_version=1 WHERE id=?", generic.getId()); }
        var exceptionRelation = jdbc.queryForMap("SELECT start_offset,end_offset,ambiguous FROM source_document_claim_relation WHERE candidate_id=? AND role='EXCEPTION'", genericId);
        jdbc.update("DELETE FROM source_document_claim_relation WHERE candidate_id=? AND role='EXCEPTION'", genericId);
        try {
            mvc.perform(post("/api/v1/source-review/information/" + key + "/ready-candidate/" + mixedId + "/activate")
                    .cookie(cookie).with(csrf())).andExpect(result -> assertThat(result.getResponse().getStatus()).isGreaterThanOrEqualTo(400));
            assertThat(active.current()).get().extracting(RetrievalGeneration::getId).isEqualTo(baseline.getId());
        } finally {
            jdbc.update("INSERT INTO source_document_claim_relation(candidate_id,role,start_offset,end_offset,ambiguous) VALUES (?,'EXCEPTION',?,?,?)",
                    genericId, exceptionRelation.get("start_offset"), exceptionRelation.get("end_offset"), exceptionRelation.get("ambiguous"));
        }
        jdbc.update("UPDATE source_document SET normalized_content=normalized_content || ' 변조' WHERE id=?", generic.getId());
        try {
            mvc.perform(post("/api/v1/source-review/information/" + key + "/ready-candidate/" + mixedId + "/activate")
                    .cookie(cookie).with(csrf())).andExpect(result -> assertThat(result.getResponse().getStatus()).isGreaterThanOrEqualTo(400));
            assertThat(active.current()).get().extracting(RetrievalGeneration::getId).isEqualTo(baseline.getId());
        } finally { jdbc.update("UPDATE source_document SET normalized_content=? WHERE id=?", genericText, generic.getId()); }
        String cancelCandidateResponse = mvc.perform(post("/api/v1/source-review/information/" + key + "/ready-candidate")
                .cookie(cookie).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"documentId\":\"" + document.getId() + "\",\"genericDocumentIds\":[\"" + generic.getId() + "\"]}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        UUID cancelledId = UUID.fromString(cancelCandidateResponse.replace("\"", ""));
        var cancelStarted = new java.util.concurrent.CountDownLatch(1);
        var cancelRelease = new java.util.concurrent.CountDownLatch(1);
        org.mockito.Mockito.doAnswer(invocation -> {
            com.financialhelper.retrieval.KureRuntimeClient.KureRuntimeBuildRequest request = invocation.getArgument(0);
            cancelStarted.countDown();
            if (!cancelRelease.await(30, java.util.concurrent.TimeUnit.SECONDS)) throw new IllegalStateException("fixture timeout");
            return new com.financialhelper.retrieval.KureRuntimeClient.KureRuntimeBuildResult(request.generationId(),
                    request.documents().stream().map(com.financialhelper.retrieval.KureRuntimeClient.KureRuntimeBuildRequest.Document::id).toList(), "{}");
        }).when(kure).build(org.mockito.ArgumentMatchers.any());
        org.mockito.Mockito.when(kure.readiness(cancelledId)).thenReturn(
                new com.financialhelper.retrieval.KureRuntimeClient.KureRuntimeReadiness(cancelledId, true, 2, "{}"));
        var cancelExecutor = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            var oldWorker = cancelExecutor.submit(() -> indexingService.buildPreparedMixed(cancelledId));
            assertThat(cancelStarted.await(30, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            mvc.perform(post("/api/v1/source-review/information/" + key + "/ready-candidate/" + cancelledId + "/cancel")
                    .cookie(cookie).with(csrf())).andExpect(status().isNoContent());
            cancelRelease.countDown();
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> oldWorker.get(30, java.util.concurrent.TimeUnit.SECONDS))
                    .isInstanceOf(java.util.concurrent.ExecutionException.class);
        } finally { cancelRelease.countDown(); cancelExecutor.shutdownNow(); }
        assertThat(jdbc.queryForObject("SELECT status FROM retrieval_generation WHERE id=?", String.class, cancelledId)).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("SELECT status FROM mixed_generation_build_attempt WHERE generation_id=?", String.class, cancelledId)).isEqualTo("FAILED");
        org.mockito.Mockito.doAnswer(invocation -> {
            com.financialhelper.retrieval.KureRuntimeClient.KureRuntimeBuildRequest request = invocation.getArgument(0);
            return new com.financialhelper.retrieval.KureRuntimeClient.KureRuntimeBuildResult(request.generationId(),
                    request.documents().stream().map(com.financialhelper.retrieval.KureRuntimeClient.KureRuntimeBuildRequest.Document::id).toList(), "{}");
        }).when(kure).build(org.mockito.ArgumentMatchers.any());
        String competingResponse = mvc.perform(post("/api/v1/source-review/information/" + key + "/ready-candidate")
                .cookie(cookie).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"documentId\":\"" + document.getId() + "\",\"genericDocumentIds\":[\"" + generic.getId() + "\"]}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        UUID competingId = UUID.fromString(competingResponse.replace("\"", ""));
        org.mockito.Mockito.when(kure.readiness(competingId)).thenReturn(
                new com.financialhelper.retrieval.KureRuntimeClient.KureRuntimeReadiness(competingId, true, 2, "{}"));
        mvc.perform(post("/api/v1/source-review/information/" + key + "/ready-candidate/" + competingId + "/build")
                .cookie(cookie).with(csrf())).andExpect(status().isNoContent());
        var activationGate = new java.util.concurrent.CountDownLatch(1);
        var activationExecutor = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            java.util.concurrent.Callable<Integer> activateFirst = () -> {
                activationGate.await();
                return mvc.perform(post("/api/v1/source-review/information/" + key + "/ready-candidate/" + mixedId + "/activate")
                        .cookie(cookie).with(csrf())).andReturn().getResponse().getStatus();
            };
            java.util.concurrent.Callable<Integer> activateSecond = () -> {
                activationGate.await();
                return mvc.perform(post("/api/v1/source-review/information/" + key + "/ready-candidate/" + competingId + "/activate")
                        .cookie(cookie).with(csrf())).andReturn().getResponse().getStatus();
            };
            var firstActivation = activationExecutor.submit(activateFirst);
            var secondActivation = activationExecutor.submit(activateSecond);
            activationGate.countDown();
            int firstStatus = firstActivation.get(30, java.util.concurrent.TimeUnit.SECONDS);
            int secondStatus = secondActivation.get(30, java.util.concurrent.TimeUnit.SECONDS);
            assertThat(java.util.List.of(firstStatus, secondStatus)).contains(204);
            assertThat(firstStatus == 204 ? secondStatus : firstStatus).isGreaterThanOrEqualTo(400);
        } finally { activationExecutor.shutdownNow(); }
        UUID activated = jdbc.queryForObject("SELECT retrieval_generation_id FROM active_retrieval_generation WHERE singleton_key=TRUE", UUID.class);
        assertThat(activated).isIn(mixedId, competingId);
        mvc.perform(post("/api/v1/source-review/information/" + key + "/ready-candidate/" + activated + "/activate")
                .cookie(cookie).with(csrf())).andExpect(result -> assertThat(result.getResponse().getStatus()).isGreaterThanOrEqualTo(400));
        assertThat(jdbc.queryForList("SELECT source_chunk_id FROM source_chunk_indexing WHERE retrieval_generation_id=?", UUID.class, activated))
                .containsExactlyInAnyOrder(chunk.getId(), genericChunk);
        org.mockito.Mockito.doAnswer(invocation -> {
            com.financialhelper.retrieval.KureRuntimeClient.KureRuntimeQueryRequest request = invocation.getArgument(0);
            return new com.financialhelper.retrieval.KureRuntimeClient.KureRuntimeQueryResult(request.generationId(), java.util.List.of());
        }).when(kure).query(org.mockito.ArgumentMatchers.any());
        String userSnapshotPath = "/api/v1/information-evidence-snapshots";
        String userQuestion = "{\"question\":\"신규\"}";
        mvc.perform(post(userSnapshotPath).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(userQuestion))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.snapshotId").doesNotExist());
        mvc.perform(post(userSnapshotPath).cookie(cookie).contentType(MediaType.APPLICATION_JSON).content(userQuestion))
                .andExpect(status().isForbidden());
        mvc.perform(post(userSnapshotPath).cookie(cookie).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"question\":\"test@example.invalid\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post(userSnapshotPath).cookie(cookie).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"question\":\"" + "가".repeat(1001) + "\"}"))
                .andExpect(status().isPayloadTooLarge());
        Long beforeNoMatch = jdbc.queryForObject("SELECT count(*) FROM document_claim_snapshot WHERE account_id=?", Long.class, login.account().getId());
        mvc.perform(post(userSnapshotPath).cookie(cookie).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"question\":\"존재하지않는합성단어\"}"))
                .andExpect(status().isOk()).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.status").value("NO_MATCH"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.evidence.length()").value(0));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM document_claim_snapshot WHERE account_id=?", Long.class, login.account().getId())).isEqualTo(beforeNoMatch);
        String snapshotJson = mvc.perform(post(userSnapshotPath).cookie(cookie).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(userQuestion)).andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.status").value("READY_CANDIDATES"))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        var snapshotTree = tools.jackson.databind.json.JsonMapper.builder().build().readTree(snapshotJson);
        UUID snapshotId = UUID.fromString(snapshotTree.get("snapshotId").asString());
        String explanationPath = userSnapshotPath + "/" + snapshotId + "/explanation";
        String explanationRequest = "{\"requestId\":\"" + UUID.randomUUID() + "\",\"question\":\"신규\"}";
        mvc.perform(post(explanationPath).contentType(MediaType.APPLICATION_JSON).content(explanationRequest).with(csrf()))
                .andExpect(result -> assertThat(result.getResponse().getStatus()).isIn(401, 403));
        mvc.perform(post(explanationPath).contentType(MediaType.APPLICATION_JSON).content(explanationRequest).cookie(cookie)).andExpect(status().isForbidden());
        mvc.perform(post(explanationPath).contentType(MediaType.APPLICATION_JSON).content(explanationRequest).cookie(cookie).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string("Cache-Control", "no-store"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.status").value("FALLBACK"));
        org.mockito.Mockito.verifyNoInteractions(openAi);
        String createReportPath = userSnapshotPath + "/" + snapshotId + "/report";
        mvc.perform(post(createReportPath).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(userQuestion))
                .andExpect(result -> assertThat(result.getResponse().getStatus()).isIn(401, 403));
        mvc.perform(post(createReportPath).cookie(cookie).contentType(MediaType.APPLICATION_JSON).content(userQuestion))
                .andExpect(status().isForbidden());
        mvc.perform(post(createReportPath).cookie(cookie).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"question\":\"test@example.invalid\"}")) .andExpect(status().isBadRequest());
        mvc.perform(post(createReportPath).cookie(cookie).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"question\":\"다른 질문\"}")).andExpect(status().isConflict());
        String questionBeforeRollback = jdbc.queryForObject("SELECT question FROM document_claim_snapshot WHERE id=?", String.class, snapshotId);
        Long reportsBeforeRollback = jdbc.queryForObject("SELECT count(*) FROM document_claim_report WHERE snapshot_id=?", Long.class, snapshotId);
        new org.springframework.transaction.support.TransactionTemplate(transactionManager).executeWithoutResult(transaction -> {
            try {
                mvc.perform(post(createReportPath).cookie(cookie).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(userQuestion)).andExpect(status().isOk());
            } catch (Exception exception) {
                throw new IllegalStateException(exception);
            }
            assertThat(jdbc.queryForObject("SELECT count(*) FROM document_claim_report WHERE snapshot_id=?", Long.class, snapshotId))
                    .isEqualTo(reportsBeforeRollback + 1);
            transaction.setRollbackOnly();
        });
        assertThat(jdbc.queryForObject("SELECT count(*) FROM document_claim_report WHERE snapshot_id=?", Long.class, snapshotId))
                .isEqualTo(reportsBeforeRollback);
        assertThat(jdbc.queryForObject("SELECT question FROM document_claim_snapshot WHERE id=?", String.class, snapshotId))
                .isEqualTo(questionBeforeRollback);
        String reportJson = mvc.perform(post(createReportPath).cookie(cookie).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(userQuestion)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        var reportTree = tools.jackson.databind.json.JsonMapper.builder().build().readTree(reportJson);
        UUID reportId = UUID.fromString(reportTree.get("reportId").asString());
        String reportPath = userSnapshotPath + "/reports/" + reportId;
        assertThat(reportJson).contains("CONDITION", "EXCEPTION", "NOT_VERIFIED", "2025-01-01", "신규");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM document_claim_report WHERE id=? AND snapshot_id=? AND account_id=?",
                Long.class, reportId, snapshotId, login.account().getId())).isEqualTo(1);
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(reportPath).cookie(cookie))
                .andExpect(status().isOk()).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string("Cache-Control", "no-store"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.status").value("READY"));
        assertThat(jdbc.queryForObject("SELECT account_id FROM document_claim_snapshot WHERE id=?", UUID.class, snapshotId)).isEqualTo(login.account().getId());
        assertThat(documentReviewer.evaluateEligibility(generic.getId(), genericId, generic.getRawSha256(), "EXTERNAL_AI").eligible()).isFalse();
        org.mockito.Mockito.verifyNoInteractions(openAi);
        assertThat(snapshotJson).contains("CONDITION", "EXCEPTION", "NOT_VERIFIED", "2025-01-01");
        assertThat(snapshotTree.get("evidence").size()).isEqualTo(1);
        assertThat(snapshotTree.get("evidence").get(0).get("officialUrl").asString()).isEqualTo(genericUrl);
        assertThat(snapshotTree.get("evidence").get(0).get("documentVersion").asInt()).isEqualTo(1);
        assertThat(snapshotTree.get("evidence").get(0).get("title").asString()).isEqualTo("합성 안내");
        assertThat(snapshotTree.get("evidence").get(0).get("locator").asString()).isEqualTo(
                jdbc.queryForObject("SELECT locator FROM source_chunk WHERE id=?", String.class, genericChunk));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM document_claim_snapshot_item WHERE snapshot_id=? AND candidate_id=?", Long.class, snapshotId, genericId)).isEqualTo(1);
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(userSnapshotPath + "/" + snapshotId).cookie(cookie))
                .andExpect(status().isOk()).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.status").value("READY_CANDIDATES"));
        var otherLogin = sessions.createSession(AccountProvider.NAVER, "other-" + UUID.randomUUID(), "fixture");
        mvc.perform(post(explanationPath).contentType(MediaType.APPLICATION_JSON).content(explanationRequest).cookie(new Cookie(AccountSessionCookie.NAME, otherLogin.rawToken())).with(csrf()))
                .andExpect(status().isNotFound());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(reportPath)
                .cookie(new Cookie(AccountSessionCookie.NAME, otherLogin.rawToken()))).andExpect(status().isNotFound());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(reportPath))
                .andExpect(result -> assertThat(result.getResponse().getStatus()).isIn(401, 403));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(userSnapshotPath + "/" + snapshotId)
                .cookie(new Cookie(AccountSessionCookie.NAME, otherLogin.rawToken())))
                .andExpect(status().isNotFound());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(userSnapshotPath + "/" + snapshotId))
                .andExpect(result -> assertThat(result.getResponse().getStatus()).isIn(401, 403));
        var snapshotRelation = jdbc.queryForMap("SELECT start_offset,end_offset,ambiguous FROM source_document_claim_relation WHERE candidate_id=? AND role='EXCEPTION'", genericId);
        jdbc.update("DELETE FROM source_document_claim_relation WHERE candidate_id=? AND role='EXCEPTION'", genericId);
        try {
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(reportPath).cookie(cookie))
                    .andExpect(status().isOk()).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.status").value("STALE"))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.evidence.length()").value(0))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.explanation").doesNotExist());
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(userSnapshotPath + "/" + snapshotId).cookie(cookie))
                    .andExpect(status().isOk()).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.status").value("STALE"))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.evidence.length()").value(0));
        } finally {
            jdbc.update("INSERT INTO source_document_claim_relation(candidate_id,role,start_offset,end_offset,ambiguous) VALUES (?,'EXCEPTION',?,?,?)",
                    genericId, snapshotRelation.get("start_offset"), snapshotRelation.get("end_offset"), snapshotRelation.get("ambiguous"));
        }
        jdbc.update("UPDATE source_document SET document_version=2 WHERE id=?", generic.getId());
        try {
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(reportPath).cookie(cookie))
                    .andExpect(status().isOk()).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.status").value("STALE"));
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(userSnapshotPath + "/" + snapshotId).cookie(cookie))
                    .andExpect(status().isOk()).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.status").value("STALE"))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.evidence.length()").value(0));
        } finally { jdbc.update("UPDATE source_document SET document_version=1 WHERE id=?", generic.getId()); }
        jdbc.update("UPDATE source_document SET normalized_content=normalized_content || ' 변조' WHERE id=?", generic.getId());
        try {
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(reportPath).cookie(cookie))
                    .andExpect(status().isOk()).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.status").value("STALE"));
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(userSnapshotPath + "/" + snapshotId).cookie(cookie))
                    .andExpect(status().isOk()).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.status").value("STALE"))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.evidence.length()").value(0));
        } finally { jdbc.update("UPDATE source_document SET normalized_content=? WHERE id=?", genericText, generic.getId()); }
        var searchRequest = new com.financialhelper.retrieval.OfficialSearchRequest("신규", null, "Synthetic", null, null, java.util.List.of(), 10);
        var found = retrieval.search(searchRequest);
        var genericHit = found.candidates().stream().filter(c -> c.sourceChunkId().equals(genericChunk)).findFirst().orElseThrow();
        assertThat(genericHit.body()).contains("다만 기존 계약은 제외합니다");
        assertThat(genericHit.publishedAt()).isEqualTo(LocalDate.of(2025, 1, 1));
        assertThat(genericHit.currentApplicability()).isEqualTo("NOT_VERIFIED");
        assertThat(genericHit.canonicalUrl()).isEqualTo(genericUrl);
        assertThat(genericHit.documentVersion()).isEqualTo(1);
        assertThat(genericHit.locator()).isEqualTo(jdbc.queryForObject("SELECT locator FROM source_chunk WHERE id=?", String.class, genericChunk));
        var genericPassage = retrieval.getPassages(found.searchId(), java.util.List.of(genericHit.candidateId())).getFirst();
        assertThat(genericPassage.text()).contains("다만 기존 계약은 제외합니다");
        assertThat(genericPassage.publishedAt()).isEqualTo(LocalDate.of(2025, 1, 1));
        assertThat(genericPassage.currentApplicability()).isEqualTo("NOT_VERIFIED");
        UUID allowedAgainId = jdbc.queryForObject("SELECT id FROM source_document_usage_review WHERE previous_review_id=?", UUID.class, holdId);
        var ordinaryUser = sessions.createSession(AccountProvider.NAVER, "ui-ordinary-" + UUID.randomUUID(), "fixture");
        Process ui = startUiTest(ordinaryUser.rawToken(), null, null);
        String revokeAfterActivation = """
                {"expectedRawSha256":"%s","documentVersion":1,"basisUrl":"%s","scopes":[],
                 "privacyReviewed":false,"thirdPartyReviewed":false,"decision":"HOLD","reason":"withdraw","expectedPreviousReviewId":"%s"}
                """.formatted(generic.getRawSha256(), genericUrl, allowedAgainId);
        try {
            mvc.perform(post(genericPath + "/usage-review").cookie(cookie).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                    .content(revokeAfterActivation)).andExpect(status().isOk());
            if (ui != null) {
                restoreActualCsrfRepository();
                ui.getOutputStream().write("REVOKED\n".getBytes(StandardCharsets.UTF_8));
                ui.getOutputStream().flush();
                assertThat(ui.waitFor(90, java.util.concurrent.TimeUnit.SECONDS)).as("browser completion").isTrue();
                assertThat(ui.exitValue()).as("browser E2E").isZero();
            }
        } finally { if (ui != null && ui.isAlive()) ui.destroyForcibly(); }
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(reportPath).cookie(cookie))
                .andExpect(status().isOk()).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.status").value("STALE"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.explanation").doesNotExist())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.evidence.length()").value(0));
        mvc.perform(post(explanationPath).contentType(MediaType.APPLICATION_JSON).content(explanationRequest).cookie(cookie).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.status").value("STALE"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.explanation").doesNotExist());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(userSnapshotPath + "/" + snapshotId).cookie(cookie))
                .andExpect(status().isOk()).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.status").value("STALE"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.evidence.length()").value(0));
        assertThat(active.current()).get().extracting(RetrievalGeneration::getId).isEqualTo(activated);
        assertThat(retrieval.searchCandidates(searchRequest).candidates()).noneMatch(c -> c.sourceChunkId().equals(genericChunk));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> retrieval.getPassages(found.searchId(), java.util.List.of(genericHit.candidateId())))
                .isInstanceOf(com.financialhelper.retrieval.UnknownRetrievalCandidateException.class);
        var legacyRequest = new com.financialhelper.retrieval.OfficialSearchRequest("2025년", null, "Synthetic", null, null, java.util.List.of(), 10);
        assertThat(retrieval.searchCandidates(legacyRequest).candidates()).anyMatch(c -> c.sourceChunkId().equals(chunk.getId()));
        UUID activeHold = jdbc.queryForObject("SELECT id FROM source_document_usage_review WHERE previous_review_id=?", UUID.class, allowedAgainId);
        String rejectAfterActivation = """
                {"expectedRawSha256":"%s","documentVersion":1,"basisUrl":"%s","scopes":[],
                 "privacyReviewed":false,"thirdPartyReviewed":false,"decision":"REJECTED","reason":"unavailable","expectedPreviousReviewId":"%s"}
                """.formatted(generic.getRawSha256(), genericUrl, activeHold);
        mvc.perform(post(genericPath + "/usage-review").cookie(cookie).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(rejectAfterActivation)).andExpect(status().isOk());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(reportPath).cookie(cookie))
                .andExpect(status().isOk()).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.status").value("STALE"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.explanation").doesNotExist())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.evidence.length()").value(0));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(userSnapshotPath + "/" + snapshotId).cookie(cookie))
                .andExpect(status().isOk()).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.status").value("STALE"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.evidence.length()").value(0));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete(reportPath).cookie(cookie))
                .andExpect(status().isForbidden());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete(reportPath)
                        .cookie(new Cookie(AccountSessionCookie.NAME, otherLogin.rawToken())).with(csrf()))
                .andExpect(status().isNotFound());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete(reportPath).cookie(cookie).with(csrf()))
                .andExpect(status().isNoContent());
        assertThat(retrieval.searchCandidates(searchRequest).candidates()).noneMatch(c -> c.sourceChunkId().equals(genericChunk));
        mvc.perform(post(path).cookie(cookie).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(approved.replace("\"APPROVE\"", "\"REJECT\"")))
                .andExpect(status().isConflict());

        var policeRegistry = registries.findBySourceKeyAndEnabledTrue("police-campaign").orElseThrow();
        String policeText = "STEP 01. 보이스피싱 피해 입었다면 즉시 112로 전화하여 피해 사실을 알려주세요! STEP 02.";
        byte[] policeBytes = policeText.getBytes(StandardCharsets.UTF_8);
        var policeParsed = new SourceIngestionData.Parsed(policeRegistry.getCanonicalUrl(), "합성 안내", null,
                OffsetDateTime.now(ZoneOffset.UTC), "text/plain;charset=UTF-8", null, null, SourceHashing.sha256(policeBytes),
                SourceHashing.sha256(policeText), policeBytes, policeText);
        writer.saveIfCurrent(SourceIngestionData.Snapshot.from(policeRegistry), policeParsed);
        var police = documents.findBySourceRegistry_IdAndStatus(policeRegistry.getId(), SourceDocumentStatus.ACTIVE).orElseThrow();
        var policeChunk = fixtureChunk(police);
        String policeBase = "/api/v1/source-review/information/police-campaign";
        mvc.perform(post(policeBase + "/prepare").cookie(cookie).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"documentId":"%s","expectedRawSha256":"%s","temporalPolicy":"HOLD"}
                                """.formatted(police.getId(), police.getRawSha256())))
                .andExpect(status().isOk());
        String reject = """
                {"documentId":"%s","chunkId":"%s","expectedRawSha256":"%s",
                 "conditionsReviewed":false,"exceptionsReviewed":false,"decision":"REJECT"}
                """.formatted(police.getId(), policeChunk.getId(), police.getRawSha256());
        var gate = new java.util.concurrent.CountDownLatch(1);
        var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            java.util.concurrent.Callable<Integer> request = () -> {
                gate.await();
                return mvc.perform(post(policeBase + "/candidate-decision").cookie(cookie).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(reject)).andReturn().getResponse().getStatus();
            };
            var first = executor.submit(request);
            var second = executor.submit(request);
            gate.countDown();
            assertThat(first.get(30, java.util.concurrent.TimeUnit.SECONDS)).isEqualTo(204);
            assertThat(second.get(30, java.util.concurrent.TimeUnit.SECONDS)).isEqualTo(204);
        } finally { executor.shutdownNow(); }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM source_applicability_review WHERE source_document_id = ? AND review_status = 'REJECTED'",
                Long.class, police.getId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM source_review_decision_audit WHERE source_document_id = ?",
                Long.class, police.getId())).isEqualTo(1);

        var rightsRegistry = registries.findBySourceKeyAndEnabledTrue("fsc-consumer-complaints").orElseThrow();
        String datedText = "금융소비자와 금융회사간의 분쟁조정 또는 금융위원회·금융감독원에 대한 민원을 신청할 수 있습니다. "
                + "2025년 9월 1일 시행 금융민원 신청하기";
        byte[] datedBytes = datedText.getBytes(StandardCharsets.UTF_8);
        var datedParsed = new SourceIngestionData.Parsed(rightsRegistry.getCanonicalUrl(), "합성 시행일 안내", null,
                OffsetDateTime.now(ZoneOffset.UTC), "text/plain;charset=UTF-8", null, null, SourceHashing.sha256(datedBytes),
                SourceHashing.sha256(datedText), datedBytes, datedText);
        writer.saveIfCurrent(SourceIngestionData.Snapshot.from(rightsRegistry), datedParsed);
        var dated = documents.findBySourceRegistry_IdAndStatus(rightsRegistry.getId(), SourceDocumentStatus.ACTIVE).orElseThrow();
        var datedChunk = fixtureChunk(dated);
        mvc.perform(post("/api/v1/source-review/information/fsc-consumer-complaints/candidate-decision")
                        .cookie(cookie).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("""
                                {"documentId":"%s","chunkId":"%s","expectedRawSha256":"%s",
                                 "conditionsReviewed":true,"exceptionsReviewed":true,"decision":"APPROVE"}
                                """.formatted(dated.getId(), datedChunk.getId(), dated.getRawSha256())))
                .andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject("SELECT applicability_start_date FROM source_applicability_review WHERE source_document_id = ?",
                LocalDate.class, dated.getId())).isEqualTo(LocalDate.of(2025, 9, 1));

        var loanRegistry = registries.findBySourceKeyAndEnabledTrue("fsc-early-repayment-qa").orElseThrow();
        String loanText = "1. 기존계약분도 적용대상인지? 제도는 2025년 9월 1일 시행합니다.\n신규 계약만 해당하며 기존 계약은 적용되지 않습니다. 2. 새마을금고";
        byte[] loanBytes = loanText.getBytes(StandardCharsets.UTF_8);
        var loanParsed = new SourceIngestionData.Parsed(loanRegistry.getCanonicalUrl(), "합성 계약 조건", null,
                OffsetDateTime.now(ZoneOffset.UTC), "text/plain;charset=UTF-8", null, null, SourceHashing.sha256(loanBytes),
                SourceHashing.sha256(loanText), loanBytes, loanText);
        writer.saveIfCurrent(SourceIngestionData.Snapshot.from(loanRegistry), loanParsed);
        var loan = documents.findBySourceRegistry_IdAndStatus(loanRegistry.getId(), SourceDocumentStatus.ACTIVE).orElseThrow();
        var loanChunk = fixtureChunk(loan);
        String loanBase = "/api/v1/source-review/information/fsc-early-repayment-qa";
        mvc.perform(post(loanBase + "/prepare").cookie(cookie).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"documentId":"%s","expectedRawSha256":"%s","temporalPolicy":"EXPLICIT_EFFECTIVE",
                                 "applicabilityStartDate":"2025-09-01"}
                                """.formatted(loan.getId(), loan.getRawSha256())))
                .andExpect(status().isOk());
        mvc.perform(post(loanBase + "/candidate-decision").cookie(cookie).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"documentId":"%s","chunkId":"%s","expectedRawSha256":"%s",
                                 "conditionsReviewed":false,"exceptionsReviewed":true,"decision":"APPROVE"}
                                """.formatted(loan.getId(), loanChunk.getId(), loan.getRawSha256())))
                .andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM source_review_decision_audit WHERE source_document_id = ?",
                Long.class, loan.getId())).isZero();
        String loanDecision = """
                {"documentId":"%s","chunkId":"%s","expectedRawSha256":"%s",
                 "conditionsReviewed":true,"exceptionsReviewed":true,"decision":"%%s"}
                """.formatted(loan.getId(), loanChunk.getId(), loan.getRawSha256());
        var raceGate = new java.util.concurrent.CountDownLatch(1);
        var raceExecutor = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            java.util.function.Function<String, java.util.concurrent.Callable<Integer>> call = decision -> () -> {
                raceGate.await();
                return mvc.perform(post(loanBase + "/candidate-decision").cookie(cookie).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(loanDecision.formatted(decision)))
                        .andReturn().getResponse().getStatus();
            };
            var approve = raceExecutor.submit(call.apply("APPROVE"));
            var deny = raceExecutor.submit(call.apply("REJECT"));
            raceGate.countDown();
            assertThat(java.util.List.of(approve.get(30, java.util.concurrent.TimeUnit.SECONDS),
                    deny.get(30, java.util.concurrent.TimeUnit.SECONDS))).containsExactlyInAnyOrder(204, 409);
        } finally { raceExecutor.shutdownNow(); }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM source_review_decision_audit WHERE source_document_id = ?",
                Long.class, loan.getId())).isEqualTo(1);
    }

    /** Creates only synthetic reviewer-approved documents in the guarded temporary database. */
    private java.util.Map<UUID,String> prepareAdditionalEvaluationDocuments(Cookie cookie, UUID baseDocument) throws Exception {
        var texts = java.util.Map.of(
                "C", "정기적금은 12개월 이후 지급됩니다. 다만 중도해지는 제외합니다.",
                "D", "자유적금은 6개월 이후 지급됩니다. 다만 만기해지는 제외합니다.",
                "E", "주택대출은 36개월 이내 신청할 수 있습니다. 다만 연체자는 제외합니다.",
                "F", "교육대출은 24개월 이내 신청할 수 있습니다. 다만 무소득자는 제외합니다.",
                "G", "자동차보험은 사고일로부터 30일 이내 청구할 수 있습니다. 다만 렌터카는 제외합니다.",
                "H", "상해보험은 사고일로부터 60일 이내 청구할 수 있습니다. 다만 렌터카는 보상 대상입니다.");
        var mapper=tools.jackson.databind.json.JsonMapper.builder().build();
        var output=new java.util.LinkedHashMap<UUID,String>();
        for (String label : java.util.List.of("C","D","E","F","G","H")) {
            UUID registryId=UUID.randomUUID(); String url="https://synthetic.invalid/"+registryId;
            jdbc.update("INSERT INTO source_registry(id,source_key,organization_name,official_domain,canonical_url,acquisition_type,content_selector) VALUES (?,?,'Synthetic','synthetic.invalid',?,'HTML','.body')",registryId,"r4b2-"+registryId,url);
            var registration=java.util.Map.of("registryId",registryId,"html","<div class='body'><p>"+texts.get(label)+"</p></div>","title","합성 금융 안내 "+label,
                    "basisUrl",url,"reason","isolated synthetic review","privacyReviewed",true,"thirdPartyReviewed",true,"storageApproved",true);
            String json=mvc.perform(post("/api/v1/source-review/document-claims/text-registration").cookie(cookie).with(csrf())
                    .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(registration))).andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
            var created=mapper.readTree(json); UUID doc=UUID.fromString(created.get("documentId").asString());
            String hash=created.get("storedTextSha256").asString(); String path="/api/v1/source-review/document-claims/"+doc;
            UUID previous=jdbc.queryForObject("SELECT usage_review_id FROM source_text_registration WHERE source_document_id=?",UUID.class,doc);
            for (String decision : java.util.List.of("HOLD","ALLOWED")) {
                var usage=java.util.Map.of("expectedRawSha256",hash,"documentVersion",1,"basisUrl",url,
                        "scopes",java.util.List.of("STORE","EXTRACT","REVIEW","SEARCH","EXCERPT"),"privacyReviewed",true,
                        "thirdPartyReviewed",true,"decision",decision,"reason","isolated synthetic review","expectedPreviousReviewId",previous);
                String reviewJson=mvc.perform(post(path+"/usage-review").cookie(cookie).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(usage))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
                previous=UUID.fromString(mapper.readTree(reviewJson).get("id").asString());
            }
            String prepared=mvc.perform(post(path+"/prepare").cookie(cookie).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                    .content(mapper.writeValueAsString(java.util.Map.of("expectedRawSha256",hash))))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
            var claims=mapper.readTree(prepared); assertThat(claims.size()).isEqualTo(1);
            assertThat(claims.get(0).get("hold").asBoolean()).isFalse();
            String claim=claims.get(0).get("candidateId").asString();
            mvc.perform(post(path+"/"+claim+"/decision").cookie(cookie).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                    .content(mapper.writeValueAsString(java.util.Map.of("expectedRawSha256",hash,"decision","APPROVE"))))
                    .andExpect(status().isNoContent());
            output.put(doc,label);
        }
        String ready="/api/v1/source-review/information/fsc-card-use-qa/ready-candidate";
        String candidate=mvc.perform(post(ready).cookie(cookie).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(java.util.Map.of("documentId",baseDocument,"genericDocumentIds",output.keySet()))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8).replace("\"","");
        mvc.perform(post(ready+"/"+candidate+"/build").cookie(cookie).with(csrf())).andExpect(status().isNoContent());
        mvc.perform(post(ready+"/"+candidate+"/activate").cookie(cookie).with(csrf())).andExpect(status().isNoContent());
        assertThat(active.currentId()).contains(UUID.fromString(candidate));
        return output;
    }

    /** Real HTTP KURE, real PostgreSQL and product RRF on the same isolated active generation. */
    private void evaluateRealHybrid(java.util.Map<UUID,String> documentLabels,
            com.financialhelper.retrieval.KureRuntimeHttpClient realKure) throws Exception {
        record Case(String id, String query, java.util.Set<String> relevant) {}
        var cases = java.util.List.of(
                new Case("exact-deposit", "예금은", java.util.Set.of("A")),
                new Case("exact-loan", "신규", java.util.Set.of("B")),
                new Case("synonym-deposit", "저축", java.util.Set.of("A")),
                new Case("synonym-loan", "융자", java.util.Set.of("B")),
                new Case("particle-deposit", "예금", java.util.Set.of("A")),
                new Case("exception-loan", "신규 기존 계약은 제외합니다", java.util.Set.of("B")),
                new Case("date-shared", "2025년", java.util.Set.of("A", "B")),
                new Case("clarification", "신규 신청할 수 있습니다", java.util.Set.of("B")),
                new Case("follow-up-context", "신규 기존 계약은 제외합니다", java.util.Set.of("B")),
                new Case("new-product", "예금은", java.util.Set.of("A")),
                new Case("fixed-savings", "정기적금은", java.util.Set.of("C")),
                new Case("flexible-savings", "자유적금은", java.util.Set.of("D")),
                new Case("home-loan-exception", "주택대출은 연체자는 제외합니다", java.util.Set.of("E")),
                new Case("education-loan-exception", "교육대출은 무소득자는 제외합니다", java.util.Set.of("F")),
                new Case("car-insurance-period", "자동차보험은 30일", java.util.Set.of("G")),
                new Case("injury-insurance-period", "상해보험은 60일", java.util.Set.of("H")),
                new Case("opposite-exceptions", "렌터카는", java.util.Set.of("G","H")),
                new Case("underspecified-shared-term", "기존", java.util.Set.of()),
                new Case("unsupported", "해외주식환전수수료", java.util.Set.of()));
        UUID generation = active.currentId().orElseThrow();
        var members = jdbc.query("""
                SELECT c.source_chunk_id,d.id FROM source_document_claim c JOIN source_document d ON d.id=c.source_document_id
                JOIN source_chunk_indexing i ON i.source_chunk_id=c.source_chunk_id
                WHERE i.retrieval_generation_id=? AND i.indexing_status='READY'
                """, (rs,n) -> java.util.Map.entry(rs.getObject(1,UUID.class),rs.getObject(2,UUID.class)),
                generation);
        assertThat(members).hasSize(8);
        java.util.Map<UUID,String> labels = new java.util.HashMap<>();
        members.forEach(e -> labels.put(e.getKey(),java.util.Objects.requireNonNull(documentLabels.get(e.getValue()))));
        var ready = realKure.readiness(generation);
        assertThat(ready.ready()).isTrue();
        assertThat(ready.documentCount()).isEqualTo(jdbc.queryForObject(
                "SELECT count(*) FROM source_chunk_indexing WHERE retrieval_generation_id=? AND indexing_status='READY'", Integer.class, generation));
        var rows = new StringBuilder("# R4-B2 실제 활성 Generation 합성 평가\n\n제품 빌더와 임시 KURE HTTP 런타임이 같은 Generation을 사용한다. 여덟 Claim의 잠정 평가다. 원시 RRF와 키워드 보강 후 사용자 채택을 구분한다.\n\n| 사례 | 질문 | 정답 | 키워드 | KURE | 원시 RRF | 채택 | 상태 |\n|---|---|---|---|---|---|---|---|\n");
        var scores = new java.util.LinkedHashMap<String, double[]>();
        for (String name : java.util.List.of("keyword","kure","rawRrf","adopted")) scores.put(name,new double[3]);
        int positive = 0, negative = 0, falsePositive = 0, rawKureFalse = 0, rawRrfFalse = 0;
        long queryCallsBefore = org.mockito.Mockito.mockingDetails(kure).getInvocations().stream()
                .filter(i -> i.getMethod().getName().equals("query")).count();
        for (var item : cases) {
            var key = keywordSearch.searchApproved(item.query(),100).stream().filter(c -> labels.containsKey(c.getId())).toList();
            var sem = realKure.query(new com.financialhelper.retrieval.KureRuntimeClient.KureRuntimeQueryRequest(generation,item.query(),10,labels.keySet())).results();
            var semHits = sem.stream().map(h -> new com.financialhelper.retrieval.ReciprocalRankFusion.RankedHit(h.sourceChunkId(),h.rank(),h.maxSimScore(),com.financialhelper.retrieval.ReciprocalRankFusion.Branch.SEMANTIC)).toList();
            var keyHits = new java.util.ArrayList<com.financialhelper.retrieval.ReciprocalRankFusion.RankedHit>();
            for (int i=0;i<key.size();i++) keyHits.add(new com.financialhelper.retrieval.ReciprocalRankFusion.RankedHit(key.get(i).getId(),i+1,0,com.financialhelper.retrieval.ReciprocalRankFusion.Branch.KEYWORD));
            var rawAll = com.financialhelper.retrieval.ReciprocalRankFusion.fuse(semHits,keyHits,100).stream().map(h -> labels.get(h.sourceChunkId())).toList();
            var raw = rawAll.stream().limit(3).toList();
            var product = retrieval.searchDocumentClaims(item.query());
            assertThat(product.degraded()).isFalse();
            assertThat(product.coverageGaps()).doesNotContain("SEMANTIC_BRANCH_UNAVAILABLE", "KEYWORD_BRANCH_UNAVAILABLE");
            var adopted = product.candidates().stream().limit(3).map(c -> labels.get(c.sourceChunkId())).toList();
            if (item.id().equals("underspecified-shared-term")) {
                assertThat(product.status()).isEqualTo(com.financialhelper.retrieval.RetrievalStatus.NEEDS_CLARIFICATION);
                assertThat(adopted).isEmpty();
            }
            var keywordAll = key.stream().map(c -> labels.get(c.getId())).toList();
            var keyword = keywordAll.stream().limit(3).toList();
            var semantic = sem.stream().limit(3).map(h -> labels.get(h.sourceChunkId())).toList();
            if (product.status() == com.financialhelper.retrieval.RetrievalStatus.NEEDS_CLARIFICATION) {
                assertThat(adopted).isEmpty();
                assertThat(product.coverageGaps()).contains("QUESTION_SCOPE_UNCLEAR");
            } else {
                assertThat(adopted).containsExactlyElementsOf(rawAll.stream().filter(keywordAll::contains).limit(3).toList());
            }
            if (item.relevant().isEmpty()) { negative++; if (!adopted.isEmpty()) falsePositive++; if (!semantic.isEmpty()) rawKureFalse++; if (!raw.isEmpty()) rawRrfFalse++; }
            else {
                positive++;
                for (var entry : java.util.Map.of("keyword",keyword,"kure",semantic,"rawRrf",raw,"adopted",adopted).entrySet()) {
                    var values=scores.get(entry.getKey()); var ranking=entry.getValue();
                    long hits=ranking.stream().filter(item.relevant()::contains).distinct().count();
                    values[0]+=(double)hits/item.relevant().size(); values[1]+=hits/3.0;
                    double dcg=0,ideal=0; for(int i=0;i<ranking.size();i++) if(item.relevant().contains(ranking.get(i))) dcg+=1/(Math.log(i+2)/Math.log(2));
                    for(int i=0;i<Math.min(3,item.relevant().size());i++) ideal+=1/(Math.log(i+2)/Math.log(2)); values[2]+=dcg/ideal;
                }
            }
            rows.append("| ").append(item.id()).append(" | ").append(item.query()).append(" | ").append(item.relevant()).append(" | ").append(keyword).append(" | ").append(semantic).append(" | ").append(raw).append(" | ").append(adopted).append(" | ").append(product.status()).append(" |\n");
        }
        long queryCallsAfter = org.mockito.Mockito.mockingDetails(kure).getInvocations().stream()
                .filter(i -> i.getMethod().getName().equals("query")).count();
        assertThat(queryCallsAfter-queryCallsBefore).isEqualTo(cases.size());
        rows.append("\n양성 ").append(positive).append("건 macro 평균, P@3 분모=3. 무근거 ").append(negative)
                .append("건 중 원시 KURE 반환 ").append(rawKureFalse).append("건, 원시 RRF 반환 ").append(rawRrfFalse)
                .append("건, 사용자 부적절 채택 ").append(falsePositive).append("건.\n\n| 방식 | Recall@3 | Precision@3 | NDCG@3 |\n|---|---:|---:|---:|\n");
        for (var entry : scores.entrySet()) rows.append("| ").append(entry.getKey()).append(" | ")
                .append(String.format(java.util.Locale.ROOT,"%.4f | %.4f | %.4f",entry.getValue()[0]/positive,entry.getValue()[1]/positive,entry.getValue()[2]/positive)).append(" |\n");
        rows.append("\n| 합성 Claim | Chunk ID | Claim ID |\n|---|---|---|\n");
        for (var entry : labels.entrySet().stream().sorted(java.util.Map.Entry.comparingByValue()).toList()) {
            String claimId=jdbc.queryForObject("SELECT candidate_id FROM source_document_claim WHERE source_chunk_id=?",String.class,entry.getKey());
            rows.append("| ").append(entry.getValue()).append(" | ").append(entry.getKey()).append(" | ").append(claimId).append(" |\n");
        }
        var path=java.nio.file.Path.of("build","reports","r4b2-live-evaluation.md");
        java.nio.file.Files.createDirectories(path.getParent()); java.nio.file.Files.writeString(path,rows);
    }

    /** Provisional synthetic labels, fixed before running retrieval; never evaluates real documents. */
    private void evaluateSyntheticKeyword(UUID depositDocument, UUID loanDocument) throws Exception {
        record Case(String id, String query, java.util.Set<String> relevant) {}
        var cases = java.util.List.of(
                new Case("exact-deposit", "예금은", java.util.Set.of("A")),
                new Case("exact-loan", "신규", java.util.Set.of("B")),
                new Case("synonym-deposit", "저축", java.util.Set.of("A")),
                new Case("synonym-loan", "융자", java.util.Set.of("B")),
                new Case("particle-deposit", "예금", java.util.Set.of("A")),
                new Case("exception-loan", "신규 기존 계약은 제외합니다", java.util.Set.of("B")),
                new Case("date-shared", "2025년", java.util.Set.of("A", "B")),
                new Case("clarification", "신규 신청할 수 있습니다", java.util.Set.of("B")),
                new Case("follow-up-context", "신규 기존 계약은 제외합니다", java.util.Set.of("B")),
                new Case("new-product", "예금은", java.util.Set.of("A")),
                new Case("unsupported", "해외주식환전수수료", java.util.Set.of()));
        UUID generationId = active.currentId().orElseThrow();
        var corpus = jdbc.query("""
                SELECT c.source_chunk_id, s.body, d.id FROM source_document_claim c
                JOIN source_document d ON d.id=c.source_document_id
                JOIN source_chunk s ON s.id=c.source_chunk_id
                JOIN source_chunk_indexing i ON i.source_chunk_id=s.id AND i.retrieval_generation_id=? AND i.indexing_status='READY'
                WHERE d.id IN (?,?) AND s.review_status='APPROVED' ORDER BY d.id
                """, (rs,n) -> java.util.Map.of("id",rs.getObject(1,UUID.class).toString(),"text",rs.getString(2),
                        "label",rs.getObject(3,UUID.class).equals(depositDocument)?"A":"B"), generationId, depositDocument, loanDocument);
        assertThat(corpus).hasSize(2);
        var rows = new StringBuilder("# R4-B 합성 키워드 평가\n\n고정 합성 Claim A/B, 실제 격리 PostgreSQL 및 제품 검색 경로. KURE는 비활성 테스트 대역. 골든셋은 잠정이며 실사용 정확도가 아니다.\n\n| 사례 | 질문 | 기대 | 반환 순위 | 상태 |\n|---|---|---|---|---|\n");
        double recall = 0, precision = 0, ndcg = 0; int positives = 0;
        for (var item : cases) {
            var result = retrieval.searchDocumentClaims(item.query());
            var ranked = result.candidates().stream().limit(3).map(c ->
                    c.sourceDocumentId().equals(depositDocument) ? "A" : c.sourceDocumentId().equals(loanDocument) ? "B" : "OTHER").toList();
            rows.append("| ").append(item.id()).append(" | ").append(item.query()).append(" | ").append(item.relevant()).append(" | ").append(ranked)
                    .append(" | ").append(result.status()).append(" |\n");
            if (item.relevant().isEmpty()) continue;
            positives++;
            long hits = ranked.stream().filter(item.relevant()::contains).distinct().count();
            recall += (double) hits / item.relevant().size(); precision += hits / 3.0;
            double dcg = 0, ideal = 0;
            for (int i = 0; i < ranked.size(); i++) if (item.relevant().contains(ranked.get(i))) dcg += 1.0 / (Math.log(i + 2) / Math.log(2));
            for (int i = 0; i < Math.min(3, item.relevant().size()); i++) ideal += 1.0 / (Math.log(i + 2) / Math.log(2));
            ndcg += dcg / ideal;
        }
        rows.append("\n양성 ").append(positives).append("건의 macro 평균: Recall@3=").append(String.format(java.util.Locale.ROOT,"%.4f",recall/positives))
                .append(", Precision@3=").append(String.format(java.util.Locale.ROOT,"%.4f",precision/positives))
                .append(", NDCG@3=").append(String.format(java.util.Locale.ROOT,"%.4f",ndcg/positives))
                .append(". 무근거 질문은 별도 표시하며 평균에서 제외. P@3 분모는 항상 3.\n");
        var path = java.nio.file.Path.of("build", "reports", "r4b-keyword-evaluation.md");
        java.nio.file.Files.createDirectories(path.getParent()); java.nio.file.Files.writeString(path, rows);
        java.nio.file.Files.writeString(path.resolveSibling("r4b-synthetic-corpus.json"),
                tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(
                        java.util.Map.of("generationId",generationId.toString(),"documents",corpus,"cases",
                                cases.stream().map(c -> java.util.Map.of("id",c.id(),"query",c.query(),"relevant",c.relevant())).toList())));
    }

    private Process startUiTest(String sessionToken, UUID reviewDocumentId, String reviewBasisUrl) throws Exception {
        if (!"true".equals(System.getenv("FH_UI_E2E"))) return null;
        restoreActualCsrfRepository();
        var frontend = java.nio.file.Path.of("..", "financial-helper-frontend").toAbsolutePath().normalize();
        var builder = new ProcessBuilder("node", "tests/isolated-document-evidence-e2e.mjs");
        builder.directory(frontend.toFile());
        builder.redirectError(ProcessBuilder.Redirect.INHERIT);
        builder.environment().put("FH_UI_BACKEND_PORT", Integer.toString(httpPort));
        builder.environment().put("FH_UI_FRONTEND_PORT", Integer.toString(UI_PORT));
        builder.environment().put("FH_UI_ACCOUNT_SESSION", sessionToken);
        builder.environment().put("FH_UI_OTHER_SESSION", sessions.createSession(AccountProvider.NAVER,
                "ui-other-" + UUID.randomUUID(), "fixture").rawToken());
        if (reviewDocumentId != null) {
            builder.environment().put("FH_UI_REVIEW_DOCUMENT_ID", reviewDocumentId.toString());
            builder.environment().put("FH_UI_REVIEW_BASIS_URL", reviewBasisUrl);
            builder.environment().put("FH_UI_REGISTRY_ID", uiRegistrationRegistryId.toString());
        }
        Process process = builder.start();
        var reader = new java.io.BufferedReader(new java.io.InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
        var executor = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            String message = executor.submit(reader::readLine).get(180, java.util.concurrent.TimeUnit.SECONDS);
            if (!"READY".equals(message)) throw new IllegalStateException("Isolated browser setup failed: " + message);
            return process;
        } catch (Exception ex) { process.destroyForcibly(); throw ex; }
        finally { executor.shutdownNow(); }
    }

    private void restoreActualCsrfRepository() {
        // MockMvc csrf() swaps the shared filter's repository. The real HTTP server
        // must use the application's cookie repository while the browser runs.
        var filter = securityFilters.getFilters("/api/v1/information-evidence-snapshots").stream()
                .filter(org.springframework.security.web.csrf.CsrfFilter.class::isInstance).findFirst().orElseThrow();
        org.springframework.test.util.ReflectionTestUtils.setField(filter, "tokenRepository", csrfRepository);
    }

    private SourceChunk fixtureChunk(SourceDocument document) {
        var config = configurations.saveAndFlush(new SourceChunkConfiguration(
                "fixture-" + UUID.randomUUID(), "{}", OffsetDateTime.now(ZoneOffset.UTC)));
        var definition = OfficialInformationClaimSelector.select(document, config.getConfigVersion(), "{}").getFirst();
        return chunks.saveAndFlush(new SourceChunk(document, config, definition, OffsetDateTime.now(ZoneOffset.UTC)));
    }
}
