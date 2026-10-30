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

import static org.assertj.core.api.Assertions.assertThat;
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

    private static OfficialSearchRequest request(String institution, String product, LocalDate date) {
        return new OfficialSearchRequest("신용카드 분실 부정사용 공식 정보", "CARD",
                institution, product, date, List.of(), 10,
                "CARD_LOSS_UNAUTHORIZED_USE");
    }
}
