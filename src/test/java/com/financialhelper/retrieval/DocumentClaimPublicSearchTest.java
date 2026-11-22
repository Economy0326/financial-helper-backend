package com.financialhelper.retrieval;

import com.financialhelper.account.AccountProperties;
import com.financialhelper.account.AccountSessionService;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class DocumentClaimPublicSearchTest {
    @Test void questionDigestBindsSnapshotWithoutStoringQuestion() {
        UUID id = UUID.randomUUID();
        String question = "합성 금융 질문";
        String stored = DocumentClaimSnapshotService.questionDigest(id, question);
        assertThat(stored).doesNotContain(question);
        assertThat(DocumentClaimSnapshotService.matchesQuestion(id, stored, question)).isTrue();
        assertThat(DocumentClaimSnapshotService.matchesQuestion(id, stored, "다른 질문")).isFalse();
        assertThat(DocumentClaimSnapshotService.matchesQuestion(UUID.randomUUID(), stored, question)).isFalse();
        assertThat(DocumentClaimSnapshotService.matchesQuestion(id, question, question)).isTrue();
    }
    @Test void unavailableSearchDoesNotExposeInternalCandidates() {
        var snapshots = mock(DocumentClaimSnapshotService.class, CALLS_REAL_METHODS);
        var candidate = new OfficialEvidenceCandidate(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                1, null, null, 0.1, "합성 기관", "합성 제목", 1, null, null, null, null, "위치", "https://example.invalid", "승인된 발췌");
        var result = new OfficialSearchResponse(UUID.randomUUID(), RetrievalStatus.TEMPORARILY_UNAVAILABLE, true,
                List.of(), List.of("KEYWORD_BRANCH_UNAVAILABLE"), List.of(candidate));
        var response = snapshots.preview(result, "질문");
        assertThat(response.status()).isEqualTo("TEMPORARILY_UNAVAILABLE");
        assertThat(response.evidence()).isEmpty();
    }
    @Test void subjectThenPurposeReturnsOnlyMatchingRevalidatedCandidates() {
        var search = mock(OfficialSourceRetrievalService.class); var snapshots = mock(DocumentClaimSnapshotService.class);
        var accounts = mock(AccountSessionService.class); var properties = mock(AccountProperties.class); var limits = mock(AccountProperties.Limits.class);
        when(properties.limits()).thenReturn(limits); when(limits.maxSituationCharacters()).thenReturn(1000);
        when(accounts.currentAccountId()).thenReturn(Optional.empty());
        UUID doc = UUID.randomUUID(), generation = UUID.randomUUID();
        var first = new OfficialEvidenceCandidate(UUID.randomUUID(), UUID.randomUUID(), doc, generation, 1, null, null, 0.1, "기관", "대상", 1, null, null, null, null, "위치", "https://example.invalid", "앱에서 신청합니다.");
        var second = new OfficialEvidenceCandidate(UUID.randomUUID(), UUID.randomUUID(), doc, generation, 2, null, null, 0.1, "기관", "대상", 1, null, null, null, null, "위치", "https://example.invalid", "필요한 서류를 확인합니다.");
        when(search.searchDocumentClaims("질문")).thenReturn(new OfficialSearchResponse(UUID.randomUUID(), RetrievalStatus.NEEDS_CLARIFICATION, false, List.of(), List.of(), List.of(first, second)));
        when(snapshots.clarificationPurposes(any(), eq("질문"), eq("대상"))).thenReturn(new DocumentClaimSnapshotService.Response(null, "NEEDS_CLARIFICATION", List.of(), List.of(), List.of(
                new DocumentClaimSnapshotService.ClarificationOption("신청", "대상", "신청", null), new DocumentClaimSnapshotService.ClarificationOption("필요 서류", "대상", "필요 서류", null))));
        when(snapshots.preview(any(), eq("질문"))).thenAnswer(call -> { OfficialSearchResponse r = call.getArgument(0); assertThat(r.candidates()).containsExactly(first); return new DocumentClaimSnapshotService.Response(null, "READY_CANDIDATES", List.of()); });
        var controller = new DocumentClaimSnapshotController(search, snapshots, accounts, properties, mock(DocumentClaimExplanationService.class), mock(DocumentAiExplanationQuota.class));
        assertThat(controller.search(new DocumentClaimSnapshotController.Request("질문", new DocumentClaimSnapshotController.Selection("대상", null, null)), new MockHttpServletResponse()).options()).hasSize(2);
        assertThat(controller.search(new DocumentClaimSnapshotController.Request("질문", new DocumentClaimSnapshotController.Selection("대상", null, "신청")), new MockHttpServletResponse()).status()).isEqualTo("READY_CANDIDATES");
    }
    @Test void selectedOptionMustMatchNewlyRetrievedCandidate() {
        var search = mock(OfficialSourceRetrievalService.class);
        var snapshots = mock(DocumentClaimSnapshotService.class);
        var accounts = mock(AccountSessionService.class);
        var properties = mock(AccountProperties.class);
        var limits = mock(AccountProperties.Limits.class);
        when(properties.limits()).thenReturn(limits); when(limits.maxSituationCharacters()).thenReturn(1000);
        when(accounts.currentAccountId()).thenReturn(Optional.empty());
        var candidate = new OfficialEvidenceCandidate(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                1, null, null, 0.1, "기관", "제목", 1, null, null, null, null, "위치", "https://example.invalid", "승인된 발췌");
        when(search.searchDocumentClaims("질문")).thenReturn(new OfficialSearchResponse(UUID.randomUUID(), RetrievalStatus.NEEDS_CLARIFICATION, false, List.of(), List.of(), List.of(candidate)));
        when(snapshots.preview(any(), eq("질문"))).thenAnswer(call -> {
            OfficialSearchResponse result = call.getArgument(0);
            return new DocumentClaimSnapshotService.Response(null, result.status().name(), List.of());
        });
        var controller = new DocumentClaimSnapshotController(search, snapshots, accounts, properties, mock(DocumentClaimExplanationService.class), mock(DocumentAiExplanationQuota.class));
        assertThat(controller.search(new DocumentClaimSnapshotController.Request("질문", new DocumentClaimSnapshotController.Selection("제목", "승인된 발췌")), new MockHttpServletResponse()).status()).isEqualTo("FOUND");
        assertThat(controller.search(new DocumentClaimSnapshotController.Request("질문", new DocumentClaimSnapshotController.Selection("제목", "변조된 발췌")), new MockHttpServletResponse()).status()).isEqualTo("NO_MATCH");
        when(search.searchDocumentClaims("질문")).thenReturn(new OfficialSearchResponse(UUID.randomUUID(), RetrievalStatus.TEMPORARILY_UNAVAILABLE, true, List.of(), List.of(), List.of()));
        assertThat(controller.search(new DocumentClaimSnapshotController.Request("질문", new DocumentClaimSnapshotController.Selection("제목", "승인된 발췌")), new MockHttpServletResponse()).status()).isEqualTo("TEMPORARILY_UNAVAILABLE");
    }
    @Test void clarificationUsesRevalidatedEvidenceWithoutSaving() {
        var snapshots = mock(DocumentClaimSnapshotService.class, CALLS_REAL_METHODS);
        var candidate = new OfficialEvidenceCandidate(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                1, null, null, 0.1, "합성 기관", "합성 제목", 1, null, null, null, null, "위치", "https://example.invalid", "원문");
        var result = new OfficialSearchResponse(UUID.randomUUID(), RetrievalStatus.NEEDS_CLARIFICATION, false, List.of(), List.of(), List.of(candidate));
        var evidence = new DocumentClaimSnapshotService.Evidence("fixture", candidate.sourceDocumentId(), 1, "신청할 수 있습니다.", "합성 기관", "합성 제목", "https://example.invalid", "위치", null, "NOT_VERIFIED", List.of());
        doReturn(new DocumentClaimSnapshotService.Response(null, "READY_CANDIDATES", List.of(evidence))).when(snapshots).preview(any(), eq("질문"));
        var output = snapshots.clarification(result, "질문");
        assertThat(output.status()).isEqualTo("NEEDS_CLARIFICATION");
        assertThat(output.evidence()).isEmpty();
        assertThat(output.options()).extracting(DocumentClaimSnapshotService.ClarificationOption::label).containsExactly("합성 제목");
        assertThat(output.options().getFirst().excerpt()).isNull();
        verify(snapshots, never()).save(any(), any());
    }
    @Test void clarificationWithoutAnswerableOptionsReturnsNoMatch() {
        var snapshots = mock(DocumentClaimSnapshotService.class, CALLS_REAL_METHODS);
        var evidence = new DocumentClaimSnapshotService.Evidence("fixture", UUID.randomUUID(), 1, "단순 소개 문장입니다.", "기관", "제목", "https://example.invalid", "위치", null, "NOT_VERIFIED", List.of());
        doReturn(new DocumentClaimSnapshotService.Response(null, "READY_CANDIDATES", List.of(evidence))).when(snapshots).preview(any(), eq("질문"));
        var result = new OfficialSearchResponse(UUID.randomUUID(), RetrievalStatus.NEEDS_CLARIFICATION, false, List.of(), List.of(), List.of());
        assertThat(snapshots.clarification(result, "질문").status()).isEqualTo("NO_MATCH");
        assertThat(snapshots.clarificationPurposes(result, "질문", "제목").status()).isEqualTo("NO_MATCH");
    }
    @Test void guestUsesSameRetrievalAndNeverSavesSnapshot() {
        var search = mock(OfficialSourceRetrievalService.class);
        var snapshots = mock(DocumentClaimSnapshotService.class);
        var accounts = mock(AccountSessionService.class);
        var properties = mock(AccountProperties.class);
        var limits = mock(AccountProperties.Limits.class);
        when(properties.limits()).thenReturn(limits);
        when(limits.maxSituationCharacters()).thenReturn(1000);
        when(accounts.currentAccountId()).thenReturn(Optional.empty());
        var result = new OfficialSearchResponse(UUID.randomUUID(), RetrievalStatus.NO_MATCH, false, List.of(), List.of(), List.of());
        when(search.searchDocumentClaims("합성 질문")).thenReturn(result);
        var expected = new DocumentClaimSnapshotService.Response(null, "NO_MATCH", List.of());
        when(snapshots.preview(result, "합성 질문")).thenReturn(expected);
        var controller = new DocumentClaimSnapshotController(search, snapshots, accounts, properties, mock(DocumentClaimExplanationService.class), mock(DocumentAiExplanationQuota.class));
        var response = new MockHttpServletResponse();
        assertThat(controller.search(new DocumentClaimSnapshotController.Request("합성 질문"), response)).isEqualTo(expected);
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
        verify(snapshots, never()).save(any(), any());
    }
}
