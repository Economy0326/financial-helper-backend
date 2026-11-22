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
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DocumentClaimReviewerIntegrationTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired SourceRegistryRepository registries;
    @Autowired SourceDocumentRepository documents;
    @Autowired AccountSessionService sessions;
    @Autowired MockMvc mvc;
    @Autowired DocumentClaimReviewerService reviewer;
    @Autowired SourceChunkPersistenceService chunkPersistence;
    @Autowired RetrievalGenerationRepository generations;
    @Autowired com.financialhelper.retrieval.RetrievalGenerationIndexingPersistenceService indexing;
    @Autowired SourceDocumentWriter sourceWriter;
    @org.springframework.test.context.bean.override.mockito.MockitoBean(name="pinnedOfficialSourceContentFetcher")
    PinnedOfficialSourceContentFetcher officialFetcher;

    @Test void newVersionInheritsOnlyExactReviewedEvidenceAndKeepsOldHistory() throws Exception {
        UUID registryId = UUID.randomUUID(); String url = "https://synthetic.invalid/" + registryId;
        jdbc.update("INSERT INTO official_source_domain(domain,organization_name) VALUES ('synthetic.invalid','Synthetic') ON CONFLICT DO NOTHING");
        jdbc.update("INSERT INTO source_registry(id,source_key,organization_name,official_domain,canonical_url,acquisition_type,content_selector) VALUES (?,?,'Synthetic','synthetic.invalid',?,'HTML','.body')", registryId, "version-" + registryId, url);
        var login = sessions.createSession(AccountProvider.NAVER, "version-" + UUID.randomUUID(), "fixture");
        Cookie cookie = new Cookie(AccountSessionCookie.NAME, login.rawToken());
        jdbc.update("INSERT INTO source_reviewer_grant(account_id,granted_by) VALUES (?,'isolated')", login.account().getId());
        var mapper = tools.jackson.databind.json.JsonMapper.builder().build();
        var request = new java.util.HashMap<String,Object>();
        request.put("registryId", registryId); request.put("basisUrl", url); request.put("reason", "합성 이용조건 검토");
        request.put("title", "합성 금융 안내"); request.put("privacyReviewed", true); request.put("thirdPartyReviewed", true); request.put("storageApproved", true);
        String prefix = "<div class='body'><h2>신청 안내</h2><p>합성 대출은 공식 채널에서 신청할 수 있습니다.</p><h2>서류 안내</h2><p>합성 대출 신청자는 관련 서류를 제출해야 합니다.</p><h2>추가 안내</h2>";
        request.put("html", prefix + "<p>합성 대출을 이용할 수 있습니다.</p></div>");
        String firstJson = mvc.perform(post("/api/v1/source-review/document-claims/text-registration").cookie(cookie).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(request)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        var first = mapper.readTree(firstJson); UUID oldId = UUID.fromString(first.get("documentId").asString()); String oldHash = first.get("storedTextSha256").asString();
        var old = jdbc.query("SELECT c.candidate_id,s.body FROM source_document_claim c JOIN source_chunk s ON s.id=c.source_chunk_id WHERE c.source_document_id=? AND NOT c.hold ORDER BY s.sequence", (rs,n) -> new String[]{rs.getString(1),rs.getString(2)}, oldId);
        assertThat(old).hasSizeGreaterThanOrEqualTo(2);
        for (int i=0; i<2; i++) {
            String decision = mapper.writeValueAsString(java.util.Map.of("expectedRawSha256", oldHash, "decision", i == 0 ? "APPROVE" : "REJECT"));
            mvc.perform(post("/api/v1/source-review/document-claims/"+oldId+"/"+old.get(i)[0]+"/decision").cookie(cookie).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(decision)).andExpect(status().isNoContent());
        }
        request.put("html", prefix + "<p>합성 대출은 이용할 수 없습니다.</p></div>");
        String secondJson = mvc.perform(post("/api/v1/source-review/document-claims/text-registration").cookie(cookie).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(request)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        UUID newId = UUID.fromString(mapper.readTree(secondJson).get("documentId").asString());
        String newHash = mapper.readTree(secondJson).get("storedTextSha256").asString();
        assertThat(newId).isNotEqualTo(oldId);
        assertThat(jdbc.queryForList("SELECT outcome FROM source_claim_version_comparison WHERE current_document_id=?", String.class, newId)).contains("INHERITED_APPROVED", "INHERITED_REJECTED", "NOT_IN_LATEST_EXTRACTION");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM source_review_decision_audit WHERE source_document_id=? AND decision IN ('APPROVED','REJECTED')", Long.class, oldId)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM source_claim_version_comparison WHERE current_document_id=? AND inherited_audit_id IS NOT NULL AND previous_audit_id IS NOT NULL", Long.class, newId)).isEqualTo(2);
        mvc.perform(get("/api/v1/source-review/document-claims/"+newId+"/version-comparison").cookie(cookie).param("expectedRawSha256", newHash))
                .andExpect(status().isOk()).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.inheritedApproved").value(1))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.inheritedRejected").value(1))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.excludedCount").value(1));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM source_chunk_indexing i JOIN source_document_claim c ON c.source_chunk_id=i.source_chunk_id WHERE c.source_document_id=?", Long.class, newId)).isZero();
        Long beforeRepeat = jdbc.queryForObject("SELECT count(*) FROM source_claim_version_comparison WHERE current_document_id=?", Long.class, newId);
        mvc.perform(post("/api/v1/source-review/document-claims/"+newId+"/prepare").cookie(cookie).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"expectedRawSha256\":\""+newHash+"\"}")).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM source_claim_version_comparison WHERE current_document_id=?", Long.class, newId)).isEqualTo(beforeRepeat);
        request.put("html", prefix + "<p>합성 대출을 이용할 수 있습니다.</p></div>");
        String thirdJson = mvc.perform(post("/api/v1/source-review/document-claims/text-registration").cookie(cookie).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(request)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        UUID thirdId = UUID.fromString(mapper.readTree(thirdJson).get("documentId").asString());
        assertThat(jdbc.queryForList("SELECT r.review_status FROM source_document_claim c JOIN source_chunk s ON s.id=c.source_chunk_id JOIN source_applicability_review r ON r.id=c.review_id WHERE c.source_document_id=? AND s.body LIKE '%이용할 수 있습니다%'", String.class, thirdId)).containsOnly("PENDING");
        request.put("reason", "변경된 이용조건 근거 검토");
        request.put("html", prefix + "<p>합성 대출은 특정 기간에 이용할 수 있습니다.</p></div>");
        String fourthJson = mvc.perform(post("/api/v1/source-review/document-claims/text-registration").cookie(cookie).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(request)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        UUID fourthId = UUID.fromString(mapper.readTree(fourthJson).get("documentId").asString());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM source_claim_version_comparison WHERE current_document_id=? AND outcome IN ('INHERITED_APPROVED','INHERITED_REJECTED')", Long.class, fourthId)).isZero();
    }

    @Test void policyNewsTrackingVariantsReuseTheSameRegistryEntry() throws Exception {
        jdbc.update("INSERT INTO official_source_domain(domain,organization_name) VALUES ('korea.kr','Synthetic') ON CONFLICT DO NOTHING");
        var login = sessions.createSession(AccountProvider.NAVER, "url-normalize-" + UUID.randomUUID(), "fixture");
        Cookie cookie = new Cookie(AccountSessionCookie.NAME, login.rawToken());
        jdbc.update("INSERT INTO source_reviewer_grant(account_id,granted_by) VALUES (?,'isolated')", login.account().getId());
        String root = "/api/v1/source-review/document-claims/registry-candidates";
        String url = "https://www.korea.kr/news/policyNewsView.do?newsId=123456789";
        var mapper = tools.jackson.databind.json.JsonMapper.builder().build();
        String first = mvc.perform(post(root).cookie(cookie).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(java.util.Map.of("domain", "korea.kr", "url", url + "&utm_source=fixture#part"))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        String second = mvc.perform(post(root).cookie(cookie).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(java.util.Map.of("domain", "korea.kr", "url", url))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(mapper.readTree(first).get("registryId").asString()).isEqualTo(mapper.readTree(second).get("registryId").asString());
        assertThat(mapper.readTree(first).get("officialUrl").asString()).isEqualTo(url);
        mvc.perform(post(root).cookie(cookie).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(java.util.Map.of("domain", "korea.kr", "url", url + "&unknown=1"))))
                .andExpect(status().isConflict());
    }

    @Test void latestExtractionPreservesLegacyDecisionAndIsIdempotent() throws Exception {
        UUID registryId = UUID.randomUUID(); String url = "https://synthetic.invalid/" + registryId;
        jdbc.update("INSERT INTO official_source_domain(domain,organization_name) VALUES ('synthetic.invalid','Synthetic') ON CONFLICT DO NOTHING");
        jdbc.update("INSERT INTO source_registry(id,source_key,organization_name,official_domain,canonical_url,acquisition_type) VALUES (?,?,'Synthetic','synthetic.invalid',?,'HTML')", registryId, "legacy-" + registryId, url);
        var registry = registries.findById(registryId).orElseThrow();
        String text = "합성 금융 안내\n대출 신청은 공식 채널에서 확인해야 합니다.";
        byte[] raw = text.getBytes(StandardCharsets.UTF_8);
        var parsed = new SourceIngestionData.Parsed(url, "합성 금융 안내", null, OffsetDateTime.now(ZoneOffset.UTC), "text/plain", null, null,
                SourceHashing.sha256(raw), SourceHashing.sha256(text), raw, text);
        var doc = documents.saveAndFlush(new SourceDocument(registry, 1, parsed));
        var login = sessions.createSession(AccountProvider.NAVER, "legacy-" + UUID.randomUUID(), "fixture");
        Cookie cookie = new Cookie(AccountSessionCookie.NAME, login.rawToken());
        jdbc.update("INSERT INTO source_reviewer_grant(account_id,granted_by) VALUES (?,'isolated')", login.account().getId());
        String root = "/api/v1/source-review/document-claims/" + doc.getId();
        String usage = "{\"expectedRawSha256\":\"" + doc.getRawSha256() + "\",\"documentVersion\":1,\"basisUrl\":\"" + url + "\",\"scopes\":[\"STORE\",\"EXTRACT\",\"REVIEW\",\"SEARCH\",\"EXCERPT\"],\"privacyReviewed\":true,\"thirdPartyReviewed\":true,\"decision\":\"ALLOWED\",\"reason\":\"fixture\"}";
        mvc.perform(post(root + "/usage-review").cookie(cookie).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(usage)).andExpect(status().isOk());
        var old = new GenericClaimEvidenceBundle().prepare(doc, "generic-claim-v1").getFirst();
        var chunk = chunkPersistence.saveIfAbsent(doc, new SourceChunkData.Definition(old.extractorVersion(), "{}", 0, old.body(), null, null, null,
                "normalized-content:utf16:" + old.start() + "-" + old.end(), old.start(), old.end(), "{}"));
        UUID reviewId = UUID.randomUUID();
        jdbc.update("INSERT INTO source_applicability_review(id,source_document_id,reviewed_raw_sha256,scope_kind,review_target_kind,review_status,condition_required,exception_required,temporal_policy) VALUES (?,?,?,'COMMON','DOCUMENT_CLAIM','PENDING',false,false,'HOLD')", reviewId, doc.getId(), doc.getRawSha256());
        jdbc.update("INSERT INTO source_applicability_chunk(review_id,source_document_id,source_chunk_id,evidence_role) VALUES (?,?,?,'SUPPORT')", reviewId, doc.getId(), chunk.getId());
        jdbc.update("INSERT INTO source_document_claim(candidate_id,source_document_id,document_version,raw_sha256,content_sha256,extractor_version,review_id,source_chunk_id,context_start,context_end,hold,hold_reason) VALUES (?,?,?,?,?,?,?,?,?,?,false,'')", old.candidateId(), doc.getId(), 1, doc.getRawSha256(), doc.getContentSha256(), old.extractorVersion(), reviewId, chunk.getId(), old.context().start(), old.context().end());
        mvc.perform(post(root + "/" + old.candidateId() + "/decision").cookie(cookie).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"expectedRawSha256\":\"" + doc.getRawSha256() + "\",\"decision\":\"APPROVE\"}")).andExpect(status().isNoContent());
        long audit = jdbc.queryForObject("SELECT count(*) FROM source_review_decision_audit WHERE review_id=?", Long.class, reviewId);
        mvc.perform(post(root + "/prepare-latest").with(csrf()).contentType(MediaType.APPLICATION_JSON).content(versionBody(doc))).andExpect(status().isForbidden());
        jdbc.execute("CREATE FUNCTION fixture_reject_latest() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'synthetic extraction failure'; END $$");
        jdbc.execute("CREATE TRIGGER fixture_reject_latest BEFORE INSERT ON source_document_claim FOR EACH ROW EXECUTE FUNCTION fixture_reject_latest()");
        try {
            mvc.perform(post(root + "/prepare-latest").cookie(cookie).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(versionBody(doc))).andExpect(status().isInternalServerError());
            assertThat(jdbc.queryForObject("SELECT count(*) FROM source_chunk WHERE source_document_id=? AND chunk_config_version='generic-claim-v3'", Long.class, doc.getId())).isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM source_applicability_review WHERE source_document_id=?", Long.class, doc.getId())).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT review_status FROM source_applicability_review WHERE id=?", String.class, reviewId)).isEqualTo("APPROVED");
        } finally {
            jdbc.execute("DROP TRIGGER fixture_reject_latest ON source_document_claim");
            jdbc.execute("DROP FUNCTION fixture_reject_latest()");
        }
        for (int i = 0; i < 2; i++) mvc.perform(post(root + "/prepare-latest").cookie(cookie).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(versionBody(doc))).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM source_document_claim WHERE source_document_id=? AND extractor_version='generic-claim-v3'", Long.class, doc.getId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT review_status FROM source_applicability_review WHERE id=?", String.class, reviewId)).isEqualTo("APPROVED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM source_review_decision_audit WHERE review_id=?", Long.class, reviewId)).isEqualTo(audit);
        assertThat(jdbc.queryForObject("SELECT r.review_status FROM source_document_claim c JOIN source_applicability_review r ON r.id=c.review_id WHERE c.source_document_id=? AND c.extractor_version='generic-claim-v3'", String.class, doc.getId())).isEqualTo("PENDING");
        String summaries = mvc.perform(get("/api/v1/source-review/document-claims").cookie(cookie)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        var summaryJson = tools.jackson.databind.json.JsonMapper.builder().build().readTree(summaries);
        var summary = java.util.stream.StreamSupport.stream(summaryJson.spliterator(), false).filter(item -> doc.getId().toString().equals(item.get("documentId").asString())).findFirst().orElseThrow();
        assertThat(summary.get("approvedCount").asInt()).isZero();
        assertThat(summary.get("pendingCount").asInt()).isEqualTo(1);
    }

    @Test void newDocumentUsesV3AndRepeatedPreparePreservesDecisions() throws Exception {
        UUID registryId = UUID.randomUUID(); String url = "https://synthetic.invalid/" + registryId;
        jdbc.update("INSERT INTO official_source_domain(domain,organization_name) VALUES ('synthetic.invalid','Synthetic') ON CONFLICT DO NOTHING");
        jdbc.update("INSERT INTO source_registry(id,source_key,organization_name,official_domain,canonical_url,acquisition_type,content_selector) VALUES (?,?,'Synthetic','synthetic.invalid',?,'HTML','.body')", registryId, "extra-" + registryId, url);
        var login = sessions.createSession(AccountProvider.NAVER, "extra-" + UUID.randomUUID(), "fixture");
        Cookie cookie = new Cookie(AccountSessionCookie.NAME, login.rawToken());
        jdbc.update("INSERT INTO source_reviewer_grant(account_id,granted_by) VALUES (?,'isolated-fixture')", login.account().getId());
        var mapper = tools.jackson.databind.json.JsonMapper.builder().build();
        var request = java.util.Map.of("registryId", registryId, "html", "<div class='body'><p>[합성 범위]</p><p>대출 신청은 공식 채널에서 확인해야 합니다.</p><p>합성 대출의 기존 계약은 제외합니다.</p><p>합성 대출의 만기는 7일입니다.</p></div>",
                "title", "합성 대출 안내", "basisUrl", url, "reason", "합성 권리 검토", "privacyReviewed", true, "thirdPartyReviewed", true, "storageApproved", true);
        String response = mvc.perform(post("/api/v1/source-review/document-claims/text-registration").cookie(cookie).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(request))).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        UUID id = UUID.fromString(mapper.readTree(response).get("documentId").asString());
        var doc = documents.findWithSourceRegistryById(id).orElseThrow();
        var originalIds = new GenericClaimEvidenceBundle().prepare(doc, "generic-claim-v3").stream().map(GenericClaimEvidenceBundle.Bundle::candidateId).toList();
        String path = "/api/v1/source-review/document-claims/" + id + "/prepare";
        mvc.perform(post(path).cookie(cookie).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(versionBody(doc))).andExpect(status().isOk());
        var all = jdbc.queryForList("SELECT candidate_id FROM source_document_claim WHERE source_document_id=? ORDER BY candidate_id", String.class, id);
        assertThat(all).containsAll(originalIds);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM source_document_claim WHERE source_document_id=? AND extractor_version='generic-claim-v3'", Long.class, id)).isGreaterThan(0);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM source_document_claim WHERE source_document_id=? AND extractor_version<>'generic-claim-v3'", Long.class, id)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM source_applicability_review WHERE source_document_id=? AND review_status<>'PENDING'", Long.class, id)).isZero();
        mvc.perform(post(path).cookie(cookie).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(versionBody(doc))).andExpect(status().isOk());
        assertThat(jdbc.queryForList("SELECT candidate_id FROM source_document_claim WHERE source_document_id=? ORDER BY candidate_id", String.class, id)).isEqualTo(all);
        String extra = jdbc.queryForObject("SELECT c.candidate_id FROM source_document_claim c JOIN source_chunk s ON s.id=c.source_chunk_id WHERE c.source_document_id=? AND c.extractor_version='generic-claim-v3' AND c.hold=false AND s.body LIKE '%만기%'", String.class, id);
        mvc.perform(post("/api/v1/source-review/document-claims/" + id + "/" + extra + "/decision").cookie(cookie).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"expectedRawSha256\":\"" + doc.getRawSha256() + "\",\"decision\":\"APPROVE\"}"))
                .andExpect(status().isNoContent());
        UUID approval = jdbc.queryForObject("SELECT r.current_claim_approval_id FROM source_document_claim c JOIN source_applicability_review r ON r.id=c.review_id WHERE c.candidate_id=?", UUID.class, extra);
        mvc.perform(post("/api/v1/source-review/document-claims/" + id + "/" + extra + "/withdraw").cookie(cookie).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(versionBody(doc))).andExpect(status().isNoContent());
        mvc.perform(post("/api/v1/source-review/document-claims/" + id + "/" + extra + "/decision").cookie(cookie).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"expectedRawSha256\":\"" + doc.getRawSha256() + "\",\"decision\":\"APPROVE\"}"))
                .andExpect(status().isNoContent());
        UUID renewed = jdbc.queryForObject("SELECT r.current_claim_approval_id FROM source_document_claim c JOIN source_applicability_review r ON r.id=c.review_id WHERE c.candidate_id=?", UUID.class, extra);
        assertThat(renewed).isNotEqualTo(approval);
    }

    @Test void reviewerCanRegisterAndPreviewOnlyAnAllowedUrlWithoutStoringContent() throws Exception {
        jdbc.update("INSERT INTO official_source_domain(domain,organization_name) VALUES ('synthetic.invalid','Synthetic') ON CONFLICT DO NOTHING");
        var login = sessions.createSession(AccountProvider.NAVER, "url-fixture-" + UUID.randomUUID(), "fixture");
        Cookie cookie = new Cookie(AccountSessionCookie.NAME, login.rawToken());
        String url = "https://synthetic.invalid/" + UUID.randomUUID();
        String path = "/api/v1/source-review/document-claims/registry-candidates";
        String body = "{\"domain\":\"synthetic.invalid\",\"url\":\"" + url + "\"}";
        mvc.perform(post(path).cookie(cookie).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden());
        jdbc.update("INSERT INTO source_reviewer_grant(account_id,granted_by) VALUES (?,'isolated-fixture')", login.account().getId());
        mvc.perform(post(path).cookie(cookie).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden());
        mvc.perform(post(path).cookie(cookie).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"domain\":\"synthetic.invalid\",\"url\":\"https://unrelated.invalid/page\"}"))
                .andExpect(result -> assertThat(result.getResponse().getStatus()).isBetween(400, 499));
        String json = mvc.perform(post(path).cookie(cookie).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        var mapper = tools.jackson.databind.json.JsonMapper.builder().build();
        UUID registryId = UUID.fromString(mapper.readTree(json).get("registryId").asString());
        String duplicate = mvc.perform(post(path).cookie(cookie).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(mapper.readTree(duplicate).get("registryId").asString()).isEqualTo(registryId.toString());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM source_document WHERE source_registry_id=?", Long.class, registryId)).isZero();
        org.mockito.Mockito.verifyNoInteractions(officialFetcher);
        String html = "<html><head><title>합성 안내</title></head><body><main>신규 대출은 2025년 이후 신청할 수 있습니다. 다만 기존 계약은 제외합니다. 대출 신청은 공식 채널에서 확인해야 합니다. 본 안내는 합성 테스트 자료이며 실제 금융상품이 아닙니다. 신청 조건은 공식 문서의 내용을 반드시 확인해야 합니다.</main></body></html>";
        org.mockito.Mockito.when(officialFetcher.fetch(org.mockito.ArgumentMatchers.any())).thenReturn(new SourceIngestionData.Fetched(url, "text/html", null, null, html.getBytes(StandardCharsets.UTF_8), OffsetDateTime.now(ZoneOffset.UTC), 200));
        mvc.perform(post(path + "/" + registryId + "/preview").cookie(cookie).with(csrf()))
                .andExpect(status().isOk()).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.usageStatus").value("NOT_REVIEWED"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.officialUrl").value(url));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM source_document WHERE source_registry_id=?", Long.class, registryId)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM source_document_usage_review u JOIN source_document d ON d.id=u.source_document_id WHERE d.source_registry_id=?", Long.class, registryId)).isZero();
    }

    @Test void registeredOfficialSourceIsFetchedThenPreparedOnlyAfterReviewerConsent() throws Exception {
        UUID registryId = UUID.randomUUID(); String url = "https://synthetic.invalid/" + registryId;
        jdbc.update("INSERT INTO official_source_domain(domain,organization_name) VALUES ('synthetic.invalid','Synthetic') ON CONFLICT DO NOTHING");
        jdbc.update("INSERT INTO source_registry(id,source_key,organization_name,official_domain,canonical_url,acquisition_type,content_selector) VALUES (?,?,'Synthetic','synthetic.invalid',?,'HTML','.body')", registryId, "fetch-" + registryId, url);
        var login = sessions.createSession(AccountProvider.NAVER, "fetch-fixture-" + UUID.randomUUID(), "fixture");
        Cookie cookie = new Cookie(AccountSessionCookie.NAME, login.rawToken());
        jdbc.update("INSERT INTO source_reviewer_grant(account_id,granted_by) VALUES (?,'isolated-fixture')", login.account().getId());
        String html = "<html><head><title>합성 안내</title></head><body><div class='body'><p>신규 대출은 2025년 이후 신청할 수 있습니다. 다만 기존 계약은 제외합니다.</p><p>대출 신청은 공식 채널에서 확인해야 합니다. 본 안내는 합성 테스트 자료이며 실제 금융상품이 아닙니다.</p><p>신청 조건은 공식 문서의 내용을 반드시 확인해야 합니다.</p></div></body></html>";
        org.mockito.Mockito.when(officialFetcher.fetch(org.mockito.ArgumentMatchers.any())).thenReturn(new SourceIngestionData.Fetched(url, "text/html;charset=UTF-8", null, null, html.getBytes(StandardCharsets.UTF_8), OffsetDateTime.now(ZoneOffset.UTC), 200));
        String path = "/api/v1/source-review/document-claims/registered-source";
        String body = "{\"registryId\":\"" + registryId + "\",\"basisUrl\":\"" + url + "\",\"reason\":\"합성 검토\",\"privacyReviewed\":true,\"thirdPartyReviewed\":true,\"storageApproved\":true}";
        mvc.perform(post(path).cookie(cookie).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden());
        mvc.perform(post(path).cookie(cookie).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body.replace("\"storageApproved\":true", "\"storageApproved\":false"))).andExpect(status().isConflict());
        org.mockito.Mockito.verifyNoInteractions(officialFetcher);
        var unavailable = new SourceIngestionData.Fetched(url, "text/html", null, null,
                "<html><head><title>login</title></head><body><input type='password'></body></html>".getBytes(StandardCharsets.UTF_8), OffsetDateTime.now(ZoneOffset.UTC), 200);
        org.mockito.Mockito.when(officialFetcher.fetch(org.mockito.ArgumentMatchers.any())).thenReturn(unavailable,
                new SourceIngestionData.Fetched(url, "text/html;charset=UTF-8", null, null, html.getBytes(StandardCharsets.UTF_8), OffsetDateTime.now(ZoneOffset.UTC), 200));
        mvc.perform(post(path).cookie(cookie).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isConflict());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM source_document WHERE source_registry_id=?", Long.class, registryId)).isZero();
        String result = mvc.perform(post(path).cookie(cookie).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        UUID documentId = UUID.fromString(tools.jackson.databind.json.JsonMapper.builder().build().readTree(result).get("documentId").asString());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM source_document_claim WHERE source_document_id=?", Long.class, documentId)).isGreaterThan(0);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM source_applicability_review WHERE source_document_id=? AND review_status='APPROVED'", Long.class, documentId)).isZero();
        assertThat(jdbc.queryForObject("SELECT normalized_content FROM source_document WHERE id=?", String.class, documentId)).doesNotContain("<html", "<p>");
    }

    @Test void seededLegacyHtmlRemainsDistinctFromNewGenericSources() {
        assertThat(LegacyHtmlSourcePolicy.allowsLegacyPath(UUID.fromString("20000000-0000-0000-0000-000000000104"), "kb-card-compensation-process")).isTrue();
        assertThat(LegacyHtmlSourcePolicy.allowsLegacyPath(UUID.randomUUID(), "kb-card-compensation-process")).isFalse();
        assertThat(LegacyHtmlSourcePolicy.allowsLegacyPath(UUID.randomUUID(), "new-official-source")).isFalse();
    }

    @Test void textOnlyRegistrationRequiresExplicitRightsAndStoresNoHtml() throws Exception {
        UUID registryId = UUID.randomUUID();
        String url = "https://synthetic.invalid/" + registryId;
        jdbc.update("INSERT INTO official_source_domain(domain,organization_name) VALUES ('synthetic.invalid','Synthetic') ON CONFLICT DO NOTHING");
        jdbc.update("INSERT INTO source_registry(id,source_key,organization_name,official_domain,canonical_url,acquisition_type,content_selector) VALUES (?,?,'Synthetic','synthetic.invalid',?,'HTML','.body')", registryId, "text-" + registryId, url);
        var unsafeBytes = "<div class='body'>원본 HTML</div>".getBytes(StandardCharsets.UTF_8);
        var unsafeParsed = new SourceIngestionData.Parsed(url, "합성", null, OffsetDateTime.now(ZoneOffset.UTC), "text/html", null, null,
                SourceHashing.sha256(unsafeBytes), SourceHashing.sha256("원본 HTML"), unsafeBytes, "원본 HTML");
        var storedRegistry = registries.findById(registryId).orElseThrow();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> documents.saveAndFlush(new SourceDocument(storedRegistry, 1, unsafeParsed)))
                .isInstanceOf(RuntimeException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM source_document WHERE source_registry_id=?", Long.class, registryId)).isZero();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> sourceWriter.saveIfCurrent(SourceIngestionData.Snapshot.from(registries.findById(registryId).orElseThrow()), unsafeParsed))
                .isInstanceOf(SourceIngestionException.class).hasMessageContaining("text-only");
        var login = sessions.createSession(AccountProvider.NAVER, "text-fixture-" + UUID.randomUUID(), "fixture");
        Cookie cookie = new Cookie(AccountSessionCookie.NAME, login.rawToken());
        String html = "<html><head><script>SECRET_HEAD</script></head><body><div class='body'><p>신규 대출은 2025년 이후 적용됩니다. 다만 기존 계약은 제외합니다.</p><p>대출 신청은 공식 채널에서 확인해야 합니다.</p><img src='SECRET_IMAGE' alt='' role='presentation'><script>SECRET_SCRIPT</script><style>SECRET_STYLE</style></div></body></html>";
        var mapper = tools.jackson.databind.json.JsonMapper.builder().build();
        java.util.Map<String,Object> request = new java.util.HashMap<>();
        request.put("registryId", registryId); request.put("html", html); request.put("title", "합성 금융 안내");
        request.put("basisUrl", url); request.put("reason", "합성 권리 검토");
        request.put("privacyReviewed", true); request.put("thirdPartyReviewed", true); request.put("storageApproved", true);
        String path = "/api/v1/source-review/document-claims/text-registration";
        String previewPath = "/api/v1/source-review/document-claims/table-preview";
        String previewJson = mapper.writeValueAsString(java.util.Map.of("registryId", registryId,
                "html", "<div class='body'><table><tr><th>대상</th><th>예외</th></tr><tr><td>신규</td><td>기존 제외</td></tr></table></div>"));
        mvc.perform(post(previewPath).cookie(cookie).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(previewJson)).andExpect(status().isForbidden());
        mvc.perform(post(path).cookie(cookie).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(request))).andExpect(status().isForbidden());
        jdbc.update("INSERT INTO source_reviewer_grant(account_id,granted_by) VALUES (?,'isolated-fixture')", login.account().getId());
        mvc.perform(post(previewPath).cookie(cookie).contentType(MediaType.APPLICATION_JSON).content(previewJson)).andExpect(status().isForbidden());
        mvc.perform(post(previewPath).cookie(cookie).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(previewJson))
                .andExpect(status().isOk()).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.tables[0].cells[3].text").value("기존 제외"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.status").value("CONTENT_REVIEW_REQUIRED"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM source_document WHERE source_registry_id=?", Long.class, registryId)).isZero();
        mvc.perform(post(path).cookie(cookie).contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(request))).andExpect(status().isForbidden());
        request.put("storageApproved", false);
        mvc.perform(post(path).cookie(cookie).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(request))).andExpect(status().isConflict());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM source_document WHERE source_registry_id=?", Long.class, registryId)).isZero();
        request.put("storageApproved", true);
        request.put("html", "<div class='body'><table><tr><td>문의 02-1234-5678</td></tr></table>예금은 일정한 금액을 맡기는 금융상품을 의미합니다.</div>");
        mvc.perform(post(path).cookie(cookie).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(request))).andExpect(status().isConflict());
        request.put("html", "<div class='body'><p>예금은 일정한 금액을 맡기는 금융상품을 의미합니다.</p><img alt='상품별 필수 조건' src='not-stored'></div>");
        mvc.perform(post(path).cookie(cookie).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(request))).andExpect(status().isConflict());
        request.put("html", html);
        String json = mvc.perform(post(path).cookie(cookie).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(request)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        var response = mapper.readTree(json);
        UUID documentId = UUID.fromString(response.get("documentId").asString());
        var doc = documents.findById(documentId).orElseThrow();
        assertThat(new String(doc.getOriginalContent(), StandardCharsets.UTF_8)).isEqualTo(doc.getNormalizedContent())
                .doesNotContain("SECRET_SCRIPT", "SECRET_STYLE", "SECRET_IMAGE", "<html", "<img");
        assertThat(doc.getRawSha256()).isEqualTo(SourceHashing.sha256(doc.getOriginalContent())).isEqualTo(doc.getContentSha256());
        assertThat(jdbc.queryForObject("SELECT submitted_html_sha256 FROM source_text_registration WHERE source_document_id=?", String.class, documentId))
                .isEqualTo(SourceHashing.sha256(html.getBytes(StandardCharsets.UTF_8))).isNotEqualTo(doc.getRawSha256());
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.update("DELETE FROM source_text_registration WHERE source_document_id=?", documentId))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(jdbc.queryForObject("SELECT allowed_scope FROM source_document_usage_review WHERE source_document_id=?", String.class, documentId)).doesNotContain("SEARCH", "EXCERPT", "EXTERNAL_AI");
        UUID priorUsage = jdbc.queryForObject("SELECT id FROM source_document_usage_review WHERE source_document_id=?", UUID.class, documentId);
        var expandedUsage = new java.util.HashMap<String,Object>();
        expandedUsage.put("expectedRawSha256", doc.getRawSha256()); expandedUsage.put("documentVersion", 1);
        expandedUsage.put("basisUrl", url); expandedUsage.put("scopes", java.util.List.of("STORE", "EXTRACT", "REVIEW", "SEARCH", "EXCERPT"));
        expandedUsage.put("privacyReviewed", true); expandedUsage.put("thirdPartyReviewed", true); expandedUsage.put("decision", "ALLOWED");
        expandedUsage.put("reason", ""); expandedUsage.put("expectedPreviousReviewId", priorUsage);
        mvc.perform(post("/api/v1/source-review/document-claims/" + documentId + "/usage-review").cookie(cookie).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(expandedUsage))).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT reason FROM source_document_usage_review WHERE previous_review_id=?", String.class, priorUsage)).contains("검색", "원문 발췌");
        String version = "{\"expectedRawSha256\":\"" + doc.getRawSha256() + "\"}";
        mvc.perform(post("/api/v1/source-review/document-claims/" + documentId + "/prepare").cookie(cookie).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(version))
                .andExpect(status().isOk()).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.length()").value(2));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM source_document_claim_relation WHERE role='EXCEPTION' AND candidate_id IN (SELECT candidate_id FROM source_document_claim WHERE source_document_id=?)", Long.class, documentId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM source_applicability_review WHERE source_document_id=? AND review_status='APPROVED'", Long.class, documentId)).isZero();
        mvc.perform(post(path).cookie(cookie).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(request)))
                .andExpect(status().isOk()).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.outcome").value("UNCHANGED"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM source_document WHERE source_registry_id=?", Long.class, registryId)).isEqualTo(1);
        request.put("html", html.replace("2025년", "2026년"));
        mvc.perform(post(path).cookie(cookie).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(request)))
                .andExpect(status().isOk()).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.documentVersion").value(2));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM source_document WHERE source_registry_id=? AND status='SUPERSEDED'", Long.class, registryId)).isEqualTo(1);
        var zeroAndOne = java.util.List.of("안내문\n오늘은 맑습니다.\n목차\n추가 공지 사항입니다.", "금융 안내\n대출 신청은 공식 채널에서 확인해야 합니다.");
        for (int expected = 0; expected < zeroAndOne.size(); expected++) {
            UUID nextRegistry = UUID.randomUUID(); String nextUrl = "https://synthetic.invalid/" + nextRegistry;
            jdbc.update("INSERT INTO source_registry(id,source_key,organization_name,official_domain,canonical_url,acquisition_type,content_selector) VALUES (?,?,'Synthetic','synthetic.invalid',?,'HTML','.body')", nextRegistry, "text-" + nextRegistry, nextUrl);
            request.put("registryId", nextRegistry); request.put("basisUrl", nextUrl); request.put("html", "<div class='body'>" + zeroAndOne.get(expected) + "</div>");
            var nextJson = mvc.perform(post(path).cookie(cookie).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(request)))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
            var next = mapper.readTree(nextJson);
            String nextVersion = "{\"expectedRawSha256\":\"" + next.get("storedTextSha256").asString() + "\"}";
            mvc.perform(post("/api/v1/source-review/document-claims/" + next.get("documentId").asString() + "/prepare")
                    .cookie(cookie).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(nextVersion))
                    .andExpect(status().isOk()).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.length()").value(expected));
        }
    }

    @Test void structuredTablesAreStoredAsTextAndAmbiguousClaimsRemainHeld() throws Exception {
        UUID registryId = UUID.randomUUID(); String url = "https://synthetic.invalid/" + registryId;
        jdbc.update("INSERT INTO official_source_domain(domain,organization_name) VALUES ('synthetic.invalid','Synthetic') ON CONFLICT DO NOTHING");
        jdbc.update("INSERT INTO source_registry(id,source_key,organization_name,official_domain,canonical_url,acquisition_type,content_selector) VALUES (?,?,'Synthetic','synthetic.invalid',?,'HTML','.body')", registryId, "structured-" + registryId, url);
        var login = sessions.createSession(AccountProvider.NAVER, "structured-fixture-" + UUID.randomUUID(), "fixture");
        Cookie cookie = new Cookie(AccountSessionCookie.NAME, login.rawToken());
        jdbc.update("INSERT INTO source_reviewer_grant(account_id,granted_by) VALUES (?,'isolated-fixture')", login.account().getId());
        String html = "<html><head><title>합성 금융 안내</title></head><body><div class='body'><table><tr><td><p>Ⅰ. 일반 안내</p></td></tr></table><p>대출 신청은 공식 채널에서 확인해야 합니다.</p><p>이 문서는 자동화 검증을 위해 작성한 합성 예시입니다. 실제 제도나 상품을 설명하지 않으며 안내 내용은 테스트 목적으로만 사용합니다.</p><h2>표 안내</h2><table><caption>대상 및 예외</caption><tr><th>상품</th><th>조건</th></tr><tr><td>합성 대출</td><td>대출 신청은 신규 계약만 가능합니다.</td></tr></table><p>※ 기존 계약은 제외합니다.</p></div></body></html>";
        var mapper = tools.jackson.databind.json.JsonMapper.builder().build();
        org.mockito.Mockito.when(officialFetcher.fetch(org.mockito.ArgumentMatchers.any())).thenReturn(new SourceIngestionData.Fetched(url, "text/html;charset=UTF-8", null, null, html.getBytes(StandardCharsets.UTF_8), OffsetDateTime.now(ZoneOffset.UTC), 200));
        var request = java.util.Map.of("registryId", registryId, "basisUrl", url, "reason", "합성 권리 검토", "privacyReviewed", true, "thirdPartyReviewed", true, "storageApproved", true);
        String json = mvc.perform(post("/api/v1/source-review/document-claims/registered-source").cookie(cookie).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(request)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        var result = mapper.readTree(json); UUID documentId = UUID.fromString(result.get("documentId").asString());
        var doc = documents.findById(documentId).orElseThrow();
        assertThat(doc.getNormalizedContent()).contains("구조화 표", "표 제목: 대상 및 예외", "머리글: 조건", "셀: 대출 신청은 신규 계약만 가능합니다.", "기존 계약은 제외합니다.").doesNotContain("<table", "<td>");
        assertThat(jdbc.queryForObject("SELECT extraction_version FROM source_text_registration WHERE source_document_id=?", String.class, documentId)).isEqualTo("review-only-html-structured-v2");
        String path = "/api/v1/source-review/document-claims/" + documentId;
        mvc.perform(post(path + "/prepare").cookie(cookie).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(versionBody(doc))).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM source_document_claim WHERE source_document_id=? AND hold=true", Long.class, documentId)).isGreaterThan(0);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM source_document_claim WHERE source_document_id=? AND hold=false", Long.class, documentId)).isGreaterThan(0);
    }

    @Test void policyNewsRegistrationRemovesMediaAndContactsAndKeepsNotices() throws Exception {
        var login = sessions.createSession(AccountProvider.NAVER, "policy-fixture-" + UUID.randomUUID(), "fixture");
        Cookie cookie = new Cookie(AccountSessionCookie.NAME, login.rawToken());
        jdbc.update("INSERT INTO source_reviewer_grant(account_id,granted_by) VALUES (?,'isolated-fixture')", login.account().getId());
        String root = "/api/v1/source-review/document-claims";
        String url = "https://www.korea.kr/news/policyNewsView.do?newsId=100000001";
        var mapper = tools.jackson.databind.json.JsonMapper.builder().build();
        String body = mapper.writeValueAsString(java.util.Map.of("domain", "korea.kr", "url", url));
        String candidate = mvc.perform(post(root + "/registry-candidates").cookie(cookie).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        String registryId = mapper.readTree(candidate).get("registryId").asString();
        mvc.perform(post(root + "/registry-candidates").cookie(cookie).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(java.util.Map.of("domain", "korea.kr", "url", "https://www.korea.kr/other?newsId=100000001"))))
                .andExpect(status().isConflict());
        String html = "<html><head><meta property='og:title' content='합성 금융 뉴스'></head><body>"
                + "<div class='article_head'><div class='info'>2025.05.15 <a>금융위원회<span>부처별 뉴스 이동</span></a></div></div>"
                + "<div class='article_body'><div class='view_cont'><p>대출 신청은 공식 채널에서 확인해야 합니다.</p>"
                + "<p>이 내용은 자체 작성한 합성 문서이며 실제 금융 상품이나 제도에 관한 설명이 아닙니다. 추가 안내 역시 테스트를 위해 작성하였습니다.</p>"
                + "<blockquote>PRIVATE_QUOTE</blockquote><figure><img src='private-image'><figcaption>PRIVATE_CAPTION</figcaption></figure><p>문의: 02-1234-5678</p></div></div>"
                + "<div class='kogl'><div class='guide'><div class='type'>이 자료는 텍스트에 한하여 공공누리 제1유형(출처표시)의 조건에 따라 이용할 수 있습니다.</div></div></div></body></html>";
        org.mockito.Mockito.when(officialFetcher.fetch(org.mockito.ArgumentMatchers.any())).thenReturn(new SourceIngestionData.Fetched(url, "text/html;charset=UTF-8", null, null, html.getBytes(StandardCharsets.UTF_8), OffsetDateTime.now(ZoneOffset.UTC), 200));
        String preview = mvc.perform(post(root + "/registry-candidates/" + registryId + "/preview").cookie(cookie).with(csrf()))
                .andExpect(status().isOk()).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.publishedAt").value("2025-05-15"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.licenseType").value("PUBLIC_NURI_TYPE_1_TEXT"))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        String hash = mapper.readTree(preview).get("previewSha256").asString();
        var request = new java.util.HashMap<String, Object>(java.util.Map.of("registryId", registryId, "basisUrl", url, "reason", "합성 이용조건 확인", "privacyReviewed", true, "thirdPartyReviewed", true, "storageApproved", true, "requireTypeOne", true, "expectedPreviewSha256", "0".repeat(64)));
        mvc.perform(post(root + "/registered-source").cookie(cookie).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(request))).andExpect(status().isConflict());
        request.put("expectedPreviewSha256", hash);
        String response = mvc.perform(post(root + "/registered-source").cookie(cookie).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(request)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        UUID documentId = UUID.fromString(mapper.readTree(response).get("documentId").asString());
        String stored = jdbc.queryForObject("SELECT normalized_content FROM source_document WHERE id=?", String.class, documentId);
        assertThat(stored).contains("미디어 제외", "인용 제외", "연락처 제외").doesNotContain("PRIVATE_CAPTION", "PRIVATE_QUOTE", "private-image", "02-1234-5678", "<img");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM source_document_claim c JOIN source_chunk s ON s.id=c.source_chunk_id WHERE c.source_document_id=? AND (s.body LIKE '%미디어 제외%' OR s.body LIKE '%연락처 제외%' OR s.body LIKE '%인용 제외%')", Long.class, documentId)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM source_document_claim WHERE source_document_id=? AND hold=false", Long.class, documentId)).isGreaterThan(0);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM source_applicability_review WHERE source_document_id=? AND review_status='APPROVED'", Long.class, documentId)).isZero();
    }

    @Test void actualPendingDecisionAndAuditRequireRightsReviewerAndCsrf() throws Exception {
        UUID registryId = UUID.randomUUID();
        jdbc.update("INSERT INTO official_source_domain(domain,organization_name) VALUES ('synthetic.invalid','Synthetic') ON CONFLICT DO NOTHING");
        String url = "https://synthetic.invalid/" + registryId;
        jdbc.update("INSERT INTO source_registry(id,source_key,organization_name,official_domain,canonical_url,acquisition_type) VALUES (?,?,'Synthetic','synthetic.invalid',?,'HTML')", registryId, "fixture-" + registryId, url);
        var registry = registries.findById(registryId).orElseThrow();
        String text = "대출 안내\n신규 대출은 2025년 이후 적용됩니다. 다만 기존 계약은 제외합니다.\n대출 신청은 공식 채널에서 확인해야 합니다.";
        byte[] raw = text.getBytes(StandardCharsets.UTF_8);
        var parsed = new SourceIngestionData.Parsed(url, "합성 문서", null, OffsetDateTime.now(ZoneOffset.UTC),
                "text/html", null, null, SourceHashing.sha256(raw), SourceHashing.sha256(text), raw, text);
        var doc = documents.saveAndFlush(new SourceDocument(registry, 1, parsed));
        var login = sessions.createSession(AccountProvider.NAVER, "synthetic-" + UUID.randomUUID(), "fixture");
        Cookie cookie = new Cookie(AccountSessionCookie.NAME, login.rawToken());
        String path = "/api/v1/source-review/document-claims/" + doc.getId();
        String body = "{\"expectedRawSha256\":\"" + doc.getRawSha256() + "\"}";
        String usage = """
                {"expectedRawSha256":"%s","documentVersion":1,"basisUrl":"%s",
                 "scopes":["STORE","EXTRACT","REVIEW","SEARCH","EXCERPT"],"privacyReviewed":true,
                 "thirdPartyReviewed":true,"decision":"ALLOWED","reason":"synthetic fixture"}
                """.formatted(doc.getRawSha256(), url);
        mvc.perform(post(path + "/prepare").cookie(cookie).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden());
        mvc.perform(post(path + "/usage-review").cookie(cookie).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(usage)).andExpect(status().isForbidden());
        jdbc.update("INSERT INTO source_reviewer_grant(account_id,granted_by) VALUES (?,'isolated-fixture')", login.account().getId());
        mvc.perform(post(path + "/prepare").cookie(cookie).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden());
        mvc.perform(post(path + "/prepare").cookie(cookie).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isConflict());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM source_document_claim WHERE source_document_id=?", Long.class, doc.getId())).isZero();
        mvc.perform(post(path + "/usage-review").cookie(cookie).contentType(MediaType.APPLICATION_JSON).content(usage)).andExpect(status().isForbidden());
        mvc.perform(post(path + "/usage-review").cookie(cookie).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(usage)).andExpect(status().isOk());
        mvc.perform(post(path + "/usage-review").cookie(cookie).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(usage)).andExpect(status().isOk());
        mvc.perform(post(path + "/usage-review").cookie(cookie).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(usage.replace("ALLOWED", "REJECTED"))).andExpect(status().isConflict());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM source_document_usage_review WHERE source_document_id=?", Long.class, doc.getId())).isEqualTo(1);
        mvc.perform(get(path + "/usage-review").cookie(cookie).param("expectedRawSha256", doc.getRawSha256())).andExpect(status().isOk());
        mvc.perform(post(path + "/prepare").cookie(cookie).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk());
        mvc.perform(get(path).cookie(cookie).param("expectedRawSha256", doc.getRawSha256()))
                .andExpect(status().isOk()).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$[0].relations").isArray())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$[0].currentApplicability").value("NOT_VERIFIED"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM source_document_claim WHERE source_document_id=?", Long.class, doc.getId())).isEqualTo(2);
        String claimId = jdbc.queryForObject("SELECT candidate_id FROM source_document_claim WHERE source_document_id=? ORDER BY context_start LIMIT 1", String.class, doc.getId());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM source_document_claim_relation WHERE candidate_id=? AND role='EXCEPTION'", Long.class, claimId)).isEqualTo(1);
        String approve = "{\"expectedRawSha256\":\"" + doc.getRawSha256() + "\",\"decision\":\"APPROVE\"}";
        UUID reviewId = jdbc.queryForObject("SELECT review_id FROM source_document_claim WHERE candidate_id=?", UUID.class, claimId);
        UUID chunkId = jdbc.queryForObject("SELECT source_chunk_id FROM source_document_claim WHERE candidate_id=?", UUID.class, claimId);
        jdbc.update("DELETE FROM source_applicability_chunk WHERE review_id=? AND evidence_role='CONDITION'", reviewId);
        mvc.perform(post(path + "/" + claimId + "/decision").cookie(cookie).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(approve)).andExpect(status().isConflict());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM source_review_decision_audit WHERE source_document_id=?", Long.class, doc.getId())).isZero();
        jdbc.update("INSERT INTO source_applicability_chunk(review_id,source_document_id,source_chunk_id,evidence_role) VALUES (?,?,?,'CONDITION')", reviewId, doc.getId(), chunkId);
        jdbc.update("DELETE FROM source_applicability_chunk WHERE review_id=? AND evidence_role='EXCEPTION'", reviewId);
        mvc.perform(post(path + "/" + claimId + "/decision").cookie(cookie).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(approve)).andExpect(status().isConflict());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM source_review_decision_audit WHERE source_document_id=?", Long.class, doc.getId())).isZero();
        jdbc.update("INSERT INTO source_applicability_chunk(review_id,source_document_id,source_chunk_id,evidence_role) VALUES (?,?,?,'EXCEPTION')", reviewId, doc.getId(), chunkId);
        jdbc.execute("CREATE FUNCTION fixture_reject_audit() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'synthetic failure'; END $$");
        jdbc.execute("CREATE TRIGGER fixture_reject_audit BEFORE INSERT ON source_review_decision_audit FOR EACH ROW EXECUTE FUNCTION fixture_reject_audit()");
        try {
            mvc.perform(post(path + "/" + claimId + "/decision").cookie(cookie).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(approve)).andExpect(status().isInternalServerError());
            assertThat(jdbc.queryForObject("SELECT review_status FROM source_applicability_review WHERE id=?", String.class, reviewId)).isEqualTo("PENDING");
            assertThat(jdbc.queryForObject("SELECT review_status FROM source_chunk WHERE id=?", String.class, chunkId)).isEqualTo("PENDING");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM source_review_decision_audit WHERE source_document_id=?", Long.class, doc.getId())).isZero();
        } finally {
            jdbc.execute("DROP TRIGGER fixture_reject_audit ON source_review_decision_audit");
            jdbc.execute("DROP FUNCTION fixture_reject_audit()");
        }
        mvc.perform(post(path + "/" + claimId + "/decision").cookie(cookie).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(approve)).andExpect(status().isNoContent());
        mvc.perform(post(path + "/" + claimId + "/decision").cookie(cookie).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(approve)).andExpect(status().isNoContent());
        mvc.perform(post(path + "/" + claimId + "/decision").cookie(cookie).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(approve.replace("APPROVE", "REJECT"))).andExpect(status().isConflict());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM source_review_decision_audit WHERE source_document_id=?", Long.class, doc.getId())).isEqualTo(1);
        assertThat(reviewer.evaluateEligibility(doc.getId(), claimId, doc.getRawSha256(), "SEARCH").eligible()).isTrue();
        // Legacy and generic evidence share the build input, without activating a generation.
        var legacy = chunkPersistence.saveIfAbsent(doc, new SourceChunkData.Definition(
                "legacy-fixture-v1", "{}", 0, "대출 안내", null, null, null,
                "fixture:legacy", 0, 5, "{}"));
        jdbc.update("UPDATE source_chunk SET review_status='APPROVED', reviewed_at=CURRENT_TIMESTAMP WHERE id=?", legacy.getId());
        var definition = new RetrievalGenerationData.Definition("mixed-fixture-" + UUID.randomUUID(),
                "fixture", "fixture-model", "fixture-revision", "fixture-tokenizer", "fixture-revision",
                "{}", "{}", "{\"includeDocumentClaims\":true,\"documentClaimDocumentIds\":[\"" + doc.getId() + "\"],\"chunkIds\":[\"" + legacy.getId() + "\"]}",
                "legacy-fixture-v1", null);
        var generation = generations.saveAndFlush(new RetrievalGeneration(definition, OffsetDateTime.now(ZoneOffset.UTC)));
        var plan = indexing.start(generation.getId());
        assertThat(plan.expectedDocumentIds()).contains(legacy.getId(), chunkId);
        assertThat(reviewer.generationReviewMatches(chunkId, generation.getId())).isTrue();
        assertThat(plan.request().documents()).hasSize(plan.expectedDocumentIds().size());
        assertThat(plan.request().documents()).filteredOn(d -> d.id().equals(chunkId))
                .singleElement().satisfies(d -> assertThat(d.text()).contains("다만 기존 계약은 제외합니다"));
        assertThat(plan.expectedDocumentIds()).doesNotContain(jdbc.queryForObject(
                "SELECT source_chunk_id FROM source_document_claim WHERE source_document_id=? AND candidate_id<>?",
                UUID.class, doc.getId(), claimId));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM source_document_claim WHERE candidate_id=? AND source_chunk_id=? AND source_document_id=? AND document_version=? AND raw_sha256=? AND content_sha256=?",
                Long.class, claimId, chunkId, doc.getId(), doc.getDocumentVersion(), doc.getRawSha256(), doc.getContentSha256())).isEqualTo(1);
        assertThat(reviewer.evaluateEligibility(doc.getId(), claimId, doc.getRawSha256(), "EXTERNAL_AI").eligible()).isFalse();
        UUID firstUsage = jdbc.queryForObject("SELECT id FROM source_document_usage_review WHERE source_document_id=? ORDER BY decided_at DESC LIMIT 1", UUID.class, doc.getId());
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.update("UPDATE source_document_usage_review SET reason='tampered' WHERE id=?", firstUsage))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        String withdraw = usageTransition(doc, firstUsage, "HOLD", "withdraw for recheck", false);
        mvc.perform(post(path + "/usage-review").cookie(cookie).contentType(MediaType.APPLICATION_JSON).content(withdraw)).andExpect(status().isForbidden());
        mvc.perform(post(path + "/usage-review").cookie(cookie).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(withdraw)).andExpect(status().isOk());
        mvc.perform(post(path + "/usage-review").cookie(cookie).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(withdraw)).andExpect(status().isOk());
        assertThat(reviewer.evaluateEligibility(doc.getId(), claimId, doc.getRawSha256(), "SEARCH").eligible()).isFalse();
        var withdrawnDefinition = new RetrievalGenerationData.Definition("withdrawn-fixture-" + UUID.randomUUID(),
                definition.representationConfigVersion(), definition.modelIdentifier(), definition.modelRevision(),
                definition.tokenizerIdentifier(), definition.tokenizerRevision(), definition.encodingConfigJson(),
                definition.indexConfigJson(), definition.metadataJson(), definition.chunkConfigVersion(), null);
        var withdrawnGeneration = generations.saveAndFlush(new RetrievalGeneration(withdrawnDefinition, OffsetDateTime.now(ZoneOffset.UTC)));
        assertThat(indexing.start(withdrawnGeneration.getId()).expectedDocumentIds())
                .contains(legacy.getId()).doesNotContain(chunkId);
        UUID holdUsage = jdbc.queryForObject("SELECT id FROM source_document_usage_review WHERE previous_review_id=?", UUID.class, firstUsage);
        String reinstate = usageTransition(doc, holdUsage, "ALLOWED", "rechecked", true);
        jdbc.execute("CREATE FUNCTION fixture_reject_usage() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'synthetic failure'; END $$");
        jdbc.execute("CREATE TRIGGER fixture_reject_usage BEFORE INSERT ON source_document_usage_review FOR EACH ROW EXECUTE FUNCTION fixture_reject_usage()");
        try {
            mvc.perform(post(path + "/usage-review").cookie(cookie).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(reinstate)).andExpect(status().isInternalServerError());
            assertThat(jdbc.queryForObject("SELECT count(*) FROM source_document_usage_review WHERE source_document_id=?", Long.class, doc.getId())).isEqualTo(2);
            assertThat(reviewer.evaluateEligibility(doc.getId(), claimId, doc.getRawSha256(), "SEARCH").eligible()).isFalse();
        } finally {
            jdbc.execute("DROP TRIGGER fixture_reject_usage ON source_document_usage_review");
            jdbc.execute("DROP FUNCTION fixture_reject_usage()");
        }
        mvc.perform(post(path + "/usage-review").cookie(cookie).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(reinstate)).andExpect(status().isOk());
        assertThat(reviewer.evaluateEligibility(doc.getId(), claimId, doc.getRawSha256(), "SEARCH").eligible()).isTrue();
        jdbc.update("DELETE FROM source_applicability_chunk WHERE review_id=? AND evidence_role='EXCEPTION'", reviewId);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> reviewer.evaluateEligibility(doc.getId(), claimId, doc.getRawSha256(), "SEARCH"))
                .isInstanceOf(SourceReviewException.class);
        jdbc.update("INSERT INTO source_applicability_chunk(review_id,source_document_id,source_chunk_id,evidence_role) VALUES (?,?,?,'EXCEPTION')", reviewId, doc.getId(), chunkId);
        UUID renewed = jdbc.queryForObject("SELECT id FROM source_document_usage_review WHERE previous_review_id=?", UUID.class, holdUsage);
        String rejectRights = usageTransition(doc, renewed, "REJECTED", "rights unavailable", false);
        mvc.perform(post(path + "/usage-review").cookie(cookie).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(rejectRights)).andExpect(status().isOk());
        assertThat(reviewer.evaluateEligibility(doc.getId(), claimId, doc.getRawSha256(), "SEARCH").eligible()).isFalse();
        UUID rejectedRights = jdbc.queryForObject("SELECT id FROM source_document_usage_review WHERE previous_review_id=?", UUID.class, renewed);
        String reopen = usageTransition(doc, rejectedRights, "HOLD", "new evidence", false);
        mvc.perform(post(path + "/usage-review").cookie(cookie).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(reopen)).andExpect(status().isOk());
        mvc.perform(post(path + "/usage-review").cookie(cookie).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(reinstate)).andExpect(status().isConflict());
        UUID reopened = jdbc.queryForObject("SELECT id FROM source_document_usage_review WHERE previous_review_id=?", UUID.class, rejectedRights);
        mvc.perform(post(path + "/usage-review").cookie(cookie).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(usageTransition(doc, reopened, "ALLOWED", "rechecked again", true))).andExpect(status().isOk());
        // Transaction timestamps can precede the decision that a waiting transaction follows.
        jdbc.execute("ALTER TABLE source_document_usage_review DISABLE TRIGGER source_usage_review_immutable");
        try {
            jdbc.update("UPDATE source_document_usage_review SET decided_at=decided_at-interval '1 day' WHERE previous_review_id=?", reopened);
        } finally { jdbc.execute("ALTER TABLE source_document_usage_review ENABLE TRIGGER source_usage_review_immutable"); }
        assertThat(reviewer.evaluateEligibility(doc.getId(), claimId, doc.getRawSha256(), "SEARCH").eligible()).isTrue();
        String second = jdbc.queryForObject("SELECT candidate_id FROM source_document_claim WHERE source_document_id=? AND candidate_id<>?", String.class, doc.getId(), claimId);
        mvc.perform(post(path + "/" + second + "/decision").cookie(cookie).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(approve.replace("APPROVE", "REJECT"))).andExpect(status().isNoContent());
        UUID rejectedChunk = jdbc.queryForObject("SELECT source_chunk_id FROM source_document_claim WHERE candidate_id=?", UUID.class, second);
        var rejectedDefinition = new RetrievalGenerationData.Definition("rejected-fixture-" + UUID.randomUUID(),
                definition.representationConfigVersion(), definition.modelIdentifier(), definition.modelRevision(),
                definition.tokenizerIdentifier(), definition.tokenizerRevision(), definition.encodingConfigJson(),
                definition.indexConfigJson(), definition.metadataJson(), definition.chunkConfigVersion(), null);
        var rejectedGeneration = generations.saveAndFlush(new RetrievalGeneration(rejectedDefinition, OffsetDateTime.now(ZoneOffset.UTC)));
        assertThat(indexing.start(rejectedGeneration.getId()).expectedDocumentIds())
                .contains(legacy.getId(), chunkId).doesNotContain(rejectedChunk);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM source_review_decision_audit WHERE source_document_id=? AND decision='REJECTED'", Long.class, doc.getId())).isEqualTo(1);
        String versionBody = "{\"expectedRawSha256\":\"" + doc.getRawSha256() + "\"}";
        mvc.perform(post(path + "/" + second + "/reconsider").cookie(cookie).contentType(MediaType.APPLICATION_JSON).content(versionBody))
                .andExpect(status().isForbidden());
        mvc.perform(post(path + "/" + second + "/reconsider").cookie(cookie).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(versionBody))
                .andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject("SELECT review_status FROM source_chunk WHERE id=?", String.class, rejectedChunk)).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM source_review_decision_audit a JOIN source_document_claim c ON c.review_id=a.review_id WHERE c.candidate_id=? AND a.decision='REVOKED' AND a.decision_reason='REJECTION_RECONSIDERED'", Long.class, second)).isEqualTo(1);
        mvc.perform(post(path + "/" + second + "/reconsider").cookie(cookie).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(versionBody))
                .andExpect(status().isConflict());
        mvc.perform(post(path + "/" + second + "/decision").cookie(cookie).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(approve.replace(doc.getRawSha256(), "0".repeat(64)))).andExpect(status().isConflict());
        String withdrawal = "{\"expectedRawSha256\":\"" + doc.getRawSha256() + "\",\"reason\":\"synthetic withdrawal\"}";
        mvc.perform(post(path + "/" + claimId + "/withdraw").cookie(cookie).contentType(MediaType.APPLICATION_JSON).content(withdrawal))
                .andExpect(status().isForbidden());
        mvc.perform(post(path + "/" + claimId + "/withdraw").cookie(cookie).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(withdrawal))
                .andExpect(status().isNoContent());
        assertThat(reviewer.evaluateEligibility(doc.getId(), claimId, doc.getRawSha256(), "SEARCH").eligible()).isFalse();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM source_review_decision_audit WHERE source_document_id=? AND decision='REVOKED' AND decision_reason='APPROVAL_WITHDRAWN: synthetic withdrawal'", Long.class, doc.getId())).isEqualTo(1);
        mvc.perform(post(path + "/" + claimId + "/decision").cookie(cookie).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(approve)).andExpect(status().isNoContent());
        assertThat(reviewer.generationReviewMatches(chunkId, generation.getId())).isFalse();
        assertThat(reviewer.genericChunkMayBeDisplayed(chunkId)).isTrue();
        assertThat(reviewer.genericChunkMayBeDisplayedForGeneration(chunkId, generation.getId())).isFalse();
        var afterDefinition = new RetrievalGenerationData.Definition("reapproved-fixture-" + UUID.randomUUID(),
                definition.representationConfigVersion(), definition.modelIdentifier(), definition.modelRevision(),
                definition.tokenizerIdentifier(), definition.tokenizerRevision(), definition.encodingConfigJson(),
                definition.indexConfigJson(), definition.metadataJson(), definition.chunkConfigVersion(), null);
        var afterReapproval = generations.saveAndFlush(new RetrievalGeneration(afterDefinition, OffsetDateTime.now(ZoneOffset.UTC)));
        assertThat(reviewer.genericChunkMayBeDisplayedForGeneration(chunkId, afterReapproval.getId())).isFalse();
        indexing.start(afterReapproval.getId());
        assertThat(reviewer.genericChunkMayBeDisplayedForGeneration(chunkId, afterReapproval.getId())).isTrue();
        var zero = fixtureDocument("안내문\n오늘은 맑습니다.");
        allow(zero, cookie);
        String zeroPath = "/api/v1/source-review/document-claims/" + zero.getId();
        mvc.perform(post(zeroPath + "/prepare").cookie(cookie).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(versionBody(zero))).andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.length()").value(0));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM source_document_claim WHERE source_document_id=?", Long.class, zero.getId())).isZero();
        var rightsHold = fixtureDocument("보험 안내\n보험 청구는 공식 채널에서 신청할 수 있습니다.");
        String rightsPath = "/api/v1/source-review/document-claims/" + rightsHold.getId();
        String pendingRights = """
                {"expectedRawSha256":"%s","documentVersion":1,"basisUrl":"%s",
                 "scopes":[],"privacyReviewed":false,"thirdPartyReviewed":false,
                 "decision":"HOLD","reason":"synthetic fixture"}
                """.formatted(rightsHold.getRawSha256(), rightsHold.getCanonicalUrl());
        mvc.perform(post(rightsPath + "/usage-review").cookie(cookie).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(pendingRights)).andExpect(status().isOk());
        mvc.perform(post(rightsPath + "/prepare").cookie(cookie).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(versionBody(rightsHold))).andExpect(status().isConflict());
        UUID rightsHoldId = jdbc.queryForObject("SELECT id FROM source_document_usage_review WHERE source_document_id=?", UUID.class, rightsHold.getId());
        mvc.perform(post(rightsPath + "/usage-review").cookie(cookie).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(usageTransition(rightsHold, rightsHoldId, "REJECTED", "synthetic fixture", false))).andExpect(status().isOk());
        mvc.perform(post(rightsPath + "/prepare").cookie(cookie).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(versionBody(rightsHold))).andExpect(status().isConflict());
        var held = fixtureDocument("금융 안내\n금융 이용 절차 개선을 추진할 예정입니다.");
        allow(held, cookie);
        String heldPath = "/api/v1/source-review/document-claims/" + held.getId();
        mvc.perform(post(heldPath + "/prepare").cookie(cookie).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(versionBody(held))).andExpect(status().isOk());
        String heldId = jdbc.queryForObject("SELECT candidate_id FROM source_document_claim WHERE source_document_id=?", String.class, held.getId());
        assertThat(jdbc.queryForObject("SELECT hold FROM source_document_claim WHERE candidate_id=?", Boolean.class, heldId)).isFalse();
        assertThat(jdbc.queryForObject("SELECT hold_reason FROM source_document_claim WHERE candidate_id=?", String.class, heldId)).contains("시행 여부");
        String heldApprove = "{\"expectedRawSha256\":\"" + held.getRawSha256() + "\",\"decision\":\"APPROVE\"}";
        mvc.perform(post(heldPath + "/" + heldId + "/decision").cookie(cookie).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(heldApprove)).andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM source_review_decision_audit WHERE source_document_id=?", Long.class, held.getId())).isEqualTo(1);
        var tableDependent = fixtureDocument("대출 안내\n대출은 아래 표에 명시된 조건에 따라 적용됩니다.\n| 대상 | 조건 |\n| 신규 | 기존 계약 제외 |");
        allow(tableDependent, cookie);
        String tablePath = "/api/v1/source-review/document-claims/" + tableDependent.getId();
        mvc.perform(post(tablePath + "/prepare").cookie(cookie).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(versionBody(tableDependent))).andExpect(status().isOk());
        String tableClaim = jdbc.queryForObject("SELECT candidate_id FROM source_document_claim WHERE source_document_id=? ORDER BY context_start LIMIT 1", String.class, tableDependent.getId());
        assertThat(jdbc.queryForObject("SELECT hold FROM source_document_claim WHERE candidate_id=?", Boolean.class, tableClaim)).isTrue();
        mvc.perform(post(tablePath + "/" + tableClaim + "/decision").cookie(cookie).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedRawSha256\":\"" + tableDependent.getRawSha256() + "\",\"decision\":\"APPROVE\"}"))
                .andExpect(status().isConflict());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM source_review_decision_audit WHERE source_document_id=?", Long.class, tableDependent.getId())).isZero();
        var rightsRace = fixtureDocument("금융 안내\n예금 신청은 공식 채널에서 확인해야 합니다.");
        allow(rightsRace, cookie);
        String racePath = "/api/v1/source-review/document-claims/" + rightsRace.getId();
        mvc.perform(post(racePath + "/prepare").cookie(cookie).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(versionBody(rightsRace))).andExpect(status().isOk());
        String raceClaim = jdbc.queryForObject("SELECT candidate_id FROM source_document_claim WHERE source_document_id=?", String.class, rightsRace.getId());
        UUID raceUsage = jdbc.queryForObject("SELECT id FROM source_document_usage_review WHERE source_document_id=?", UUID.class, rightsRace.getId());
        String raceApprove = "{\"expectedRawSha256\":\"" + rightsRace.getRawSha256() + "\",\"decision\":\"APPROVE\"}";
        var rightsExecutor = java.util.concurrent.Executors.newFixedThreadPool(2);
        var rightsStart = new java.util.concurrent.CountDownLatch(1);
        try {
            var withdrawRace = rightsExecutor.submit(() -> { rightsStart.await(); return mvc.perform(post(racePath + "/usage-review").cookie(cookie).with(csrf())
                    .contentType(MediaType.APPLICATION_JSON).content(usageTransition(rightsRace, raceUsage, "HOLD", "race withdrawal", false))).andReturn().getResponse().getStatus(); });
            var approveRace = rightsExecutor.submit(() -> { rightsStart.await(); return mvc.perform(post(racePath + "/" + raceClaim + "/decision").cookie(cookie).with(csrf())
                    .contentType(MediaType.APPLICATION_JSON).content(raceApprove)).andReturn().getResponse().getStatus(); });
            rightsStart.countDown();
            assertThat(withdrawRace.get(30, java.util.concurrent.TimeUnit.SECONDS)).isEqualTo(200);
            assertThat(approveRace.get(30, java.util.concurrent.TimeUnit.SECONDS)).isIn(204, 409);
        } finally { rightsExecutor.shutdownNow(); }
        assertThat(reviewer.evaluateEligibility(rightsRace.getId(), raceClaim, rightsRace.getRawSha256(), "SEARCH").eligible()).isFalse();
        var single = fixtureDocument("금융 안내\n대출 신청은 공식 채널에서 확인해야 합니다.");
        allow(single, cookie);
        String singlePath = "/api/v1/source-review/document-claims/" + single.getId();
        mvc.perform(post(singlePath + "/prepare").cookie(cookie).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(versionBody(single))).andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.length()").value(1));
        String singleId = jdbc.queryForObject("SELECT candidate_id FROM source_document_claim WHERE source_document_id=?", String.class, single.getId());
        String raceBody = "{\"expectedRawSha256\":\"" + single.getRawSha256() + "\",\"decision\":\"%s\"}";
        var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        var start = new java.util.concurrent.CountDownLatch(1);
        try {
            var yes = executor.submit(() -> { start.await(); return mvc.perform(post(singlePath + "/" + singleId + "/decision").cookie(cookie).with(csrf())
                    .contentType(MediaType.APPLICATION_JSON).content(raceBody.formatted("APPROVE"))).andReturn().getResponse().getStatus(); });
            var no = executor.submit(() -> { start.await(); return mvc.perform(post(singlePath + "/" + singleId + "/decision").cookie(cookie).with(csrf())
                    .contentType(MediaType.APPLICATION_JSON).content(raceBody.formatted("REJECT"))).andReturn().getResponse().getStatus(); });
            start.countDown();
            assertThat(java.util.List.of(yes.get(30, java.util.concurrent.TimeUnit.SECONDS), no.get(30, java.util.concurrent.TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(204, 409);
        } finally { executor.shutdownNow(); }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM source_review_decision_audit WHERE source_document_id=?", Long.class, single.getId())).isEqualTo(1);
        jdbc.update("UPDATE source_document SET status='SUPERSEDED',superseded_at=CURRENT_TIMESTAMP WHERE id=?", single.getId());
        mvc.perform(get(singlePath + "/usage-review").cookie(cookie).param("expectedRawSha256", single.getRawSha256()))
                .andExpect(status().isConflict());
        mvc.perform(post(singlePath + "/prepare").cookie(cookie).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(versionBody(single))).andExpect(status().isConflict());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM source_review_decision_audit WHERE source_document_id=?", Long.class, single.getId())).isEqualTo(1);
        String revisedText = "금융 안내\n대출 신청은 다른 공식 채널에서 확인해야 합니다.";
        byte[] revisedBytes = revisedText.getBytes(StandardCharsets.UTF_8);
        UUID sourceId = jdbc.queryForObject("SELECT source_registry_id FROM source_document WHERE id=?", UUID.class, single.getId());
        var revised = documents.saveAndFlush(new SourceDocument(registries.findById(sourceId).orElseThrow(), 2,
                new SourceIngestionData.Parsed(single.getCanonicalUrl(), "합성 새 판본", null, OffsetDateTime.now(ZoneOffset.UTC),
                        "text/html", null, null, SourceHashing.sha256(revisedBytes), SourceHashing.sha256(revisedText), revisedBytes, revisedText)));
        String revisedPath = "/api/v1/source-review/document-claims/" + revised.getId();
        mvc.perform(post(revisedPath + "/prepare").cookie(cookie).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(versionBody(revised))).andExpect(status().isConflict());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM source_document_claim WHERE source_document_id=?", Long.class, revised.getId())).isZero();
    }

    private SourceDocument fixtureDocument(String text) {
        UUID id = UUID.randomUUID();
        String url = "https://synthetic.invalid/" + id;
        jdbc.update("INSERT INTO source_registry(id,source_key,organization_name,official_domain,canonical_url,acquisition_type) VALUES (?,?,'Synthetic','synthetic.invalid',?,'HTML')", id, "fixture-" + id, url);
        var registry = registries.findById(id).orElseThrow();
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        return documents.saveAndFlush(new SourceDocument(registry, 1,
                new SourceIngestionData.Parsed(url, "합성 문서", null, OffsetDateTime.now(ZoneOffset.UTC),
                        "text/html", null, null, SourceHashing.sha256(bytes), SourceHashing.sha256(text), bytes, text)));
    }

    private String versionBody(SourceDocument doc) {
        return "{\"expectedRawSha256\":\"" + doc.getRawSha256() + "\"}";
    }

    private void allow(SourceDocument doc, Cookie cookie) throws Exception {
        String usage = """
                {"expectedRawSha256":"%s","documentVersion":1,"basisUrl":"%s",
                 "scopes":["STORE","EXTRACT","REVIEW"],"privacyReviewed":true,
                 "thirdPartyReviewed":true,"decision":"ALLOWED","reason":"synthetic fixture"}
                """.formatted(doc.getRawSha256(), doc.getCanonicalUrl());
        mvc.perform(post("/api/v1/source-review/document-claims/" + doc.getId() + "/usage-review")
                .cookie(cookie).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(usage)).andExpect(status().isOk());
    }

    private String usageTransition(SourceDocument doc, UUID previous, String decision, String reason, boolean allowed) {
        String scopes = allowed ? "[\"STORE\",\"EXTRACT\",\"REVIEW\",\"SEARCH\",\"EXCERPT\"]" : "[]";
        return """
                {"expectedRawSha256":"%s","documentVersion":1,"basisUrl":"%s",
                 "scopes":%s,"privacyReviewed":%s,"thirdPartyReviewed":%s,"decision":"%s",
                 "reason":"%s","expectedPreviousReviewId":"%s"}
                """.formatted(doc.getRawSha256(), doc.getCanonicalUrl(), scopes, allowed, allowed, decision, reason, previous);
    }
}
