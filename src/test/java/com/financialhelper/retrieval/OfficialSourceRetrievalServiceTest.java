package com.financialhelper.retrieval;

import com.financialhelper.source.ActiveRetrievalGenerationPersistenceService;
import com.financialhelper.source.RetrievalGeneration;
import com.financialhelper.source.RetrievalGenerationStatus;
import com.financialhelper.source.SourceChunk;
import com.financialhelper.source.SourceChunkRepository;
import com.financialhelper.source.SourceChunkReviewStatus;
import com.financialhelper.source.SourceDocument;
import com.financialhelper.source.SourceDocumentStatus;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.mock;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;

@ExtendWith(MockitoExtension.class)
class OfficialSourceRetrievalServiceTest {
    @Mock private ActiveRetrievalGenerationPersistenceService activeGeneration;
    @Mock private RetrievalCorpusSnapshotService corpusSnapshot;
    @Mock private SourceKeywordSearchService keywordSearch;
    @Mock private KureRuntimeClient kureRuntime;
    @Mock private SourceChunkRepository sourceChunkRepository;

    private OfficialSourceRetrievalService service;

    @BeforeEach
    void setUp() {
        service = new OfficialSourceRetrievalService(
                activeGeneration, corpusSnapshot, keywordSearch,
                kureRuntime, sourceChunkRepository);
    }

    @Test
    void missing_institution_is_a_clarification_not_a_corpus_gap() {
        OfficialSearchResponse response = service.search(new OfficialSearchRequest(
                "분실 카드", "CARD", null, "신용카드", null, List.of(), 10));

        assertThat(response.status()).isEqualTo(RetrievalStatus.NEEDS_CLARIFICATION);
        assertThat(response.missingFacts()).containsExactly("INSTITUTION");
        assertThat(response.coverageGaps()).isEmpty();
    }

    @Test
    void arbitrary_candidate_ids_are_rejected() {
        UUID searchId = UUID.randomUUID();
        assertThatThrownBy(() -> service.getPassages(searchId, List.of(UUID.randomUUID())))
                .isInstanceOf(UnknownRetrievalCandidateException.class);
    }

    @Test
    void no_active_generation_is_reported_as_a_coverage_gap() {
        when(activeGeneration.current()).thenReturn(Optional.empty());

        OfficialSearchResponse response = service.search(new OfficialSearchRequest(
                "분실 카드", "CARD", "KB국민카드", "신용카드", null, List.of(), 10));

        assertThat(response.status()).isEqualTo(RetrievalStatus.NO_MATCH);
        assertThat(response.coverageGaps()).containsExactly("NO_ACTIVE_READY_GENERATION");
    }

    @Test
    void one_branch_failure_returns_available_evidence_as_degraded() {
        UUID generationId = UUID.randomUUID();
        UUID chunkId = UUID.randomUUID();
        RetrievalGeneration generation = mock(RetrievalGeneration.class);
        SourceChunk chunk = mock(SourceChunk.class);
        when(generation.getId()).thenReturn(generationId);
        when(generation.getStatus()).thenReturn(RetrievalGenerationStatus.READY);
        when(activeGeneration.current()).thenReturn(Optional.of(generation));
        when(corpusSnapshot.readyChunks(generationId)).thenReturn(List.of(
                new RetrievalCorpusSnapshotService.EligibleChunk(
                        chunkId, "KB국민카드 신용카드 분실 안내", UUID.randomUUID(), 1,
                        LocalDate.of(2025, 1, 1), null,
                        "{}", "㈜KB국민카드", "분실 안내", "분실", "제1조", "p.1", "loc", "https://kbcard.com")));
        when(kureRuntime.query(org.mockito.ArgumentMatchers.any()))
                .thenThrow(new KureRuntimeException("runtime unavailable"));
        when(chunk.getId()).thenReturn(chunkId);
        when(keywordSearch.searchApproved(anyString(), anyInt())).thenReturn(List.of(chunk));

        OfficialSearchResponse response = service.search(new OfficialSearchRequest(
                "분실 안내", "CARD", "KB국민카드", null, null, List.of(), 10));

        assertThat(response.status()).isEqualTo(RetrievalStatus.FOUND);
        assertThat(response.degraded()).isTrue();
        assertThat(response.candidates()).hasSize(1);
        assertThat(response.coverageGaps()).containsExactly("SEMANTIC_BRANCH_UNAVAILABLE");
    }

