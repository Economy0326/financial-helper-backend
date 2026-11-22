package com.financialhelper.source;

import com.financialhelper.account.AccountProvider;
import com.financialhelper.account.AccountSessionCookie;
import com.financialhelper.account.AccountSessionService;
import com.financialhelper.retrieval.KureRuntimeClient;
import com.financialhelper.retrieval.RetrievalCorpusSnapshotService;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DocumentClaimBatchReadyIntegrationTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired SourceRegistryRepository registries;
    @Autowired SourceDocumentRepository documents;
    @Autowired SourceDocumentWriter writer;
    @Autowired AccountSessionService sessions;
    @Autowired ActiveRetrievalGenerationPersistenceService active;
    @Autowired RetrievalCorpusSnapshotService corpus;
    @Autowired DocumentClaimReviewerService reviewer;
    @Autowired com.financialhelper.retrieval.KureRuntimeProperties kureProperties;
    @Autowired com.financialhelper.retrieval.SourceKeywordSearchService keyword;
    @Autowired com.financialhelper.retrieval.DocumentAiExplanationQuota explanationQuota;
    @Autowired SourceChunkPersistenceService chunkPersistence;
    @Autowired MockMvc mvc;
    @MockitoBean com.financialhelper.retrieval.KureRuntimeHttpClient kure;
    @MockitoBean com.financialhelper.retrieval.KureRuntimeCompatibility compatibility;
    @MockitoBean OfficialSourceVerificationService verifier;

    @Test void firstBatchThenReapprovalRequiresANewGeneration() throws Exception {
        var counts = new ConcurrentHashMap<UUID, Integer>();
        boolean real = "true".equals(System.getenv("FH_R4B2_REAL_KURE"));
        var realClient = real ? new com.financialhelper.retrieval.KureRuntimeHttpClient(kureProperties,
                tools.jackson.databind.json.JsonMapper.builder().build()) : null;
        if (real) {
            when(kure.metadata()).thenAnswer(call -> realClient.metadata());
            when(kure.query(any())).thenAnswer(call -> realClient.query(call.getArgument(0)));
            doAnswer(call -> { new com.financialhelper.retrieval.KureRuntimeCompatibility(tools.jackson.databind.json.JsonMapper.builder().build())
                    .requireCompatible(call.getArgument(0), call.getArgument(1)); return null; }).when(compatibility).requireCompatible(any(), any());
        } else {
            when(kure.query(any())).thenAnswer(call -> {
                KureRuntimeClient.KureRuntimeQueryRequest request = call.getArgument(0);
                var hits = request.allowedDocumentIds().stream().map(id -> new KureRuntimeClient.KureRuntimeQueryResult.Hit(id, 1, 1.0, request.generationId())).toList();
                return new KureRuntimeClient.KureRuntimeQueryResult(request.generationId(), hits);
            });
        }
        when(kure.build(any())).thenAnswer(call -> {
            KureRuntimeClient.KureRuntimeBuildRequest request = call.getArgument(0);
            assertThat(request.documents()).allMatch(document -> document.text().startsWith("합성 테스트 "));
            assertThat(request.documents()).noneMatch(document -> document.text().contains("검토전용문맥표식"));
            assertThat(request.documents()).noneMatch(document -> document.text().endsWith("\n공식 채널"));
            counts.put(request.generationId(), request.documents().size());
            if (real) return realClient.build(request);
            return new KureRuntimeClient.KureRuntimeBuildResult(request.generationId(), request.documents().stream().map(KureRuntimeClient.KureRuntimeBuildRequest.Document::id).toList(), "{}");
        });
        when(kure.readiness(any())).thenAnswer(call -> {
            UUID id = call.getArgument(0);
            String hash = jdbc.queryForObject("SELECT corpus_snapshot_sha256 FROM retrieval_generation WHERE id=?", String.class, id);
            return real ? realClient.readiness(id) : new KureRuntimeClient.KureRuntimeReadiness(id, true, counts.get(id), "{\"corpusSnapshotSha256\":\"" + hash + "\"}");
        });
        var login = sessions.createSession(AccountProvider.NAVER, "batch-" + UUID.randomUUID(), "fixture");
        Cookie cookie = new Cookie(AccountSessionCookie.NAME, login.rawToken());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options("/api/v1/information-evidence-snapshots/reports/" + UUID.randomUUID())
                        .header("Origin", "http://localhost:3000")
                        .header("Access-Control-Request-Method", "DELETE")
                        .header("Access-Control-Request-Headers", "x-xsrf-token"))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string("Access-Control-Allow-Methods", org.hamcrest.Matchers.containsString("DELETE")));
        jdbc.update("INSERT INTO source_reviewer_grant(account_id,granted_by) VALUES (?,'isolated')", login.account().getId());
        assertThat(active.currentId()).isEmpty();
        var first = createClaim(cookie, "합성 대출 신청은 공식 채널에서 확인해야 합니다.\n검토전용문맥표식입니다.");
        var second = createClaim(cookie, "합성 예금 신청은 지정된 금융기관에서 확인해야 합니다.");
        String selection = "{\"documentIds\":[\"" + first.document() + "\"]}";
        mvc.perform(post("/api/v1/source-review/document-claims/batch-ready").cookie(cookie).contentType(MediaType.APPLICATION_JSON).content(selection)).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/source-review/document-claims/batch-ready").with(csrf()).contentType(MediaType.APPLICATION_JSON).content(selection)).andExpect(status().isForbidden());
        long firstStart = System.nanoTime();
        UUID generation = prepare(cookie, first.document(), second.document());
        run(cookie, generation, "build", 204); run(cookie, generation, "activate", 204);
        System.out.println("READY_FIRST_MS=" + java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - firstStart));
        long generationCount = jdbc.queryForObject("SELECT count(*) FROM retrieval_generation", Long.class);
        String unchangedBody = "{\"documentIds\":[\"" + first.document() + "\",\"" + second.document() + "\"]}";
        mvc.perform(post("/api/v1/source-review/document-claims/batch-ready/unchanged").with(csrf()).contentType(MediaType.APPLICATION_JSON).content(unchangedBody))
                .andExpect(status().isForbidden());
        long repeatStart = System.nanoTime();
        mvc.perform(post("/api/v1/source-review/document-claims/batch-ready/unchanged").cookie(cookie).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(unchangedBody))
                .andExpect(status().isOk()).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().string("true"));
        System.out.println("READY_UNCHANGED_MS=" + java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - repeatStart));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM retrieval_generation", Long.class)).isEqualTo(generationCount);
        assertThat(active.currentId()).contains(generation);
        assertThat(counts).hasSize(1);
        UUID reused = prepare(cookie, first.document(), second.document());
        assertThat(reused).isEqualTo(generation);
        run(cookie, reused, "build", 204); run(cookie, reused, "activate", 204);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM retrieval_generation", Long.class)).isEqualTo(generationCount);
        assertThat(counts).hasSize(1);
        var json = tools.jackson.databind.json.JsonMapper.builder().build();
        String faqBody = json.writeValueAsString(java.util.Map.of("claimId",first.candidate(),"claimIds",java.util.List.of(first.candidate(),second.candidate()),"question","합성 금융정보 신청 방법은?","displayOrder",1,"published",true,"meaningConfirmed",true));
        mvc.perform(post("/api/v1/source-review/faq").with(csrf()).contentType(MediaType.APPLICATION_JSON).content(faqBody)).andExpect(status().isForbidden());
        String faqResponse = mvc.perform(post("/api/v1/source-review/faq").cookie(cookie).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(faqBody))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String faqId = json.readTree(faqResponse).get("id").asString();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/faq"))
                .andExpect(status().isOk()).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$[0].evidence[0].claimId").value(first.candidate())).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$[0].evidence.length()").value(2));
        String unpublished = json.writeValueAsString(java.util.Map.of("claimId",first.candidate(),"question","합성 질문 수정","displayOrder",2,"published",false,"meaningConfirmed",true));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/v1/source-review/faq/"+faqId).cookie(cookie).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(unpublished)).andExpect(status().isOk());
        String defaultOrder = json.writeValueAsString(java.util.Map.of("claimId", second.candidate(), "question", "합성 새 질문", "published", false, "meaningConfirmed", true));
        String defaultFaq = mvc.perform(post("/api/v1/source-review/faq").cookie(cookie).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(defaultOrder))
                .andExpect(status().isOk()).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.displayOrder").value(3))
                .andReturn().getResponse().getContentAsString();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/v1/source-review/faq/" + json.readTree(defaultFaq).get("id").asString()).cookie(cookie).with(csrf()))
                .andExpect(status().isNoContent());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/faq")).andExpect(status().isOk()).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$").isEmpty());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/v1/source-review/faq/"+faqId).cookie(cookie).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(faqBody)).andExpect(status().isOk());
        String reportQuestion = "대출과 예금 차이는 무엇인가요?";
        String reportBody = json.writeValueAsString(java.util.Map.of("question",reportQuestion));
        String snapJson = mvc.perform(post("/api/v1/information-evidence-snapshots").cookie(cookie).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(reportBody))
                .andExpect(status().isOk()).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.evidence.length()").value(2)).andReturn().getResponse().getContentAsString();
        String multiSnapshot = json.readTree(snapJson).get("snapshotId").asString();
        UUID snapshotId = UUID.fromString(multiSnapshot);
        assertThat(jdbc.queryForObject("SELECT question FROM document_claim_snapshot WHERE id=?", String.class, snapshotId))
                .startsWith("sha256:").doesNotContain(reportQuestion);
        String wrongQuestion = json.writeValueAsString(java.util.Map.of("question", "다른 합성 질문"));
        mvc.perform(post("/api/v1/information-evidence-snapshots/"+multiSnapshot+"/report").cookie(cookie).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(wrongQuestion)).andExpect(status().isConflict());
        String savedJson = mvc.perform(post("/api/v1/information-evidence-snapshots/"+multiSnapshot+"/report").cookie(cookie).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(reportBody)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(jdbc.queryForObject("SELECT question FROM document_claim_snapshot WHERE id=?", String.class, snapshotId)).isEqualTo(reportQuestion);
        String multiReport = json.readTree(savedJson).get("reportId").asString();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/source-review/document-claims/"+first.document()+"/change-status")).andExpect(status().isForbidden());
        String currentHash = jdbc.queryForObject("SELECT content_sha256 FROM source_document WHERE id=?", String.class, first.document());
        var unchanged = new OfficialSourceVerificationService.Result(first.document(), 1, first.hash(), "official", "official", OffsetDateTime.now(), 200, true, first.hash(), currentHash, "test", "HTML_NORMALIZED_MATCH_RAW_CHANGED_REVIEW_REQUIRED", false, true, false);
        var changed = new OfficialSourceVerificationService.Result(first.document(), 1, first.hash(), "official", "official", OffsetDateTime.now().plusSeconds(1), 200, true, "other", "different", "test", "CONTENT_CHANGED_REVIEW_REQUIRED", false, true, false);
        when(verifier.verifyRegistered(anyString(), eq(first.document()), anyBoolean())).thenReturn(unchanged).thenThrow(new IllegalStateException("synthetic unavailable")).thenReturn(changed).thenReturn(unchanged);
        String checkUrl = "/api/v1/source-review/document-claims/"+first.document()+"/change-check";
        mvc.perform(post(checkUrl).cookie(cookie).with(csrf())).andExpect(status().isOk()).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.result").value("UNCHANGED"));
        mvc.perform(post(checkUrl).cookie(cookie).with(csrf())).andExpect(status().isOk()).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.result").value("FAILED")).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.changeDetected").value(false));
        mvc.perform(post(checkUrl).cookie(cookie).with(csrf())).andExpect(status().isOk()).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.result").value("CHANGED"));
        mvc.perform(post(checkUrl).cookie(cookie).with(csrf())).andExpect(status().isOk()).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.changeDetected").value(true));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/source-review/document-claims/"+first.document()+"/change-status").cookie(cookie)).andExpect(status().isOk()).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.changeDetected").value(true));
        assertThat(reviewer.evaluateEligibility(first.document(), first.candidate(), first.hash(), "EXTERNAL_AI").eligible()).isFalse();
        assertThat(corpus.readyChunks(generation).stream().anyMatch(c -> c.sourceChunkId().equals(first.chunk()))).isFalse();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/faq")).andExpect(status().isOk()).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$").isEmpty());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/information-evidence-snapshots/reports/"+multiReport).cookie(cookie)).andExpect(status().isOk()).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.status").value("PARTIAL"));
        mvc.perform(post("/api/v1/source-review/faq/"+faqId+"/unpublish").with(csrf())).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/source-review/faq/"+faqId+"/unpublish").cookie(cookie).with(csrf())).andExpect(status().isNoContent());
        jdbc.update("DELETE FROM document_change_observation WHERE source_document_id=?", first.document());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/v1/source-review/faq/"+faqId).cookie(cookie).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(faqBody)).andExpect(status().isOk());
        var other = sessions.createSession(AccountProvider.NAVER, "faq-other-"+UUID.randomUUID(), "fixture");
        Cookie otherCookie = new Cookie(AccountSessionCookie.NAME, other.rawToken());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/information-evidence-snapshots/reports/"+multiReport).cookie(otherCookie)).andExpect(status().isNotFound());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/v1/information-evidence-snapshots/reports/"+multiReport).cookie(otherCookie).with(csrf())).andExpect(status().isNotFound());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/information-evidence-snapshots/reports").cookie(cookie)).andExpect(status().isOk()).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$[0].reportId").value(multiReport));
        var clarificationJson = mvc.perform(post("/api/v1/information-evidence-snapshots").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"합성\"}"))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.status").value("NEEDS_CLARIFICATION"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.evidence").isEmpty())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.options[0].label").isNotEmpty())
                .andReturn().getResponse().getContentAsString();
        var clarificationMapper = tools.jackson.databind.json.JsonMapper.builder().build();
        var selected = clarificationMapper.readTree(clarificationJson).get("options").get(0);
        String selectedBody = clarificationMapper.writeValueAsString(java.util.Map.of("question", "합성", "selection",
                java.util.Map.of("title", selected.get("title").asString())));
        mvc.perform(post("/api/v1/information-evidence-snapshots").with(csrf()).contentType(MediaType.APPLICATION_JSON).content(selectedBody))
                .andExpect(status().isOk()).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.status").value("READY_CANDIDATES"));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/information-evidence-snapshots/topics"))
                .andExpect(status().isOk()).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$[0]").isNotEmpty());
        mvc.perform(post("/api/v1/information-evidence-snapshots").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"대출과 예금 차이는 무엇인가요?\"}"))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.status").value("READY_CANDIDATES"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.evidence.length()").value(2));
        UUID quotaSnapshot = UUID.randomUUID();
        jdbc.update("INSERT INTO document_claim_snapshot(id,account_id,retrieval_generation_id,question) VALUES (?,?,?,'합성 질문')", quotaSnapshot, login.account().getId(), generation);
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/information-evidence-snapshots/explanation-quota"))
                .andExpect(status().isForbidden());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/information-evidence-snapshots/explanation-quota").cookie(cookie))
                .andExpect(status().isOk()).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.remaining").value(3))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.available").value(false))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.reason").value("FEATURE_DISABLED"));
        UUID staleRequest = UUID.randomUUID();
        assertThat(explanationQuota.reserve(login.account().getId(), quotaSnapshot, staleRequest)).isTrue();
        assertThat(explanationQuota.succeed(login.account().getId(), staleRequest, () -> false)).isFalse();
        explanationQuota.fail(login.account().getId(), staleRequest);
        var executor = java.util.concurrent.Executors.newFixedThreadPool(8);
        var requests = java.util.stream.IntStream.range(0, 8).mapToObj(i -> UUID.randomUUID()).toList();
        try {
            var tasks = requests.stream().<java.util.concurrent.Callable<UUID>>map(request -> () ->
                    explanationQuota.reserve(login.account().getId(), quotaSnapshot, request) ? request : null).toList();
            var reserved = new java.util.ArrayList<UUID>();
            for (var future : executor.invokeAll(tasks)) { var request = future.get(); if (request != null) reserved.add(request); }
            assertThat(reserved).hasSize(3);
            assertThat(explanationQuota.reserve(login.account().getId(), quotaSnapshot, reserved.getFirst())).isFalse();
            explanationQuota.fail(login.account().getId(), reserved.removeFirst());
            assertThat(explanationQuota.status(login.account().getId()).remaining()).isEqualTo(3);
            UUID replacement = UUID.randomUUID();
            assertThat(explanationQuota.reserve(login.account().getId(), quotaSnapshot, replacement)).isTrue();
            reserved.add(replacement);
            for (UUID request : reserved) assertThat(explanationQuota.succeed(login.account().getId(), request, () -> true)).isTrue();
            assertThat(explanationQuota.status(login.account().getId()).remaining()).isZero();
            assertThat(explanationQuota.reserve(login.account().getId(), quotaSnapshot, UUID.randomUUID())).isFalse();
            jdbc.update("UPDATE document_ai_explanation_attempt SET quota_date=(CURRENT_TIMESTAMP AT TIME ZONE 'Asia/Seoul')::date-1 WHERE account_id=?", login.account().getId());
            assertThat(explanationQuota.status(login.account().getId()).remaining()).isEqualTo(3);
        } finally { executor.shutdownNow(); }
        assertThat(active.currentId()).contains(generation);
        assertThat(corpus.readyChunks(generation)).extracting(RetrievalCorpusSnapshotService.EligibleChunk::sourceChunkId).contains(first.chunk(), second.chunk());
        assertThat(keyword.searchApproved("대출", 10)).extracting(SourceChunk::getId).contains(first.chunk());
        long beforeGuest = jdbc.queryForObject("SELECT count(*) FROM document_claim_snapshot", Long.class);
        String guestBody = "{\"question\":\"합성 대출 신청\"}";
        mvc.perform(post("/api/v1/information-evidence-snapshots").contentType(MediaType.APPLICATION_JSON).content(guestBody))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/information-evidence-snapshots").with(csrf()).contentType(MediaType.APPLICATION_JSON).content(guestBody))
                .andExpect(status().isOk()).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.status").value("READY_CANDIDATES"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.snapshotId").doesNotExist())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.evidence[0].claimId").value(first.candidate()));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM document_claim_snapshot", Long.class)).isEqualTo(beforeGuest);
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/information-evidence-snapshots/" + UUID.randomUUID()))
                .andExpect(status().isForbidden());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/information-evidence-snapshots/reports/" + UUID.randomUUID()))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/information-evidence-snapshots/" + UUID.randomUUID() + "/explanation").with(csrf()))
                .andExpect(status().isForbidden());
        if (real) assertThat(realClient.query(new KureRuntimeClient.KureRuntimeQueryRequest(generation, "대출 신청", 2,
                java.util.Set.of(first.chunk(), second.chunk()))).results()).isNotEmpty();
        UUID snapshot = null;
        UUID report = null;
        if (real) {
            String question = "합성 대출 신청은 공식 채널에서 확인해야 합니다";
            var mapper = tools.jackson.databind.json.JsonMapper.builder().build();
            String body = mapper.writeValueAsString(java.util.Map.of("question", question));
            String result = mvc.perform(post("/api/v1/information-evidence-snapshots").cookie(cookie).with(csrf())
                    .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            var evidence = mapper.readTree(result);
            assertThat(evidence.get("status").asString()).isEqualTo("READY_CANDIDATES");
            snapshot = UUID.fromString(evidence.get("snapshotId").asString());
            String reportJson = mvc.perform(post("/api/v1/information-evidence-snapshots/" + snapshot + "/report").cookie(cookie).with(csrf())
                    .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            report = UUID.fromString(mapper.readTree(reportJson).get("reportId").asString());
        }
        String path = "/api/v1/source-review/document-claims/" + first.document() + "/" + first.candidate();
        mvc.perform(post(path + "/withdraw").cookie(cookie).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedRawSha256\":\"" + first.hash() + "\",\"reason\":\"test\"}")).andExpect(status().isNoContent());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/faq")).andExpect(status().isOk()).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$").isEmpty());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/information-evidence-snapshots/reports/"+multiReport).cookie(cookie)).andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.status").value("PARTIAL"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.evidence[0].claimId").value(second.candidate()));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/v1/information-evidence-snapshots/reports/"+multiReport).cookie(cookie).with(csrf())).andExpect(status().isNoContent());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/v1/source-review/faq/"+faqId).cookie(cookie).with(csrf())).andExpect(status().isNoContent());
        assertThat(corpus.readyChunks(generation)).extracting(RetrievalCorpusSnapshotService.EligibleChunk::sourceChunkId).doesNotContain(first.chunk()).contains(second.chunk());
        mvc.perform(post("/api/v1/source-review/document-claims/batch-ready/unchanged").cookie(cookie).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(unchangedBody))
                .andExpect(status().isOk()).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().string("false"));
        mvc.perform(post("/api/v1/information-evidence-snapshots").with(csrf()).contentType(MediaType.APPLICATION_JSON).content(guestBody))
                .andExpect(status().isOk()).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.status").value("NO_MATCH"));
        if (real) {
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/information-evidence-snapshots/" + snapshot).cookie(cookie))
                    .andExpect(status().isOk()).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.status").value("STALE"));
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/information-evidence-snapshots/reports/" + report).cookie(cookie))
                    .andExpect(status().isOk()).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.status").value("STALE"));
            mvc.perform(post("/api/v1/information-evidence-snapshots").cookie(cookie).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"question\":\"합성 대출 신청은 공식 채널에서 확인해야 합니다\"}"))
                    .andExpect(status().isOk()).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.status").value("NO_MATCH"));
        }
        UUID removal = prepare(cookie, first.document());
        run(cookie, removal, "build", 204); run(cookie, removal, "activate", 204);
        assertThat(active.currentId()).contains(removal);
        assertThat(corpus.readyChunks(removal)).extracting(RetrievalCorpusSnapshotService.EligibleChunk::sourceChunkId)
                .doesNotContain(first.chunk()).contains(second.chunk());
        mvc.perform(post(path + "/decision").cookie(cookie).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedRawSha256\":\"" + first.hash() + "\",\"decision\":\"APPROVE\"}")).andExpect(status().isNoContent());
        assertThat(corpus.readyChunks(generation)).extracting(RetrievalCorpusSnapshotService.EligibleChunk::sourceChunkId).doesNotContain(first.chunk());
        mvc.perform(post("/api/v1/source-review/document-claims/batch-ready/unchanged").cookie(cookie).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(unchangedBody))
                .andExpect(status().isOk()).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().string("false"));
        UUID replacement = prepare(cookie, first.document());
        run(cookie, replacement, "build", 204); run(cookie, replacement, "activate", 204);
        assertThat(active.currentId()).contains(replacement);
        assertThat(corpus.readyChunks(replacement)).extracting(RetrievalCorpusSnapshotService.EligibleChunk::sourceChunkId).contains(first.chunk(), second.chunk());
        if (real) {
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/information-evidence-snapshots/" + snapshot).cookie(cookie))
                    .andExpect(status().isOk()).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.status").value("STALE"));
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/information-evidence-snapshots/reports/" + report).cookie(cookie))
                    .andExpect(status().isOk()).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.status").value("STALE"));
            mvc.perform(post("/api/v1/information-evidence-snapshots").cookie(cookie).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"question\":\"합성 대출 신청은 공식 채널에서 확인해야 합니다\"}"))
                    .andExpect(status().isOk()).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.status").value("READY_CANDIDATES"))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.evidence[0].claimId").value(first.candidate()));
        }
        // Coexisting extraction versions require an explicit selection and replace only this document.
        String versionFaq = mvc.perform(post("/api/v1/source-review/faq").cookie(cookie).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(faqBody))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String versionFaqId = json.readTree(versionFaq).get("id").asString();
        UUID legacyChunk = addLegacyClaim(cookie, first);
        mvc.perform(post("/api/v1/source-review/document-claims/batch-ready").cookie(cookie).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"documentIds\":[\"" + first.document() + "\"]}")).andExpect(status().isConflict());
        assertThat(active.currentId()).contains(replacement);
        UUID legacyGeneration = prepareVersion(cookie, first.document(), "LEGACY");
        run(cookie, legacyGeneration, "build", 204); run(cookie, legacyGeneration, "activate", 204);
        assertThat(corpus.readyChunks(legacyGeneration)).extracting(RetrievalCorpusSnapshotService.EligibleChunk::sourceChunkId)
                .contains(legacyChunk, second.chunk()).doesNotContain(first.chunk());
        String legacyCandidate = jdbc.queryForObject("SELECT candidate_id FROM source_document_claim WHERE source_chunk_id=?", String.class, legacyChunk);
        String eligiblePath = "/api/v1/source-review/faq/eligible-claims?documentId=" + first.document();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(eligiblePath))
                .andExpect(status().isForbidden());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(eligiblePath).cookie(cookie))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$", org.hamcrest.Matchers.hasItem(legacyCandidate)))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$", org.hamcrest.Matchers.not(org.hamcrest.Matchers.hasItem(first.candidate()))));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/faq")).andExpect(status().isOk()).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$").isEmpty());
        replacement = prepareVersion(cookie, first.document(), "LATEST");
        run(cookie, replacement, "build", 204); run(cookie, replacement, "activate", 204);
        assertThat(corpus.readyChunks(replacement)).extracting(RetrievalCorpusSnapshotService.EligibleChunk::sourceChunkId)
                .contains(first.chunk(), second.chunk()).doesNotContain(legacyChunk);
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(eligiblePath).cookie(cookie))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$", org.hamcrest.Matchers.hasItem(first.candidate())))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$", org.hamcrest.Matchers.not(org.hamcrest.Matchers.hasItem(legacyCandidate))));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/faq")).andExpect(status().isOk()).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$[0].evidence[0].claimId").value(first.candidate()));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/v1/source-review/faq/" + versionFaqId).cookie(cookie).with(csrf())).andExpect(status().isNoContent());
        UUID failing = prepareVersion(cookie, first.document(), "LEGACY");
        doThrow(new com.financialhelper.retrieval.KureRuntimeException("synthetic build failure")).when(kure).build(any());
        mvc.perform(post("/api/v1/source-review/document-claims/batch-ready/" + failing + "/build").cookie(cookie).with(csrf()))
                .andExpect(status().is5xxServerError());
        assertThat(active.currentId()).contains(replacement);
        String rightsFaq = mvc.perform(post("/api/v1/source-review/faq").cookie(cookie).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(faqBody))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String rightsFaqId = json.readTree(rightsFaq).get("id").asString();
        UUID usageId = jdbc.queryForObject("SELECT id FROM source_document_usage_review WHERE source_document_id=? ORDER BY decided_at DESC LIMIT 1", UUID.class, first.document());
        String sourceUrl = jdbc.queryForObject("SELECT canonical_url FROM source_document WHERE id=?", String.class, first.document());
        String hold = json.writeValueAsString(java.util.Map.of("expectedRawSha256",first.hash(),"documentVersion",1,"basisUrl",sourceUrl,"scopes",java.util.List.of(),"privacyReviewed",false,"thirdPartyReviewed",false,"decision","HOLD","reason","isolated test","expectedPreviousReviewId",usageId.toString()));
        mvc.perform(post("/api/v1/source-review/document-claims/"+first.document()+"/usage-review").cookie(cookie).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(hold)).andExpect(status().isOk());
        mvc.perform(post("/api/v1/source-review/document-claims/batch-ready/unchanged").cookie(cookie).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(unchangedBody))
                .andExpect(status().isOk()).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().string("false"));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/faq")).andExpect(status().isOk()).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$").isEmpty());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/v1/source-review/faq/"+rightsFaqId).cookie(cookie).with(csrf())).andExpect(status().isNoContent());
    }

    private Fixture createClaim(Cookie cookie, String text) throws Exception {
        UUID registry = UUID.randomUUID(); String url = "https://synthetic.invalid/" + registry;
        jdbc.update("INSERT INTO official_source_domain(domain,organization_name) VALUES ('synthetic.invalid','Synthetic') ON CONFLICT DO NOTHING");
        jdbc.update("INSERT INTO source_registry(id,source_key,organization_name,official_domain,canonical_url,acquisition_type) VALUES (?,?,'Synthetic','synthetic.invalid',?,'HTML')", registry, "batch-" + registry, url);
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        var parsed = new SourceIngestionData.Parsed(url, "합성 테스트 " + text.substring(0, 5), null, OffsetDateTime.now(ZoneOffset.UTC), "text/plain;charset=UTF-8", null, null,
                SourceHashing.sha256(bytes), SourceHashing.sha256(text), bytes, text);
        writer.saveIfCurrent(SourceIngestionData.Snapshot.from(registries.findById(registry).orElseThrow()), parsed);
        var doc = documents.findBySourceRegistry_IdAndStatus(registry, SourceDocumentStatus.ACTIVE).orElseThrow();
        String root = "/api/v1/source-review/document-claims/" + doc.getId();
        String usage = "{\"expectedRawSha256\":\"" + doc.getRawSha256() + "\",\"documentVersion\":1,\"basisUrl\":\"" + url + "\",\"scopes\":[\"STORE\",\"EXTRACT\",\"REVIEW\",\"SEARCH\",\"EXCERPT\"],\"privacyReviewed\":true,\"thirdPartyReviewed\":true,\"decision\":\"ALLOWED\",\"reason\":\"synthetic\"}";
        mvc.perform(post(root + "/usage-review").cookie(cookie).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(usage)).andExpect(status().isOk());
        mvc.perform(post(root + "/prepare").cookie(cookie).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"expectedRawSha256\":\"" + doc.getRawSha256() + "\"}")).andExpect(status().isOk());
        String candidate = jdbc.queryForObject("SELECT candidate_id FROM source_document_claim WHERE source_document_id=? AND hold=false LIMIT 1", String.class, doc.getId());
        UUID chunk = jdbc.queryForObject("SELECT source_chunk_id FROM source_document_claim WHERE candidate_id=?", UUID.class, candidate);
        mvc.perform(post(root + "/" + candidate + "/decision").cookie(cookie).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedRawSha256\":\"" + doc.getRawSha256() + "\",\"decision\":\"APPROVE\"}")).andExpect(status().isNoContent());
        return new Fixture(doc.getId(), candidate, chunk, doc.getRawSha256());
    }
    private UUID addLegacyClaim(Cookie cookie, Fixture fixture) throws Exception {
        var doc = documents.findWithSourceRegistryById(fixture.document()).orElseThrow();
        var old = new GenericClaimEvidenceBundle().prepare(doc, "generic-claim-v1").getFirst();
        var chunk = chunkPersistence.saveIfAbsent(doc, new SourceChunkData.Definition(old.extractorVersion(), "{}", 0, old.body(), null, null, null,
                "normalized-content:utf16:" + old.start() + "-" + old.end(), old.start(), old.end(), "{}"));
        UUID review = UUID.randomUUID();
        jdbc.update("INSERT INTO source_applicability_review(id,source_document_id,reviewed_raw_sha256,scope_kind,review_target_kind,review_status,condition_required,exception_required,temporal_policy) VALUES (?,?,?,'COMMON','DOCUMENT_CLAIM','PENDING',false,false,'HOLD')", review, fixture.document(), fixture.hash());
        jdbc.update("INSERT INTO source_applicability_chunk(review_id,source_document_id,source_chunk_id,evidence_role) VALUES (?,?,?,'SUPPORT')", review, fixture.document(), chunk.getId());
        jdbc.update("INSERT INTO source_document_claim(candidate_id,source_document_id,document_version,raw_sha256,content_sha256,extractor_version,review_id,source_chunk_id,context_start,context_end,hold,hold_reason) VALUES (?,?,?,?,?,?,?,?,?,?,false,'')", old.candidateId(), fixture.document(), 1, fixture.hash(), doc.getContentSha256(), old.extractorVersion(), review, chunk.getId(), old.context().start(), old.context().end());
        mvc.perform(post("/api/v1/source-review/document-claims/" + fixture.document() + "/" + old.candidateId() + "/decision").cookie(cookie).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedRawSha256\":\"" + fixture.hash() + "\",\"decision\":\"APPROVE\"}")).andExpect(status().isNoContent());
        return chunk.getId();
    }
    private UUID prepareVersion(Cookie cookie, UUID doc, String version) throws Exception {
        String body = "{\"documentIds\":[\"" + doc + "\"],\"versions\":{\"" + doc + "\":\"" + version + "\"}}";
        String response = mvc.perform(post("/api/v1/source-review/document-claims/batch-ready").cookie(cookie).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return UUID.fromString(response.replace("\"", ""));
    }
    private UUID prepare(Cookie cookie, UUID... docs) throws Exception {
        String ids = java.util.Arrays.stream(docs).map(id -> "\"" + id + "\"").collect(java.util.stream.Collectors.joining(","));
        String response = mvc.perform(post("/api/v1/source-review/document-claims/batch-ready").cookie(cookie).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"documentIds\":[" + ids + "]}")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return UUID.fromString(response.replace("\"", ""));
    }
    private void run(Cookie cookie, UUID id, String action, int expected) throws Exception {
        mvc.perform(post("/api/v1/source-review/document-claims/batch-ready/" + id + "/" + action).cookie(cookie).with(csrf())).andExpect(status().is(expected));
    }
    private record Fixture(UUID document, String candidate, UUID chunk, String hash) {}
}
