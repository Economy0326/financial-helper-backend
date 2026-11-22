package com.financialhelper.source;

import com.financialhelper.account.AccountProvider;
import com.financialhelper.account.AccountSessionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = "app.source-url-verification-records.enabled=true")
class SourceUrlVerificationRecordIntegrationTest {
    private boolean draftSchemaInstalled;
    @Autowired JdbcTemplate jdbc;
    @Autowired SourceRegistryRepository registries;
    @Autowired SourceDocumentRepository documents;
    @Autowired SourceDocumentWriter writer;
    @Autowired AccountSessionService sessions;
    @Autowired SourceUrlVerificationRecordService records;
    @MockitoBean OfficialSourceVerificationService verifier;
    @MockitoBean SourceReviewerService authority;

    @Test void storesSuccessAndFailureAsImmutableVersionBoundRecordsWithoutNetworkTransaction() throws Exception {
        jdbc.execute(Files.readString(Path.of("docs/drafts/source-url-verification-schema.sql")));
        draftSchemaInstalled = true;
        var registry = registries.findBySourceKeyAndEnabledTrue("fsc-card-use-qa").orElseThrow();
        String body = "isolated fixture";
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        var now = OffsetDateTime.now(ZoneOffset.UTC);
        var parsed = new SourceIngestionData.Parsed(registry.getCanonicalUrl(), "fixture", null, now,
                "text/plain;charset=UTF-8", null, null, SourceHashing.sha256(bytes), SourceHashing.sha256(body), bytes, body);
        writer.saveIfCurrent(SourceIngestionData.Snapshot.from(registry), parsed);
        var doc = documents.findBySourceRegistry_IdAndStatus(registry.getId(), SourceDocumentStatus.ACTIVE).orElseThrow();
        var login = sessions.createSession(AccountProvider.NAVER, "isolated-" + UUID.randomUUID(), "fixture");
        when(authority.requireReviewer()).thenReturn(login.account().getId());
        when(verifier.verify(anyString(), any())).thenAnswer(inv -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return new OfficialSourceVerificationService.Result(doc.getId(), doc.getDocumentVersion(), doc.getRawSha256(),
                    registry.getCanonicalUrl(), registry.getCanonicalUrl(), now, 200, true,
                    doc.getRawSha256(), doc.getContentSha256(), "HTML_RAW_AND_NORMALIZED_SHA256", "RAW_BYTES_MATCH", false, true, true);
        });
        UUID ok = records.verifyAndRecord(registry.getSourceKey(), doc.getId());
        assertThat(jdbc.queryForObject("SELECT stored_content_sha256 FROM source_url_verification_attempt WHERE id=?", String.class, ok))
                .isEqualTo(doc.getContentSha256());
        assertThatThrownBy(() -> jdbc.update("UPDATE source_url_verification_attempt SET result='CHANGED' WHERE id=?", ok))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        when(verifier.verify(anyString(), any())).thenReturn(new OfficialSourceVerificationService.Result(
                doc.getId(), doc.getDocumentVersion(), doc.getRawSha256(), registry.getCanonicalUrl(), "https://unofficial.invalid/?token=secret", now,
                null, false, null, null, "NOT_VERIFIED", "SOURCE_REQUEST_FAILED", false, true, false));
        UUID failed = records.verifyAndRecord(registry.getSourceKey(), doc.getId());
        assertThat(jdbc.queryForObject("SELECT failure_code FROM source_url_verification_attempt WHERE id=?", String.class, failed))
                .isEqualTo("SOURCE_REQUEST_FAILED");
        assertThat(jdbc.queryForObject("SELECT final_url FROM source_url_verification_attempt WHERE id=?", String.class, failed)).isNull();
    }

    @org.junit.jupiter.api.AfterEach
    void removeDraftSchema() {
        if (!draftSchemaInstalled) return;
        jdbc.execute("DROP TABLE source_current_guidance_basis, source_review_search_window, source_review_verification_link, source_url_verification_attempt");
        jdbc.execute("ALTER TABLE source_review_decision_audit DROP CONSTRAINT uq_audit_document_hash, DROP CONSTRAINT uq_audit_review_decision");
        jdbc.execute("DROP FUNCTION reject_source_verification_mutation()");
    }
}
