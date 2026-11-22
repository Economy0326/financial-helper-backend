package com.financialhelper.source;

import com.financialhelper.account.AccountProvider;
import com.financialhelper.account.AccountSessionCookie;
import com.financialhelper.account.AccountSessionService;
import com.financialhelper.consultation.FinancialTopic;
import com.financialhelper.retrieval.RetrievalCorpusSnapshotService;
import com.financialhelper.retrieval.ReviewedSourceApplicabilityService;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.time.LocalDate;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Explicit public-source preflight in a disposable database. Review decisions are synthetic fixtures only. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "SIX_TOPIC_OFFICIAL_PREFLIGHT", matches = "true")
class SixTopicOfficialSourcePreflightIntegrationTest {
    private static final List<String> KEYS = List.of(
            "fsc-card-use-qa", "police-campaign", "fsc-early-repayment-qa",
            "fsc-silsong24-guide", "law-financial-consumer-protection-19",
            "fsc-consumer-complaints");
    private static final Map<String, String> CLAIM_ANCHORS = Map.of(
            "fsc-card-use-qa", "분실",
            "police-campaign", "112",
            "fsc-early-repayment-qa", "중도상환수수료",
            "fsc-silsong24-guide", "실손24",
            "law-financial-consumer-protection-19", "설명",
            "fsc-consumer-complaints", "분쟁조정");

    @Autowired SourceIngestionService ingestion;
    @Autowired SourceRegistryRepository registries;
    @Autowired SourceDocumentRepository documents;
    @Autowired SourceChunkingService chunking;
    @Autowired SourceChunkRepository chunks;
    @Autowired AccountSessionService sessions;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired ReviewedSourceApplicabilityService applicability;

