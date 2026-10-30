package com.financialhelper.retrieval;

import com.financialhelper.source.ActiveRetrievalGenerationPersistenceService;
import com.financialhelper.source.RetrievalGeneration;
import com.financialhelper.source.RetrievalGenerationStatus;
import com.financialhelper.source.SourceChunkRepository;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

/** Synthetic contract probes; no real official document or answer key is asserted. */
class GoldenCoverageEvaluationTest {
    @Test
    void catalogIsCompleteAndDoesNotClaimReviewedRealWorldAnswers() {
        List<GoldenCoverageCatalog.Case> cases = GoldenCoverageCatalog.load();
        assertThat(cases).hasSize(16);
        assertThat(cases.stream().map(GoldenCoverageCatalog.Case::scenario).distinct()).hasSize(4);
        assertThat(cases).allSatisfy(value -> {
            assertThat(value.question()).isNotBlank();
            assertThat(value.requiredEvidenceType()).isNotBlank();
            assertThat(value.answerKeyStatus()).isEqualTo("UNVERIFIED");
            assertThat(value.unverifiedReason()).isNotBlank();
            assertThat(value.allowedStatuses()).isNotEmpty();
            assertThat(value.excluded()).isNotEmpty();
            assertThat(value.requiredFacts()).isNotEmpty();
        });
    }

    @TestFactory
    Stream<DynamicTest> syntheticEligibilityAndRetrievalProbes() {
        return GoldenCoverageCatalog.load().stream().map(value -> dynamicTest(value.caseId(), () -> {
            if ("CATALOG_ONLY".equals(value.syntheticProbe())) {
                assertThat(value.allowedStatuses()).containsAnyOf("NEEDS_CLARIFICATION", "NO_MATCH");
                assertThat(value.incidentDate() == null || value.conditionRequired()).isTrue();
                return;
            }
            UUID generationId = UUID.randomUUID();
            UUID chunkId = UUID.randomUUID();
            RetrievalGeneration generation = mock(RetrievalGeneration.class);
            when(generation.getId()).thenReturn(generationId);
            when(generation.getStatus()).thenReturn(RetrievalGenerationStatus.READY);
            ActiveRetrievalGenerationPersistenceService active = mock(ActiveRetrievalGenerationPersistenceService.class);
            when(active.current()).thenReturn(Optional.of(generation));
            RetrievalCorpusSnapshotService corpus = mock(RetrievalCorpusSnapshotService.class);
            when(corpus.readyChunks(generationId)).thenReturn(List.of(
                    new RetrievalCorpusSnapshotService.EligibleChunk(chunkId,
                            "합성 자료: 실제 공식 원문이 아님", UUID.randomUUID(), 1,
                            LocalDate.of(2026, 1, 1), null, null, "합성 기관", "합성 문서",
                            null, "제1조", "1", "page:1", "https://example.invalid/synthetic")));
            KureRuntimeClient kure = mock(KureRuntimeClient.class);
            SourceKeywordSearchService keyword = mock(SourceKeywordSearchService.class);
            OfficialSourceRetrievalService retrieval = new OfficialSourceRetrievalService(
                    active, corpus, keyword, kure, mock(SourceChunkRepository.class));
            boolean allowed = Set.of("COMMON_FOUND", "BOTH_BRANCHES_FAIL",
                    "PRODUCT_MISMATCH", "DATE_MISMATCH")
                    .contains(value.syntheticProbe());
            if ("BOTH_BRANCHES_FAIL".equals(value.syntheticProbe())) {
                when(kure.query(any())).thenThrow(new KureRuntimeException("synthetic failure"));
                when(keyword.searchApproved(any(), anyInt()))
                        .thenThrow(new IllegalStateException("synthetic failure"));
            } else if (allowed) {
                when(kure.query(any())).thenReturn(new KureRuntimeClient.KureRuntimeQueryResult(
                        generationId, List.of(new KureRuntimeClient.KureRuntimeQueryResult.Hit(
                        chunkId, 1, 0.9, generationId))));
            }
            OfficialSearchRequest request = new OfficialSearchRequest("합성 공식 정보",
                    "CARD_LOSS_UNAUTHORIZED_USE".equals(value.scenario()) ? "CARD" : "FINANCIAL_FRAUD",
                    value.institution(), value.productType(), LocalDate.parse(value.incidentDate()),
                    List.of(), 10, value.scenario());
            OfficialSearchResponse result = retrieval.searchReviewedCandidates(
                    request, allowed ? Set.of(chunkId) : Set.of());
            RetrievalStatus expected = switch (value.syntheticProbe()) {
                case "COMMON_FOUND" -> RetrievalStatus.FOUND;
                case "BOTH_BRANCHES_FAIL" -> RetrievalStatus.TEMPORARILY_UNAVAILABLE;
                default -> RetrievalStatus.NO_MATCH;
            };
            assertThat(result.status()).isEqualTo(expected);
            assertThat(value.allowedStatuses()).contains(result.status().name());
            if (expected == RetrievalStatus.FOUND) {
                assertThat(result.candidates()).hasSize(1);
                assertThat(result.candidates().getFirst().sourceChunkId()).isEqualTo(chunkId);
            } else {
                assertThat(result.candidates()).isEmpty();
            }
            if (expected == RetrievalStatus.TEMPORARILY_UNAVAILABLE) {
                assertThat(result.coverageGaps()).contains("SEMANTIC_BRANCH_UNAVAILABLE",
                        "KEYWORD_BRANCH_UNAVAILABLE");
            }
        }));
    }
}
