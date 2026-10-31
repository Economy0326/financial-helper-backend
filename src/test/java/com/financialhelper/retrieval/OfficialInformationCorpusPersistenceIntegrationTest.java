package com.financialhelper.retrieval;

import com.financialhelper.ai.OpenAiStructuredClient;
import com.financialhelper.consultation.Consultation;
import com.financialhelper.consultation.ConsultationCategory;
import com.financialhelper.consultation.ConsultationRepository;
import com.financialhelper.consultation.ConsultationScenario;
import com.financialhelper.guest.GuestSession;
import com.financialhelper.guest.GuestSessionRepository;
import com.financialhelper.guest.GuestSessionTokenService;
import com.financialhelper.law.KoreanLawOpenApiClient;
import com.financialhelper.source.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

/** Real source, chunk, index and active-generation repositories; no remote clients. */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class OfficialInformationCorpusPersistenceIntegrationTest {
    private static final OffsetDateTime NOW = OffsetDateTime.of(
            2026, 1, 3, 0, 0, 0, 0, ZoneOffset.UTC);
    @Autowired private JdbcTemplate jdbc;
    @Autowired private SourceRegistryRepository registries;
    @Autowired private SourceDocumentRepository documents;
    @Autowired private SourceChunkConfigurationRepository configurations;
    @Autowired private SourceChunkRepository chunks;
    @Autowired private RetrievalGenerationRepository generations;
    @Autowired private SourceChunkIndexingRepository indexings;
    @Autowired private ActiveRetrievalGenerationPersistenceService active;
    @Autowired private OfficialSourceRetrievalService retrieval;
    @Autowired private ReviewedSourceApplicabilityService applicability;
    @Autowired private GuestSessionTokenService tokens;
    @Autowired private GuestSessionRepository guests;
    @Autowired private ConsultationRepository consultations;
    @Autowired private PersistentSearchContextRepository contexts;
    @MockitoBean private KureRuntimeHttpClient kure;
    @MockitoBean private SourceKeywordSearchService keyword;
    @MockitoBean private KoreanLawOpenApiClient law;
    @MockitoBean private OpenAiStructuredClient openAi;

    private SourceChunk chunk;
    private RetrievalGeneration generation;

    @BeforeEach
    void createOwnedReadyCorpus() {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        UUID registryId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO source_registry (id, source_key, organization_name,
                    official_domain, canonical_url, acquisition_type, enabled,
                    created_at, updated_at, content_selector)
                VALUES (?, ?, ?, ?, ?, ?, TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, NULL)
                """, registryId, "r04-" + suffix, "㈜KB국민카드", "mapps.kbcard.com",
                "https://mapps.kbcard.com/r04-fixture/" + suffix,
                SourceAcquisitionType.HTML.name());
        SourceRegistry registry = registries.findById(registryId).orElseThrow();
        String body = "신용카드 분실 부정사용 공식 정보";
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        SourceDocument document = new SourceDocument(registry, 1,
                new SourceIngestionData.Parsed(registry.getCanonicalUrl(), "공식 테스트 문서",
                        null, NOW, "text/html", null, null,
                        SourceHashing.sha256(bytes), SourceHashing.sha256(body), bytes, body));
        document.setApplicabilityWindow(LocalDate.of(2025, 1, 1),
                LocalDate.of(2026, 12, 31));
        document = documents.saveAndFlush(document);
        SourceChunkConfiguration config = configurations.saveAndFlush(
                new SourceChunkConfiguration("r04-" + suffix, "{}", NOW));
        chunk = new SourceChunk(document, config,
                new SourceChunkData.Definition(config.getConfigVersion(), "{}", 0, body,
                        null, "제1조", "1", "page:1", 0, body.length(), "{}"), NOW);
        chunk.approve(NOW);
        chunk = chunks.saveAndFlush(chunk);
        generation = generations.saveAndFlush(new RetrievalGeneration(
                new RetrievalGenerationData.Definition("r04-" + suffix,
                        "representation-v1", "model", "revision-1", "tokenizer",
                        "revision-1", "{}", "{}", "{}"), NOW));
        SourceChunkIndexing indexing = indexings.saveAndFlush(
                new SourceChunkIndexing(chunk, generation, NOW));
        indexing.markProcessing(NOW.plusSeconds(1));
        indexing.markReady(NOW.plusSeconds(2), "{}");
        indexings.saveAndFlush(indexing);
        generation.markProcessing(NOW.plusSeconds(1));
        generation.markReady(1, NOW.plusSeconds(3));
        generations.saveAndFlush(generation);
        active.activate(generation.getId());
        when(kure.query(any())).thenReturn(new KureRuntimeClient.KureRuntimeQueryResult(
                generation.getId(), List.of(new KureRuntimeClient.KureRuntimeQueryResult.Hit(
                chunk.getId(), 1, 0.9, generation.getId()))));
        when(keyword.searchApproved(any(), anyInt())).thenReturn(List.of(chunk));
    }

    @Test
    void approvedChunkFromCurrentReadyGenerationIsCandidate() {
        OfficialSearchResponse response = retrieval.searchCandidates(request(
                "㈜KB국민카드", "개인 신용카드", LocalDate.of(2026, 1, 2)));
        assertThat(response.status()).isEqualTo(RetrievalStatus.FOUND);
        assertThat(response.candidates()).hasSize(1);
        assertThat(response.candidates().getFirst().sourceChunkId()).isEqualTo(chunk.getId());
        assertThat(response.candidates().getFirst().retrievalGenerationId()).isEqualTo(generation.getId());
        verifyNoInteractions(law, openAi);
    }

    @Test
    void parsedSyntheticHtmlVersionNeedsItsOwnReviewedChunkBeforeSearch() {
        SourceDocument previous = chunk.getSourceDocument();
        previous.supersede(NOW.plusSeconds(4));
        documents.saveAndFlush(previous);
        SourceRegistry registry = previous.getSourceRegistry();
        String html = "<html><body><main><h1>합성 카드 정보</h1><p>"
                + "접수와 조사 및 결과 확인은 각각 다른 단계입니다. ".repeat(8)
                + "</p></main></body></html>";
        byte[] raw = html.getBytes(StandardCharsets.UTF_8);
        var parsed = new HtmlSourceDocumentParser().parse(SourceIngestionData.Snapshot.from(registry),
                new SourceIngestionData.Fetched(registry.getCanonicalUrl(), "text/html", null, null,
                        raw, NOW.plusSeconds(5)));
        SourceDocument current = new SourceDocument(registry, 2, parsed);
        current.setApplicabilityWindow(LocalDate.of(2025, 1, 1), LocalDate.of(2026, 12, 31));
        current = documents.saveAndFlush(current);
        assertThat(current.getRawSha256()).isEqualTo(SourceHashing.sha256(raw));
        assertThat(current.getDocumentVersion()).isEqualTo(2);

        String body = parsed.normalizedContent();
        SourceChunk next = new SourceChunk(current, chunk.getChunkConfiguration(),
                new SourceChunkData.Definition(chunk.getChunkConfiguration().getConfigVersion(),
                        "{}", 0, body, null, null, null,
                        "normalized-content:utf16:0-" + body.length(), 0, body.length(), "{}"), NOW);
        next.approve(NOW);
        next = chunks.saveAndFlush(next);
        assertThat(next.getBody()).isEqualTo(current.getNormalizedContent());
        SourceChunkIndexing indexing = new SourceChunkIndexing(next, generation, NOW);
        indexing.markProcessing(NOW.plusSeconds(5));
        indexing.markReady(NOW.plusSeconds(6), "{}");
        indexings.saveAndFlush(indexing);

        chunk = next;
        when(kure.query(any())).thenReturn(new KureRuntimeClient.KureRuntimeQueryResult(
                generation.getId(), List.of(new KureRuntimeClient.KureRuntimeQueryResult.Hit(
                next.getId(), 1, 0.9, generation.getId()))));
        when(keyword.searchApproved(any(), anyInt())).thenReturn(List.of(next));
        var request = request(null, "개인 신용카드", LocalDate.of(2026, 1, 2));
        assertThat(applicability.eligible(request, ready())).doesNotContainKey(next.getId());
        UUID review = reviewed("COMMON", null);
        assertThat(applicability.eligible(request, ready())).containsKey(next.getId());
        var result = retrieval.searchReviewedCandidates(request, java.util.Set.of(next.getId()));
        assertThat(result.status()).isEqualTo(RetrievalStatus.FOUND);
        assertThat(result.candidates()).extracting(OfficialEvidenceCandidate::sourceChunkId)
                .containsExactly(next.getId());
        jdbc.update("""
                UPDATE source_applicability_review SET review_status = 'REVOKED',
                    revoked_at = CURRENT_TIMESTAMP, revoked_by = 'fixture',
                    revocation_reason = 'synthetic revocation' WHERE id = ?
                """, review);
        assertThat(applicability.eligible(request, ready())).doesNotContainKey(next.getId());
        verifyNoInteractions(law, openAi);
    }

    @Test
    void revokedChunkIsExcludedEvenWhenItsIndexingRemainsReady() {
        chunk.revokeApproval(NOW.plusSeconds(4));
        chunks.saveAndFlush(chunk);
        OfficialSearchResponse response = retrieval.searchCandidates(request(
                "㈜KB국민카드", "개인 신용카드", LocalDate.of(2026, 1, 2)));
        assertThat(response.candidates()).isEmpty();
        assertThat(response.status()).isEqualTo(RetrievalStatus.NO_MATCH);
    }

    @Test
    void supersededDocumentIsExcludedEvenWhenItsChunkAndIndexingRemainReady() {
        SourceDocument document = documents.findById(chunk.getSourceDocument().getId()).orElseThrow();
        document.supersede(NOW.plusSeconds(4));
        documents.saveAndFlush(document);
        OfficialSearchResponse response = retrieval.searchCandidates(request(
                "㈜KB국민카드", "개인 신용카드", LocalDate.of(2026, 1, 2)));
        assertThat(response.candidates()).isEmpty();
        assertThat(response.status()).isEqualTo(RetrievalStatus.NO_MATCH);
    }

    @Test
    void institutionProductAndDateMismatchExcludeTheDocument() {
        assertThat(retrieval.searchCandidates(request("다른 카드사", "개인 신용카드",
                LocalDate.of(2026, 1, 2))).candidates()).isEmpty();
        assertThat(retrieval.searchCandidates(request("㈜KB국민카드", "체크카드",
                LocalDate.of(2026, 1, 2))).candidates()).isEmpty();
        assertThat(retrieval.searchCandidates(request("㈜KB국민카드", "개인 신용카드",
                LocalDate.of(2027, 1, 2))).candidates()).isEmpty();
    }

    @Test
    void oneOrBothRetrievalBranchesFailWithoutHidingDegradation() {
        when(kure.query(any())).thenThrow(new KureRuntimeException("fixture unavailable"));
        OfficialSearchResponse partial = retrieval.searchCandidates(request(
                "㈜KB국민카드", "개인 신용카드", LocalDate.of(2026, 1, 2)));
        assertThat(partial.status()).isEqualTo(RetrievalStatus.FOUND);
        assertThat(partial.degraded()).isTrue();
        when(keyword.searchApproved(any(), anyInt())).thenThrow(new IllegalStateException("fixture unavailable"));
        OfficialSearchResponse failed = retrieval.searchCandidates(request(
                "㈜KB국민카드", "개인 신용카드", LocalDate.of(2026, 1, 2)));
        assertThat(failed.status()).isEqualTo(RetrievalStatus.TEMPORARILY_UNAVAILABLE);
        assertThat(failed.candidates()).isEmpty();
    }

    @Test
    void existingConsultationSearchStillPersistsRevisionBoundContext() {
        String raw = tokens.generateRawToken();
        GuestSession guest = guests.saveAndFlush(new GuestSession(
                tokens.hashToken(raw), NOW, NOW.plusYears(2)));
        Consultation consultation = new Consultation(guest, NOW);
        consultation.updateCategory(ConsultationCategory.CARD,
                ConsultationScenario.CARD_LOSS_UNAUTHORIZED_USE, NOW.plusSeconds(1));
        consultation.updateSituation("KB국민카드 개인 신용카드 분실 2026-01-02",
                NOW.plusSeconds(2));
        consultation = consultations.saveAndFlush(consultation);
        OfficialSearchResponse response = retrieval.search(consultation.getId(), request(
                "㈜KB국민카드", "개인 신용카드", LocalDate.of(2026, 1, 2)));
        assertThat(response.status()).isEqualTo(RetrievalStatus.FOUND);
        assertThat(contexts.findById(response.searchId())).isPresent();
        assertThat(contexts.findById(response.searchId()).orElseThrow()
                .getConsultation().getId()).isEqualTo(consultation.getId());
        assertThat(retrieval.getPassages(consultation.getId(), response.searchId(),
                List.of(response.candidates().getFirst().candidateId()))).hasSize(1);
    }

    @Test
    void commonReviewAllowsInstitutionUnknownOnlyForReviewedSupport() {
        OfficialSearchRequest unknown = request(null, "개인 신용카드", LocalDate.of(2026, 1, 2));
        assertThat(applicability.eligible(unknown, ready())).isEmpty();
        UUID review = reviewed("COMMON", null);
        var matches = applicability.eligible(unknown, ready());
        assertThat(matches).containsKey(chunk.getId());
        assertThat(matches.get(chunk.getId()).reviewId()).isEqualTo(review);
        assertThat(retrieval.searchReviewedCandidates(unknown, matches.keySet()).status())
                .isEqualTo(RetrievalStatus.FOUND);
        assertThat(retrieval.searchReviewedCandidates(unknown, java.util.Set.of()).candidates()).isEmpty();
    }

    @Test
    void institutionReviewNeverLeaksToUnknownOrAnotherInstitution() {
        reviewed("INSTITUTION_SPECIFIC", "㈜KB국민카드");
        assertThat(applicability.eligible(request(null, "개인 신용카드",
                LocalDate.of(2026, 1, 2)), ready())).isEmpty();
        assertThat(applicability.eligible(request("다른 카드사", "개인 신용카드",
                LocalDate.of(2026, 1, 2)), ready())).isEmpty();
        assertThat(applicability.eligible(request("㈜KB국민카드", "개인 신용카드",
                LocalDate.of(2026, 1, 2)), ready())).containsKey(chunk.getId());
    }

    @Test
    void reviewIsBoundToProductDateAndCurrentApproval() {
        UUID review = reviewed("COMMON", null);
        assertThat(applicability.eligible(request(null, "체크카드",
                LocalDate.of(2026, 1, 2)), ready())).isEmpty();
        assertThat(applicability.eligible(request(null, "개인 신용카드",
                LocalDate.of(2027, 1, 2)), ready())).isEmpty();
        jdbc.update("""
                UPDATE source_applicability_review SET review_status = 'REVOKED',
                    revoked_at = CURRENT_TIMESTAMP, revoked_by = 'fixture',
                    revocation_reason = 'fixture revocation' WHERE id = ?
                """, review);
        assertThat(applicability.eligible(request(null, "개인 신용카드",
                LocalDate.of(2026, 1, 2)), ready())).isEmpty();
    }

    @Test
    void pendingReviewDoesNotBecomeSearchableFromAnApprovedChunk() {
        UUID review = UUID.randomUUID();
        SourceDocument document = chunk.getSourceDocument();
        jdbc.update("""
                INSERT INTO source_applicability_review (id, source_document_id,
                    reviewed_raw_sha256, scope_kind, scenario, product_type,
                    applicability_start_date)
                VALUES (?, ?, ?, 'COMMON', 'CARD_LOSS_UNAUTHORIZED_USE',
                    '개인 신용카드', DATE '2025-01-01')
                """, review, document.getId(), document.getRawSha256());
        jdbc.update("""
                INSERT INTO source_applicability_chunk
                    (review_id, source_document_id, source_chunk_id, evidence_role)
                VALUES (?, ?, ?, 'SUPPORT')
                """, review, document.getId(), chunk.getId());
        assertThat(applicability.eligible(request(null, "개인 신용카드",
                LocalDate.of(2026, 1, 2)), ready())).isEmpty();
    }

    @Test
    void reviewWithWrongRawHashIsRejectedByDatabase() {
        SourceDocument document = chunk.getSourceDocument();
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO source_applicability_review (id, source_document_id,
                    reviewed_raw_sha256, scope_kind, scenario, product_type,
                    applicability_start_date)
                VALUES (?, ?, ?, 'COMMON', 'CARD_LOSS_UNAUTHORIZED_USE',
                    '개인 신용카드', DATE '2025-01-01')
                """, UUID.randomUUID(), document.getId(), "0".repeat(64)))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @Test
    void revokedChunkCannotSatisfySupportOrQualifier() {
        reviewed("COMMON", null);
        chunk.revokeApproval(NOW.plusSeconds(4));
        chunks.saveAndFlush(chunk);
        assertThat(applicability.eligible(request(null, "개인 신용카드",
                LocalDate.of(2026, 1, 2)), ready())).isEmpty();
    }

    @Test
    void missingSupportAndUnreviewedConditionsFailClosed() {
        UUID review = reviewed("COMMON", null);
        jdbc.update("UPDATE source_applicability_review SET condition_required = TRUE WHERE id = ?", review);
        assertThat(applicability.eligible(request(null, "개인 신용카드",
                LocalDate.of(2026, 1, 2)), ready())).isEmpty();
        jdbc.update("UPDATE source_applicability_review SET condition_required = FALSE, exception_required = TRUE WHERE id = ?", review);
        assertThat(applicability.eligible(request(null, "개인 신용카드",
                LocalDate.of(2026, 1, 2)), ready())).isEmpty();
        jdbc.update("DELETE FROM source_applicability_chunk WHERE review_id = ?", review);
        assertThat(applicability.eligible(request(null, "개인 신용카드",
                LocalDate.of(2026, 1, 2)), ready())).isEmpty();
    }

    @Test
    void reviewCannotBorrowAChunkFromAnotherDocument() {
        UUID review = reviewed("COMMON", null);
        String suffix = UUID.randomUUID().toString().replace("-", "");
        UUID registryId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO source_registry (id, source_key, organization_name, official_domain,
                    canonical_url, acquisition_type, enabled, created_at, updated_at)
                VALUES (?, ?, '㈜KB국민카드', 'mapps.kbcard.com', ?, 'HTML', TRUE,
                    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, registryId, "r05-" + suffix, "https://mapps.kbcard.com/r05/" + suffix);
        SourceRegistry otherRegistry = registries.findById(registryId).orElseThrow();
        String text = "다른 문서";
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        SourceDocument otherDocument = documents.saveAndFlush(new SourceDocument(otherRegistry, 1,
                new SourceIngestionData.Parsed(otherRegistry.getCanonicalUrl(), "다른 문서", null,
                        NOW, "text/html", null, null, SourceHashing.sha256(bytes),
                        SourceHashing.sha256(text), bytes, text)));
        SourceChunk otherChunk = chunks.saveAndFlush(new SourceChunk(otherDocument,
                chunk.getChunkConfiguration(), new SourceChunkData.Definition(
                chunk.getChunkConfiguration().getConfigVersion(), "{}", 0, text,
                null, "제1조", "1", "page:1", 0, text.length(), "{}"), NOW));
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO source_applicability_chunk
                    (review_id, source_document_id, source_chunk_id, evidence_role)
                VALUES (?, ?, ?, 'CONDITION')
                """, review, chunk.getSourceDocument().getId(), otherChunk.getId()))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @Test
    void conditionNotReadyInCurrentGenerationBlocksSupport() {
        UUID review = reviewed("COMMON", null);
        String body = chunk.getBody();
        SourceChunk condition = new SourceChunk(chunk.getSourceDocument(),
                chunk.getChunkConfiguration(), new SourceChunkData.Definition(
                chunk.getChunkConfiguration().getConfigVersion(), "{}", 1, body,
                null, "제2조", "1", "page:1#condition", 0, body.length(), "{}"), NOW);
        condition.approve(NOW);
        condition = chunks.saveAndFlush(condition);
        jdbc.update("""
                INSERT INTO source_applicability_chunk
                    (review_id, source_document_id, source_chunk_id, evidence_role)
                VALUES (?, ?, ?, 'CONDITION')
                """, review, chunk.getSourceDocument().getId(), condition.getId());
        assertThat(applicability.eligible(request(null, "개인 신용카드",
                LocalDate.of(2026, 1, 2)), ready())).isEmpty();
    }

    @Test
    void reviewDoesNotTransferToAnotherScenarioOrDocumentVersion() {
        reviewed("COMMON", null);
        OfficialSearchRequest voice = new OfficialSearchRequest("공식 정보", "FINANCIAL_FRAUD",
                null, "개인 신용카드", LocalDate.of(2026, 1, 2), List.of(), 10,
                "VOICE_PHISHING_SUSPICIOUS_TRANSFER");
        assertThat(applicability.eligible(voice, ready())).isEmpty();
        SourceDocument document = chunk.getSourceDocument();
        document.supersede(NOW.plusSeconds(4));
        documents.saveAndFlush(document);
        String body = chunk.getBody();
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        SourceDocument next = new SourceDocument(document.getSourceRegistry(), 2,
                new SourceIngestionData.Parsed(document.getCanonicalUrl(), "새 판본", null,
                        NOW.plusSeconds(5), "text/html", null, null,
                        SourceHashing.sha256(bytes), SourceHashing.sha256(body), bytes, body));
        next.setApplicabilityWindow(LocalDate.of(2025, 1, 1), LocalDate.of(2026, 12, 31));
        next = documents.saveAndFlush(next);
        SourceChunk nextChunk = new SourceChunk(next, chunk.getChunkConfiguration(),
                new SourceChunkData.Definition(chunk.getChunkConfiguration().getConfigVersion(),
                        "{}", 0, body, null, "제1조", "1", "page:1", 0, body.length(), "{}"), NOW);
        nextChunk.approve(NOW);
        nextChunk = chunks.saveAndFlush(nextChunk);
        SourceChunkIndexing indexing = new SourceChunkIndexing(nextChunk, generation, NOW);
        indexing.markProcessing(NOW.plusSeconds(1));
        indexing.markReady(NOW.plusSeconds(2), "{}");
        indexings.saveAndFlush(indexing);
        assertThat(applicability.eligible(request(null, "개인 신용카드",
                LocalDate.of(2026, 1, 2)), ready())).isEmpty();
    }

    static Stream<GoldenCoverageCatalog.Case> goldenDbCases() {
        return GoldenCoverageCatalog.load().stream()
                .filter(value -> !"CATALOG_ONLY".equals(value.syntheticProbe()));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("goldenDbCases")
    void goldenQuestionChecksReviewedDbScopeBeforeRetrieval(GoldenCoverageCatalog.Case value) {
        String probe = value.syntheticProbe();
        String reviewScenario = "SCENARIO_MISMATCH".equals(probe)
                ? "CARD_LOSS_UNAUTHORIZED_USE" : value.scenario();
        String reviewScope = "INSTITUTION_UNKNOWN_BLOCK".equals(probe)
                || "INSTITUTION_MISMATCH".equals(probe)
                ? "INSTITUTION_SPECIFIC" : "COMMON";
        String reviewInstitution = "INSTITUTION_SPECIFIC".equals(reviewScope)
                ? "㈜KB국민카드" : null;
        String reviewProduct = "PRODUCT_MISMATCH".equals(probe)
                ? "개인 신용카드" : value.productType();
        UUID review = reviewed(reviewScope, reviewInstitution, reviewScenario, reviewProduct);
        if (value.conditionRequired() || value.exceptionRequired()) {
            jdbc.update("""
                    UPDATE source_applicability_review SET condition_required = ?,
                        exception_required = ? WHERE id = ?
                    """, value.conditionRequired(), value.exceptionRequired(), review);
            if (value.conditionRequired()) {
                jdbc.update("""
                        INSERT INTO source_applicability_chunk
                            (review_id, source_document_id, source_chunk_id, evidence_role)
                        VALUES (?, ?, ?, 'CONDITION')
                        """, review, chunk.getSourceDocument().getId(), chunk.getId());
            }
            if (value.exceptionRequired()) {
                jdbc.update("""
                        INSERT INTO source_applicability_chunk
                            (review_id, source_document_id, source_chunk_id, evidence_role)
                        VALUES (?, ?, ?, 'EXCEPTION')
                        """, review, chunk.getSourceDocument().getId(), chunk.getId());
            }
        }
        if ("REVIEW_REVOKED".equals(probe)) {
            jdbc.update("""
                    UPDATE source_applicability_review SET review_status = 'REVOKED',
                        revoked_at = CURRENT_TIMESTAMP, revoked_by = 'fixture',
                        revocation_reason = 'synthetic revocation' WHERE id = ?
                    """, review);
        }
        OfficialSearchRequest request = new OfficialSearchRequest("합성 공식 정보",
                "CARD_LOSS_UNAUTHORIZED_USE".equals(value.scenario()) ? "CARD" : "FINANCIAL_FRAUD",
                value.institution(), value.productType(), LocalDate.parse(value.incidentDate()),
                List.of(), 10, value.scenario());
        var matches = applicability.eligible(request, ready());
        boolean allowed = "COMMON_FOUND".equals(probe) || "BOTH_BRANCHES_FAIL".equals(probe);
        assertThat(matches.containsKey(chunk.getId())).isEqualTo(allowed);
        if ("BOTH_BRANCHES_FAIL".equals(probe)) {
            when(kure.query(any())).thenThrow(new KureRuntimeException("synthetic outage"));
            when(keyword.searchApproved(any(), anyInt()))
                    .thenThrow(new IllegalStateException("synthetic outage"));
        }
        OfficialSearchResponse result = retrieval.searchReviewedCandidates(request, matches.keySet());
        RetrievalStatus expected = "BOTH_BRANCHES_FAIL".equals(probe)
                ? RetrievalStatus.TEMPORARILY_UNAVAILABLE
                : allowed ? RetrievalStatus.FOUND : RetrievalStatus.NO_MATCH;
        assertThat(result.status()).isEqualTo(expected);
        assertThat(value.allowedStatuses()).contains(expected.name());
        if (allowed && expected == RetrievalStatus.FOUND) {
            assertThat(result.candidates()).extracting(OfficialEvidenceCandidate::sourceChunkId)
                    .containsExactly(chunk.getId());
        } else {
            assertThat(result.candidates()).isEmpty();
        }
        verifyNoInteractions(law, openAi);
    }

    private List<RetrievalCorpusSnapshotService.EligibleChunk> ready() {
        return new RetrievalCorpusSnapshotService(indexings).readyChunks(generation.getId());
    }

    private UUID reviewed(String scope, String institution) {
        return reviewed(scope, institution, "CARD_LOSS_UNAUTHORIZED_USE", "개인 신용카드");
    }

    private UUID reviewed(String scope, String institution, String scenario, String product) {
        UUID id = UUID.randomUUID();
        SourceDocument document = chunk.getSourceDocument();
        jdbc.update("""
                INSERT INTO source_applicability_review (id, source_document_id, reviewed_raw_sha256,
                    scope_kind, scenario, product_type, institution_name, applicability_start_date,
                    applicability_end_date, review_status, conditions_reviewed, exceptions_reviewed,
                    reviewed_by, reviewed_at)
                VALUES (?, ?, ?, ?, ?, ?, ?,
                    DATE '2025-01-01', DATE '2026-12-31', 'APPROVED', TRUE, TRUE, 'fixture', CURRENT_TIMESTAMP)
                """, id, document.getId(), document.getRawSha256(), scope,
                scenario, product, institution);
        jdbc.update("""
                INSERT INTO source_applicability_chunk (review_id, source_document_id, source_chunk_id, evidence_role)
                VALUES (?, ?, ?, 'SUPPORT')
                """, id, document.getId(), chunk.getId());
        return id;
    }

    private static OfficialSearchRequest request(String institution, String product, LocalDate date) {
        return new OfficialSearchRequest("신용카드 분실 부정사용 공식 정보", "CARD",
                institution, product, date, List.of(), 10,
                "CARD_LOSS_UNAUTHORIZED_USE");
    }
}