    @Test
    void publicDocumentsProduceNarrowCandidatesAndIsolatedHumanDecisionFixture() throws Exception {
        var login = sessions.createSession(AccountProvider.NAVER,
                "isolated-information-review-" + UUID.randomUUID(), "fixture");
        Cookie cookie = new Cookie(AccountSessionCookie.NAME, login.rawToken());
        jdbc.update("INSERT INTO source_reviewer_grant(account_id, granted_by) VALUES (?, 'isolated-fixture')",
                login.account().getId());
        SourceDocument insurance = null;
        for (String key : KEYS) {
            SourceRegistry registry = registries.findBySourceKeyAndEnabledTrue(key).orElseThrow();
            SourceIngestionResult result;
            try {
                result = ingestion.ingestOne(key);
            } catch (SourceIngestionException unavailable) {
                if (!"SOURCE_TEMPORARILY_UNAVAILABLE".equals(unavailable.getCode())) throw unavailable;
                Assumptions.abort("Official law URL returned a maintenance page; live source was not verified");
                throw unavailable;
            }
            assertThat(result.outcome()).as(key).isNotEqualTo(SourceIngestionResult.Outcome.FAILED);
            SourceDocument document = documents.findBySourceRegistry_IdAndStatus(
                    registry.getId(), SourceDocumentStatus.ACTIVE).orElseThrow();
            assertThat(document.getRawSha256()).as(key).matches("[a-f0-9]{64}");
            assertThat(document.getCanonicalUrl()).as(key).isEqualTo(registry.getCanonicalUrl());
            assertThat(document.getTitle()).as(key).isNotBlank();
            String anchor = CLAIM_ANCHORS.get(key);
            int anchorAt = document.getNormalizedContent().indexOf(anchor);
            assertThat(anchorAt).as("claim anchor in " + key).isGreaterThanOrEqualTo(0);
            var savedChunks = chunking.chunk(document);
            assertThat(savedChunks).as(key).isNotEmpty();
            assertThat(savedChunks).as(key).hasSize("fsc-silsong24-guide".equals(key) ? 2 : 1);
            var claim = savedChunks.getFirst();
            assertThat(claim.getReviewStatus()).isEqualTo(SourceChunkReviewStatus.PENDING);
            assertThat(claim.getBody()).isEqualTo(document.getNormalizedContent().substring(
                    claim.getSourceStartOffset(), claim.getSourceEndOffset()));
            assertThat(chunking.chunk(document).getFirst().getId()).isEqualTo(claim.getId());
            switch (key) {
                case "fsc-card-use-qa" -> assertThat(claim.getBody())
                        .contains("신고해야 합니다").doesNotContain("60일", "주민등록번호", "보상");
                case "police-campaign" -> assertThat(claim.getBody())
                        .contains("112", "피해").doesNotContain("10분", "지급정지", "환급");
                case "fsc-early-repayment-qa" -> assertThat(claim.getBody())
                        .contains("’25년 1월 13일", "이전 계약", "적용되지 않습니다");
                case "fsc-silsong24-guide" -> {
                    assertThat(claim.getBody()).contains("소비자가 앱으로 요청", "청구서류")
                            .doesNotContain("수천억원");
                    assertThat(savedChunks.get(1).getBody()).contains("연계 완료", "이용할 수 있는 요양기관")
                            .doesNotContain("47.7%");
                    assertThat(OfficialInformationClaimSelector.role(key, 1)).isEqualTo("CONDITION");
                }
                case "law-financial-consumer-protection-19" -> assertThat(claim.getBody())
                        .contains("제19조", "투자에 따른 위험", "수수료");
                case "fsc-consumer-complaints" -> assertThat(claim.getBody())
                        .contains("분쟁조정", "금융민원 신청하기").doesNotContain("미래창조과학부");
                default -> throw new IllegalStateException("Unexpected source");
            }
            System.out.printf("SOURCE_PREFLIGHT key=%s version=%d bytes=%d hash=%s chunks=%d published=%s title=%s%n",
                    key, document.getDocumentVersion(), document.getOriginalContent().length,
                    document.getRawSha256(), savedChunks.size(), document.getPublishedAt(), document.getTitle());
            assertThat(chunks.findAllBySourceDocument_IdOrderBySequenceAsc(document.getId()))
                    .as(key).allSatisfy(chunk -> {
                        assertThat(chunk.getReviewStatus()).isEqualTo(SourceChunkReviewStatus.PENDING);
                        assertThat(chunk.getLocator()).startsWith("normalized-content:utf16:");
                    });
            mvc.perform(post("/api/v1/source-review/information/" + key + "/extract")
                    .cookie(cookie).with(csrf()).param("documentId", document.getId().toString()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[0].temporalAssistance.recommendedPolicy").exists())
                    .andExpect(jsonPath("$[0].temporalAssistance.currentnessNotice").exists());
            if ("fsc-silsong24-guide".equals(key)) insurance = document;
        }
        assertThat(insurance).isNotNull();
        SourceDocument card = documents.findBySourceRegistry_IdAndStatus(
                registries.findBySourceKeyAndEnabledTrue("fsc-card-use-qa").orElseThrow().getId(),
                SourceDocumentStatus.ACTIVE).orElseThrow();
        SourceChunk cardClaim = chunks.findAllBySourceDocument_IdOrderBySequenceAsc(card.getId()).getFirst();
        SourceDocument inventedClaimDocument = new SourceDocument(card.getSourceRegistry(), 2,
                new SourceIngestionData.Parsed(card.getCanonicalUrl(), card.getTitle(),
                        card.getPublishedAt(), card.getRetrievedAt(), "text/html", null, null,
                        card.getRawSha256(), card.getContentSha256(), card.getOriginalContent(),
                        "원문에 없는 합성 주장"));
        assertThatThrownBy(() -> OfficialInformationClaimSelector.select(inventedClaimDocument,
                cardClaim.getChunkConfigVersion(), cardClaim.getChunkConfiguration().getConfigJson()))
                .isInstanceOf(IllegalStateException.class);
        assertThat(TemporalEvidenceAssistant.analyze(card.getNormalizedContent(),
                cardClaim.getSourceStartOffset(), cardClaim.getSourceEndOffset(), card.getPublishedAt())
                .recommendedPolicy()).isEqualTo(TemporalEvidencePolicy.HOLD);
        String cardBase = "/api/v1/source-review/information/fsc-card-use-qa";
        mvc.perform(post(cardBase + "/prepare").cookie(cookie).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("""
                    {"documentId":"%s","expectedRawSha256":"%s",
                     "temporalPolicy":"EXPLICIT_EFFECTIVE","applicabilityStartDate":"%s"}
                    """.formatted(card.getId(), card.getRawSha256(), card.getPublishedAt())))
                .andExpect(status().isOk());
        UUID cardReview = jdbc.queryForObject(
                "SELECT id FROM source_applicability_review WHERE source_document_id = ?",
                UUID.class, card.getId());
        mvc.perform(post(cardBase + "/" + cardReview + "/decision").cookie(cookie).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("""
                    {"expectedRawSha256":"%s","conditionsReviewed":true,
                     "exceptionsReviewed":true,"decision":"APPROVE",
                     "correctionCode":"SOURCE_DATE_MISCLASSIFIED","evidenceLocator":"%s"}
                    """.formatted(card.getRawSha256(), cardClaim.getLocator())))
                .andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("SELECT review_status FROM source_applicability_review WHERE id = ?",
                String.class, cardReview)).isEqualTo("PENDING");
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Seoul"));
        String prepare = """
                {"documentId":"%s","expectedRawSha256":"%s",
                 "temporalPolicy":"CURRENT_GUIDANCE","temporalVerifiedOn":"%s",
                 "temporalValidUntil":"%s"}
                """.formatted(insurance.getId(), insurance.getRawSha256(), today, today.plusDays(2));
        String base = "/api/v1/source-review/information/fsc-silsong24-guide";
        mvc.perform(post(base + "/prepare").cookie(cookie).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(prepare)).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM source_applicability_review
                WHERE source_document_id = ? AND review_status = 'PENDING'
                """, Long.class, insurance.getId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM source_review_decision_audit", Long.class)).isZero();
        UUID reviewId = jdbc.queryForObject("""
                SELECT id FROM source_applicability_review WHERE source_document_id = ?
                """, UUID.class, insurance.getId());
        SourceChunk insuranceClaim = chunks.findAllBySourceDocument_IdOrderBySequenceAsc(insurance.getId()).getFirst();
        String decision = """
                {"expectedRawSha256":"%s","conditionsReviewed":true,
                 "exceptionsReviewed":true,"decision":"APPROVE",
                 "correctionCode":"REVIEWER_CURRENTNESS_CHECK","evidenceLocator":"%s"}
                """.formatted(insurance.getRawSha256(), insuranceClaim.getLocator());
        mvc.perform(post(base + "/" + reviewId + "/decision").cookie(cookie).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("""
                    {"expectedRawSha256":"%s","conditionsReviewed":true,
                     "exceptionsReviewed":true,"decision":"APPROVE"}
                    """.formatted(insurance.getRawSha256()))).andExpect(status().isBadRequest());
        mvc.perform(post(base + "/" + reviewId + "/decision").cookie(cookie).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("""
                    {"expectedRawSha256":"%s","conditionsReviewed":true,
                     "exceptionsReviewed":true,"decision":"APPROVE",
                     "correctionCode":"CLAIM_OUTSIDE_SOURCE","evidenceLocator":"%s"}
                    """.formatted(insurance.getRawSha256(), insuranceClaim.getLocator())))
                .andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM source_review_decision_audit", Long.class)).isZero();
        mvc.perform(post(base + "/" + reviewId + "/decision").cookie(cookie).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(decision))
                .andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM source_review_decision_audit
                WHERE review_id = ? AND decision = 'APPROVED'
                """, Long.class, reviewId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT decision_reason FROM source_review_decision_audit WHERE review_id = ?
                """, String.class, reviewId)).contains("REVIEWER_SCOPE_CORRECTION", insuranceClaim.getLocator());
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM source_applicability_chunk
                WHERE review_id = ? AND evidence_role = 'CONDITION'
                """, Long.class, reviewId)).isEqualTo(1);
        assertThat(chunks.findAllBySourceDocument_IdOrderBySequenceAsc(insurance.getId()))
                .allSatisfy(chunk -> assertThat(chunk.getReviewStatus())
                        .isEqualTo(SourceChunkReviewStatus.APPROVED));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM retrieval_generation", Long.class)).isZero();
        // A dated publication does not supply the first effective date of a current guide.
        SourceDocument police = documents.findBySourceRegistry_IdAndStatus(
                registries.findBySourceKeyAndEnabledTrue("police-campaign").orElseThrow().getId(),
                SourceDocumentStatus.ACTIVE).orElseThrow();
        String policeBase = "/api/v1/source-review/information/police-campaign";
        SourceChunk policeChunk = chunks.findAllBySourceDocument_IdOrderBySequenceAsc(police.getId()).getFirst();
        String bounded = """
                {"documentId":"%s","expectedRawSha256":"%s","claimScopes":[
                 {"chunkId":"%s","policy":"CURRENT_GUIDANCE",
                  "verifiedOn":"%s","validUntil":"%s"}]}
                """.formatted(police.getId(), police.getRawSha256(), policeChunk.getId(),
                    today, today.plusDays(2));
        mvc.perform(post(policeBase + "/prepare").cookie(cookie).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(bounded)).andExpect(status().isOk());
        UUID policeReview = jdbc.queryForObject(
                "SELECT id FROM source_applicability_review WHERE source_document_id = ?",
                UUID.class, police.getId());
        assertThat(jdbc.queryForObject("SELECT applicability_start_date FROM source_applicability_review WHERE id = ?",
                LocalDate.class, policeReview)).isNull();
        mvc.perform(post(policeBase + "/" + policeReview + "/decision").cookie(cookie).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("""
                    {"expectedRawSha256":"%s","conditionsReviewed":true,
                     "exceptionsReviewed":true,"decision":"APPROVE",
                     "correctionCode":"REVIEWER_CURRENTNESS_CHECK","evidenceLocator":"%s"}
                    """.formatted(police.getRawSha256(), policeChunk.getLocator()))).andExpect(status().isNoContent());
        var readyPolice = List.of(new RetrievalCorpusSnapshotService.EligibleChunk(
                policeChunk.getId(), policeChunk.getBody(), police.getId(), police.getDocumentVersion(),
                null, null, "{}", police.getOrganizationName(), police.getTitle(),
                policeChunk.getParentSection(), policeChunk.getArticleReference(),
                policeChunk.getPageReference(), policeChunk.getLocator(), police.getCanonicalUrl()));
        assertThat(applicability.eligibleTopic(FinancialTopic.FRAUD_SECURITY, today,
                null, null, readyPolice)).containsKey(policeChunk.getId());
        assertThat(applicability.eligibleTopic(FinancialTopic.FRAUD_SECURITY, today.plusDays(3),
                null, null, readyPolice)).isEmpty();
        assertThat(applicability.eligibleTopic(FinancialTopic.FRAUD_SECURITY, today,
                true, null, null, readyPolice)).isEmpty();
        // A HOLD claim can be inspected and rejected, but never approved or searched.
        SourceDocument rights = documents.findBySourceRegistry_IdAndStatus(
                registries.findBySourceKeyAndEnabledTrue("fsc-consumer-complaints").orElseThrow().getId(),
                SourceDocumentStatus.ACTIVE).orElseThrow();
        String rightsBase = "/api/v1/source-review/information/fsc-consumer-complaints";
        mvc.perform(post(rightsBase + "/prepare").cookie(cookie).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("""
                    {"documentId":"%s","expectedRawSha256":"%s","temporalPolicy":"HOLD"}
                    """.formatted(rights.getId(), rights.getRawSha256()))).andExpect(status().isOk());
        UUID rightsReview = jdbc.queryForObject(
                "SELECT id FROM source_applicability_review WHERE source_document_id = ?",
                UUID.class, rights.getId());
        mvc.perform(post(rightsBase + "/" + rightsReview + "/decision").cookie(cookie).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("""
                    {"expectedRawSha256":"%s","conditionsReviewed":true,
                     "exceptionsReviewed":true,"decision":"APPROVE"}
                    """.formatted(rights.getRawSha256()))).andExpect(status().isBadRequest());
        mvc.perform(post(base + "/ready").cookie(cookie).with(csrf())
                .param("documentId", insurance.getId().toString()))
                .andExpect(status().isConflict());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM retrieval_generation", Long.class)).isZero();
    }
}