    @Test
    void incident_date_excludes_future_and_unknown_applicability_without_guessing() {
        UUID generationId = UUID.randomUUID();
        RetrievalGeneration generation = mock(RetrievalGeneration.class);
        when(generation.getId()).thenReturn(generationId);
        when(generation.getStatus()).thenReturn(RetrievalGenerationStatus.READY);
        when(activeGeneration.current()).thenReturn(Optional.of(generation));
        when(corpusSnapshot.readyChunks(generationId)).thenReturn(List.of(
                new RetrievalCorpusSnapshotService.EligibleChunk(
                        UUID.randomUUID(), "future", UUID.randomUUID(), 2,
                        LocalDate.of(2027, 1, 1), null,
                        "{}", "KB국민카드", "약관", "분실", "제1조", "p.1", "loc", "url"),
                new RetrievalCorpusSnapshotService.EligibleChunk(
                        UUID.randomUUID(), "unknown", UUID.randomUUID(), 1,
                        null, null,
                        "{}", "KB국민카드", "약관", "분실", "제1조", "p.1", "loc", "url")));

        OfficialSearchResponse response = service.search(new OfficialSearchRequest(
                "분실", "CARD", "KB국민카드", null,
                LocalDate.of(2026, 1, 1), List.of(), 10));

        assertThat(response.status()).isEqualTo(RetrievalStatus.NO_MATCH);
        assertThat(response.coverageGaps()).containsExactly("TEMPORAL_APPLICABILITY_UNKNOWN");
    }

    @Test
    void incident_date_keeps_only_the_applicable_version() {
        UUID generationId = UUID.randomUUID();
        UUID applicableId = UUID.randomUUID();
        RetrievalGeneration generation = mock(RetrievalGeneration.class);
        when(generation.getId()).thenReturn(generationId);
        when(generation.getStatus()).thenReturn(RetrievalGenerationStatus.READY);
        when(activeGeneration.current()).thenReturn(Optional.of(generation));
        when(corpusSnapshot.readyChunks(generationId)).thenReturn(List.of(
                new RetrievalCorpusSnapshotService.EligibleChunk(
                        applicableId, "적용 가능한 분실 조항", UUID.randomUUID(), 1,
                        LocalDate.of(2025, 1, 1), LocalDate.of(2026, 12, 31),
                        "{}", "KB국민카드", "약관", "분실", "제40조", "p.29", "loc", "url"),
                new RetrievalCorpusSnapshotService.EligibleChunk(
                        UUID.randomUUID(), "미래 버전", UUID.randomUUID(), 2,
                        LocalDate.of(2027, 1, 1), null,
                        "{}", "KB국민카드", "약관", "분실", "제40조", "p.29", "loc", "url")));
        when(kureRuntime.query(org.mockito.ArgumentMatchers.any()))
                .thenReturn(new KureRuntimeClient.KureRuntimeQueryResult(
                        generationId,
                        List.of(new KureRuntimeClient.KureRuntimeQueryResult.Hit(
                                applicableId, 1, 1.0, generationId))));
        when(keywordSearch.searchApproved(anyString(), anyInt())).thenReturn(List.of());

        OfficialSearchResponse response = service.search(new OfficialSearchRequest(
                "분실", "CARD", "KB국민카드", null,
                LocalDate.of(2026, 1, 1), List.of(), 10));

        assertThat(response.status()).isEqualTo(RetrievalStatus.FOUND);
        assertThat(response.candidates()).extracting(OfficialEvidenceCandidate::sourceChunkId)
                .containsExactly(applicableId);
        assertThat(response.coverageGaps()).isEmpty();
    }

