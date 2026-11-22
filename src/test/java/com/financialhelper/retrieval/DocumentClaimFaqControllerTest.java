package com.financialhelper.retrieval;

import com.financialhelper.source.SourceReviewerService;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.web.server.ResponseStatusException;
import java.util.List;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DocumentClaimFaqControllerTest {
    @Test void reviewerCanUnpublishEvenIfEvidenceIsUnavailable() {
        var jdbc = mock(JdbcTemplate.class);
        var reviewer = mock(SourceReviewerService.class);
        var snapshots = mock(DocumentClaimSnapshotService.class);
        when(reviewer.requireReviewer()).thenReturn(UUID.randomUUID());
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
        new DocumentClaimFaqController(jdbc, reviewer, snapshots).unpublish(UUID.randomUUID());
        verifyNoInteractions(snapshots);
    }
    @Test void cannotPublishWhenAnyLinkedClaimIsIneligible() {
        var jdbc = mock(JdbcTemplate.class);
        var reviewer = mock(SourceReviewerService.class);
        var snapshots = mock(DocumentClaimSnapshotService.class);
        when(reviewer.requireReviewer()).thenReturn(UUID.randomUUID());
        var item = new DocumentClaimSnapshotService.Evidence("first", UUID.randomUUID(), 1, "합성 근거", "기관", "제목", "https://example.invalid", "위치", null, "NOT_VERIFIED", List.of());
        when(snapshots.currentClaimEvidence("first")).thenReturn(List.of(item));
        when(snapshots.currentClaimEvidence("second")).thenReturn(List.of());
        var faq = new DocumentClaimFaqController(jdbc, reviewer, snapshots);
        assertThatThrownBy(() -> faq.create(new DocumentClaimFaqController.Request("first", "질문", 0, true, true, List.of("first", "second"))))
                .isInstanceOf(ResponseStatusException.class);
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }
    @Test
    void cannotPublishAnIneligibleClaim() {
        var jdbc = mock(JdbcTemplate.class);
        var reviewer = mock(SourceReviewerService.class);
        var snapshots = mock(DocumentClaimSnapshotService.class);
        when(reviewer.requireReviewer()).thenReturn(UUID.randomUUID());
        when(snapshots.currentClaimEvidence("candidate")).thenReturn(List.of());
        var faq = new DocumentClaimFaqController(jdbc, reviewer, snapshots);
        assertThatThrownBy(() -> faq.create(new DocumentClaimFaqController.Request("candidate", "질문", 0, true, true)))
                .isInstanceOf(ResponseStatusException.class);
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }
}
