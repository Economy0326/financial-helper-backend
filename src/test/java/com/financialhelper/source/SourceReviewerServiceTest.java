package com.financialhelper.source;

import com.financialhelper.account.AccountPrincipal;
import com.financialhelper.account.AccountProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.UUID;
import java.util.Optional;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SourceReviewerServiceTest {
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final SourceRegistryRepository registries = mock(SourceRegistryRepository.class);
    private final SourceDocumentRepository documents = mock(SourceDocumentRepository.class);
    private final SourceChunkRepository chunks = mock(SourceChunkRepository.class);
    private final SourceIngestionService ingestion = mock(SourceIngestionService.class);
    private final SourceChunkingService chunking = mock(SourceChunkingService.class);
    private final SourceReviewerService service = new SourceReviewerService(
            jdbc, registries, documents, chunks, ingestion, chunking,
            mock(SourceChunkingProperties.class), mock(RetrievalGenerationPersistenceService.class),
            mock(com.financialhelper.retrieval.RetrievalGenerationIndexingService.class),
            mock(com.financialhelper.retrieval.KureRuntimeProperties.class),
            mock(com.financialhelper.retrieval.GeneralTopicCoverageService.class));

    @AfterEach void clear() { SecurityContextHolder.clearContext(); }

    @Test void unauthenticatedRequestCannotInspectOrDecide() {
        assertThatThrownBy(service::requireReviewer).isInstanceOfSatisfying(SourceReviewException.class,
                exception -> assertThat(exception.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED));
        verifyNoInteractions(jdbc, registries, documents, chunks, ingestion, chunking);
    }

    @Test void authenticatedAccountWithoutExplicitGrantCannotIngest() {
        authenticated();
        when(jdbc.queryForObject(anyString(), eq(Boolean.class), any(UUID.class))).thenReturn(false);
        assertThatThrownBy(service::ingestDeposit).isInstanceOfSatisfying(SourceReviewException.class,
                exception -> assertThat(exception.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
        verifyNoInteractions(registries, documents, chunks, ingestion, chunking);
    }

    @Test void sourceInventoryRequiresGrantBeforeReadingRegistry() {
        authenticated();
        when(jdbc.queryForObject(anyString(), eq(Boolean.class), any(UUID.class))).thenReturn(false);
        assertThatThrownBy(service::sources).isInstanceOfSatisfying(SourceReviewException.class,
                exception -> assertThat(exception.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
        verifyNoInteractions(registries, documents);
    }

    @Test void grantAloneDoesNotPermitMissingOfficialRegistry() {
        authenticated();
        when(jdbc.queryForObject(anyString(), eq(Boolean.class), any(UUID.class))).thenReturn(true);
        assertThatThrownBy(service::ingestDeposit).isInstanceOfSatisfying(SourceReviewException.class,
                exception -> assertThat(exception.getCode()).isEqualTo("SOURCE_REVIEW_REGISTRY_MISSING"));
        verifyNoInteractions(ingestion);
    }

    @Test void documentViewSeparatesStoredBytesHashFromReviewedReference() {
        authenticated();
        when(jdbc.queryForObject(anyString(), eq(Boolean.class), any(UUID.class))).thenReturn(true);
        SourceRegistry registry = mock(SourceRegistry.class);
        UUID registryId = UUID.randomUUID();
        when(registry.getId()).thenReturn(registryId);
        when(registry.getCanonicalUrl()).thenReturn(VerifiedPdfMetadata.DEPOSIT_PDF_URL);
        when(registry.getOfficialDomain()).thenReturn("fsc.go.kr");
        when(registry.getOrganizationName()).thenReturn("금융위원회");
        when(registry.getAcquisitionType()).thenReturn(SourceAcquisitionType.PDF);
        when(registries.findBySourceKeyAndEnabledTrue(anyString())).thenReturn(Optional.of(registry));
        SourceDocument document = mock(SourceDocument.class);
        byte[] bytes = "synthetic fixture".getBytes(StandardCharsets.UTF_8);
        when(document.getOriginalContent()).thenReturn(bytes);
        when(document.getRawSha256()).thenReturn(SourceHashing.sha256(bytes));
        when(documents.findBySourceRegistry_IdAndStatus(registryId, SourceDocumentStatus.ACTIVE))
                .thenReturn(Optional.of(document));
        var view = service.currentDocument();
        assertThat(view.storedBytesMatchHash()).isTrue();
        assertThat(view.matchesReviewedHash()).isFalse();
    }

    private void authenticated() {
        var principal = new AccountPrincipal(UUID.randomUUID(), AccountProvider.KAKAO, "synthetic-reviewer-test");
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(principal, null, List.of()));
    }
}