    @Test
    void passage_expansion_keeps_parent_article_and_exception_chunks_in_one_version() {
        UUID generationId = UUID.randomUUID();
        UUID documentId = UUID.randomUUID();
        UUID candidateChunkId = UUID.randomUUID();
        UUID referencedChunkId = UUID.randomUUID();
        RetrievalGeneration generation = mock(RetrievalGeneration.class);
        when(generation.getId()).thenReturn(generationId);
        when(generation.getStatus()).thenReturn(RetrievalGenerationStatus.READY);
        when(activeGeneration.current()).thenReturn(Optional.of(generation));
        when(corpusSnapshot.readyChunks(generationId)).thenReturn(List.of(
                new RetrievalCorpusSnapshotService.EligibleChunk(
                        candidateChunkId, "분실 조항 본문", documentId, 1,
                        LocalDate.of(2025, 1, 1), null,
                        "{\"referencedChunkIds\":[\"" + referencedChunkId + "\"]}",
                        "KB국민카드", "약관", "분실", "제40조", "p.29", "loc", "url")));
        when(kureRuntime.query(org.mockito.ArgumentMatchers.any()))
                .thenReturn(new KureRuntimeClient.KureRuntimeQueryResult(
                        generationId,
                        List.of(new KureRuntimeClient.KureRuntimeQueryResult.Hit(
                                candidateChunkId, 1, 3.0, generationId))));
        when(keywordSearch.searchApproved(anyString(), anyInt())).thenReturn(List.of());

        SourceDocument document = mock(SourceDocument.class);
        when(document.getStatus()).thenReturn(SourceDocumentStatus.ACTIVE);
        SourceChunk candidate = mock(SourceChunk.class);
        SourceChunk parent = mock(SourceChunk.class);
        SourceChunk exception = mock(SourceChunk.class);
        SourceChunk unrelated = mock(SourceChunk.class);
        SourceChunk referenced = mock(SourceChunk.class);
        when(candidate.getId()).thenReturn(candidateChunkId);
        when(candidate.getBody()).thenReturn("분실 조항 본문");
        when(candidate.getSequence()).thenReturn(2);
        when(candidate.getSourceDocument()).thenReturn(document);
        when(parent.getId()).thenReturn(UUID.randomUUID());
        when(parent.getBody()).thenReturn("제40조의 부모 내용");
        when(parent.getSequence()).thenReturn(1);
        when(parent.getArticleReference()).thenReturn("제40조");
        when(parent.getParentSection()).thenReturn("분실");
        when(parent.getSourceDocument()).thenReturn(document);
        when(exception.getId()).thenReturn(UUID.randomUUID());
        when(exception.getBody()).thenReturn("다만 예외가 적용된다");
        when(exception.getSequence()).thenReturn(3);
        when(exception.getArticleReference()).thenReturn("제40조");
        when(exception.getParentSection()).thenReturn("분실");
        when(exception.getSourceDocument()).thenReturn(document);
        when(unrelated.getId()).thenReturn(UUID.randomUUID());
        when(unrelated.getBody()).thenReturn("다른 조항");
        when(unrelated.getArticleReference()).thenReturn("제41조");
        when(unrelated.getParentSection()).thenReturn("분실");
        when(unrelated.getSourceDocument()).thenReturn(document);
        when(referenced.getId()).thenReturn(referencedChunkId);
        when(referenced.getBody()).thenReturn("참조된 제41조 내용");
        when(referenced.getSequence()).thenReturn(5);
        when(referenced.getArticleReference()).thenReturn("제41조");
        when(referenced.getParentSection()).thenReturn("참조");
        when(referenced.getSourceDocument()).thenReturn(document);
        for (SourceChunk chunk : List.of(candidate, parent, exception, unrelated, referenced)) {
            when(chunk.getReviewStatus()).thenReturn(SourceChunkReviewStatus.APPROVED);
        }
        when(sourceChunkRepository.findAllBySourceDocument_IdOrderBySequenceAsc(documentId))
                .thenReturn(List.of(candidate, parent, exception, unrelated, referenced));

        OfficialSearchResponse response = service.search(new OfficialSearchRequest(
                "분실", "CARD", "KB국민카드", null, null, List.of(), 10));
        List<SourcePassage> passages = service.getPassages(
                response.searchId(), List.of(response.candidates().getFirst().candidateId()));

        assertThat(passages).hasSize(1);
        assertThat(passages.getFirst().sourceChunkIds())
                .containsExactly(parent.getId(), candidateChunkId, exception.getId(), referencedChunkId);
        assertThat(passages.getFirst().text())
                .contains("제40조의 부모 내용", "분실 조항 본문", "다만 예외가 적용된다", "참조된 제41조 내용")
                .doesNotContain("다른 조항");
    }
}
