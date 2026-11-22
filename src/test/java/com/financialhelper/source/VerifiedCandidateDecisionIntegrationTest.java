package com.financialhelper.source;

import com.financialhelper.account.AccountProvider;
import com.financialhelper.account.AccountSessionCookie;
import com.financialhelper.account.AccountSessionService;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest @AutoConfigureMockMvc @ActiveProfiles("test")
@TestPropertySource(properties = "app.source-url-verification-records.enabled=true")
class VerifiedCandidateDecisionIntegrationTest {
    private boolean draftSchemaInstalled;
    @Autowired JdbcTemplate jdbc;
    @Autowired SourceRegistryRepository registries;
    @Autowired SourceDocumentRepository documents;
    @Autowired SourceDocumentWriter writer;
    @Autowired SourceChunkConfigurationRepository configurations;
    @Autowired SourceChunkRepository chunks;
    @Autowired AccountSessionService sessions;
    @Autowired MockMvc mvc;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactionManager;
    @MockitoBean OfficialSourceVerificationService verifier;

    @Test void firstDecisionLinksVerifiedEvidenceAndRetriesWithoutDuplicateAudit() throws Exception {
        jdbc.execute(Files.readString(Path.of("docs/drafts/source-url-verification-schema.sql")));
        draftSchemaInstalled = true;
        var registry = registries.findBySourceKeyAndEnabledTrue("fsc-card-use-qa").orElseThrow();
        String text = "Q1) 국내에서 카드를 도난, 분실시 조치방법은 무엇인지?\n"
                + "카드를 분실하거나 도난당한 경우 2025년 9월 1일 시행\n해당 카드회사에 즉시 신고해야 합니다.\nQ2) 리볼빙";
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        var now = OffsetDateTime.now(ZoneOffset.UTC);
        var parsed = new SourceIngestionData.Parsed(registry.getCanonicalUrl(), "fixture", null, now,
                "text/plain;charset=UTF-8", null, null, SourceHashing.sha256(bytes), SourceHashing.sha256(text), bytes, text);
        writer.saveIfCurrent(SourceIngestionData.Snapshot.from(registry), parsed);
        var doc = documents.findBySourceRegistry_IdAndStatus(registry.getId(), SourceDocumentStatus.ACTIVE).orElseThrow();
        var config = configurations.saveAndFlush(new SourceChunkConfiguration("fixture-" + UUID.randomUUID(), "{}", now));
        var definition = OfficialInformationClaimSelector.select(doc, config.getConfigVersion(), "{}").getFirst();
        var chunk = chunks.saveAndFlush(new SourceChunk(doc, config, definition, now));
        var login = sessions.createSession(AccountProvider.NAVER, "isolated-" + UUID.randomUUID(), "fixture");
        Cookie cookie = new Cookie(AccountSessionCookie.NAME, login.rawToken());
        jdbc.update("INSERT INTO source_reviewer_grant(account_id,granted_by) VALUES (?, 'fixture')", login.account().getId());
        when(verifier.verify(anyString(), any())).thenReturn(new OfficialSourceVerificationService.Result(
                doc.getId(), doc.getDocumentVersion(), doc.getRawSha256(), registry.getCanonicalUrl(), registry.getCanonicalUrl(),
                now, 200, true, doc.getRawSha256(), doc.getContentSha256(), "HTML_RAW_AND_NORMALIZED_SHA256",
                "RAW_BYTES_MATCH", false, true, true));
        String path = "/api/v1/source-review/information/fsc-card-use-qa/candidate-decision";
        String request = """
                {"documentId":"%s","chunkId":"%s","expectedRawSha256":"%s",
                 "temporalPolicy":"EXPLICIT_EFFECTIVE","applicabilityStartDate":"2025-09-01",
                 "conditionsReviewed":true,"exceptionsReviewed":true,"decision":"APPROVE"}
                """.formatted(doc.getId(), chunk.getId(), doc.getRawSha256());
        mvc.perform(post(path).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isForbidden());
        mvc.perform(post(path).cookie(cookie).contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isForbidden());
        when(verifier.verify(anyString(), any())).thenReturn(new OfficialSourceVerificationService.Result(
                doc.getId(), doc.getDocumentVersion(), doc.getRawSha256(), registry.getCanonicalUrl(), null,
                now, null, false, null, null, "NOT_VERIFIED", "SOURCE_REQUEST_FAILED", false, true, false));
        mvc.perform(post(path).cookie(cookie).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM source_applicability_review WHERE source_document_id=?", Integer.class, doc.getId())).isZero();
        when(verifier.verify(anyString(), any())).thenReturn(new OfficialSourceVerificationService.Result(
                doc.getId(), doc.getDocumentVersion(), doc.getRawSha256(), registry.getCanonicalUrl(), registry.getCanonicalUrl(),
                now, 200, true, doc.getRawSha256(), doc.getContentSha256(), "HTML_RAW_AND_NORMALIZED_SHA256",
                "RAW_BYTES_MATCH", false, true, true));
        String currentGuidance = """
                {"documentId":"%s","chunkId":"%s","expectedRawSha256":"%s",
                 "temporalPolicy":"CURRENT_GUIDANCE","temporalVerifiedOn":"%s","temporalValidUntil":"%s",
                 "conditionsReviewed":true,"exceptionsReviewed":true,"decision":"APPROVE"}
                """.formatted(doc.getId(), chunk.getId(), doc.getRawSha256(),
                now.atZoneSameInstant(java.time.ZoneId.of("Asia/Seoul")).toLocalDate(),
                now.atZoneSameInstant(java.time.ZoneId.of("Asia/Seoul")).toLocalDate());
        mvc.perform(post(path).cookie(cookie).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(currentGuidance))
                .andExpect(status().isBadRequest())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.error.code")
                        .value("SOURCE_REVIEW_CURRENTNESS_UNVERIFIED"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM source_applicability_review WHERE source_document_id=?", Integer.class, doc.getId())).isZero();
        mvc.perform(post(path).cookie(cookie).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM source_review_verification_link", Integer.class)).isEqualTo(1);
        UUID reviewId = jdbc.queryForObject("SELECT review_id FROM source_review_decision_audit WHERE source_document_id=?", UUID.class, doc.getId());
        UUID auditId = jdbc.queryForObject("SELECT audit_id FROM source_review_verification_link", UUID.class);
        var boundary = java.time.Instant.parse("2030-01-01T00:00:00Z");
        var before = new com.financialhelper.retrieval.SearchAvailabilityWindowGate(jdbc, true,
                java.time.Clock.fixed(boundary.minusNanos(1000), ZoneOffset.UTC));
        assertThat(before.allows(reviewId)).isFalse(); // New contract without independently authorized window.
        jdbc.update("INSERT INTO source_review_search_window(audit_id,valid_from,valid_until) VALUES (?,?,?)", auditId,
                OffsetDateTime.ofInstant(boundary.minusSeconds(10), ZoneOffset.UTC),
                OffsetDateTime.ofInstant(boundary, ZoneOffset.UTC));
        assertThat(before.allows(reviewId)).isTrue();
        assertThat(new com.financialhelper.retrieval.SearchAvailabilityWindowGate(jdbc, false,
                java.time.Clock.fixed(boundary.minusSeconds(1), ZoneOffset.UTC)).allows(reviewId)).isFalse();
        assertThat(new com.financialhelper.retrieval.SearchAvailabilityWindowGate(jdbc, true,
                java.time.Clock.fixed(boundary, ZoneOffset.UTC)).allows(reviewId)).isFalse();
        assertThat(new com.financialhelper.retrieval.SearchAvailabilityWindowGate(jdbc, true,
                java.time.Clock.fixed(boundary.plusSeconds(1), ZoneOffset.UTC)).allows(reviewId)).isFalse();
        assertThat(before.allows(UUID.randomUUID())).isTrue(); // A legacy review has no linked verification.
        var candidate = new com.financialhelper.retrieval.RetrievalCorpusSnapshotService.EligibleChunk(
                chunk.getId(), chunk.getBody(), doc.getId(), doc.getDocumentVersion(), null, null, "{}",
                registry.getOrganizationName(), doc.getTitle(), chunk.getParentSection(), null,
                chunk.getPageReference(), chunk.getLocator(), registry.getCanonicalUrl());
        var beforeReviews = new com.financialhelper.retrieval.ReviewedSourceApplicabilityService(jdbc, before);
        var expiredReviews = new com.financialhelper.retrieval.ReviewedSourceApplicabilityService(jdbc,
                new com.financialhelper.retrieval.SearchAvailabilityWindowGate(jdbc, true,
                        java.time.Clock.fixed(boundary, ZoneOffset.UTC)));
        var asOf = java.time.LocalDate.of(2026, 11, 14);
        assertThat(beforeReviews.eligibleTopic(com.financialhelper.consultation.FinancialTopic.CARD_PAYMENTS,
                asOf, null, null, java.util.List.of(candidate))).containsKey(chunk.getId());
        assertThat(expiredReviews.eligibleTopic(com.financialhelper.consultation.FinancialTopic.CARD_PAYMENTS,
                asOf, null, null, java.util.List.of(candidate))).doesNotContainKey(chunk.getId());
        var transaction = new org.springframework.transaction.support.TransactionTemplate(transactionManager);
        transaction.executeWithoutResult(status -> {
            jdbc.update("UPDATE source_applicability_review SET review_status='REJECTED' WHERE id=?", reviewId);
            assertThat(beforeReviews.eligibleTopic(com.financialhelper.consultation.FinancialTopic.CARD_PAYMENTS,
                    asOf, null, null, java.util.List.of(candidate))).isEmpty();
            status.setRollbackOnly();
        });
        transaction.executeWithoutResult(status -> {
            jdbc.update("""
                    UPDATE source_applicability_review SET review_status='PENDING', reviewed_by=NULL,
                           reviewed_at=NULL, conditions_reviewed=FALSE, exceptions_reviewed=FALSE WHERE id=?
                    """, reviewId);
            assertThat(beforeReviews.eligibleTopic(com.financialhelper.consultation.FinancialTopic.CARD_PAYMENTS,
                    asOf, null, null, java.util.List.of(candidate))).isEmpty();
            status.setRollbackOnly();
        });
        // A candidate discovered before expiry cannot be reassembled into a snapshot at expiry.
        UUID consultationId = UUID.randomUUID(), guestId = UUID.randomUUID(), generationId = UUID.randomUUID();
        var guestSessions = mock(com.financialhelper.guest.GuestSessionService.class);
        var guest = mock(com.financialhelper.guest.GuestSession.class);
        var consultationRepository = mock(com.financialhelper.consultation.ConsultationRepository.class);
        var consultation = mock(com.financialhelper.consultation.Consultation.class);
        var activeGenerations = mock(ActiveRetrievalGenerationPersistenceService.class);
        var generation = mock(RetrievalGeneration.class);
        var corpus = mock(com.financialhelper.retrieval.RetrievalCorpusSnapshotService.class);
        when(guestSessions.requireValidSession("fixture")).thenReturn(guest);
        when(guest.getId()).thenReturn(guestId);
        when(consultationRepository.findByIdAndGuestSession_Id(consultationId, guestId)).thenReturn(java.util.Optional.of(consultation));
        when(consultation.getSituationText()).thenReturn("카드를 분실했는데 신고 방법을 알려주세요");
        when(consultation.getConfirmedInformationTopic()).thenReturn(com.financialhelper.consultation.FinancialTopic.CARD_PAYMENTS);
        when(activeGenerations.current()).thenReturn(java.util.Optional.of(generation));
        when(generation.getId()).thenReturn(generationId);
        when(generation.getStatus()).thenReturn(RetrievalGenerationStatus.READY);
        when(corpus.readyChunks(generationId)).thenReturn(java.util.List.of(candidate));
        var discovered = new com.financialhelper.retrieval.ConsultationOfficialInformationResponse.Candidate(
                doc.getId(), doc.getDocumentVersion(), chunk.getId(), generationId, registry.getOrganizationName(),
                doc.getTitle(), registry.getCanonicalUrl(), null, chunk.getPageReference(), chunk.getLocator(),
                chunk.getBody(), "COMMON", reviewId, null, null, null, null, java.util.List.of(), java.util.List.of());
        var response = new com.financialhelper.retrieval.GeneralTopicInformationResponse(consultationId, 0,
                com.financialhelper.consultation.FinancialTopic.CARD_PAYMENTS,
                java.time.LocalDate.now(java.time.ZoneId.of("Asia/Seoul")), "READY_CANDIDATES", java.util.List.of(),
                java.util.List.of(discovered), com.financialhelper.retrieval.GeneralTopicInformationRequest.TimeScope.CURRENT, 0);
        var assembler = new com.financialhelper.ai.information.GeneralTopicEvidenceSnapshotService(guestSessions,
                consultationRepository, activeGenerations, corpus, expiredReviews, documents);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> assembler.assemble(consultationId, "fixture", response))
                .isInstanceOf(IllegalStateException.class).hasMessage("general topic evidence identity changed");
        mvc.perform(post(path).cookie(cookie).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM source_review_decision_audit WHERE source_document_id=?", Integer.class, doc.getId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM source_review_verification_link", Integer.class)).isEqualTo(1);
    }

    @org.junit.jupiter.api.AfterEach
    void removeDraftSchema() {
        if (!draftSchemaInstalled) return;
        jdbc.execute("DROP TABLE source_current_guidance_basis, source_review_search_window, source_review_verification_link, source_url_verification_attempt");
        jdbc.execute("ALTER TABLE source_review_decision_audit DROP CONSTRAINT uq_audit_document_hash, DROP CONSTRAINT uq_audit_review_decision");
        jdbc.execute("DROP FUNCTION reject_source_verification_mutation()");
    }
}
