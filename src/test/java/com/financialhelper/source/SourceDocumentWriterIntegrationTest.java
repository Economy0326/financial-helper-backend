package com.financialhelper.source;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions
        .assertThat;

import static org.assertj.core.api.Assertions
        .assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
class SourceDocumentWriterIntegrationTest {

    private String fixtureSourceKey;
    private UUID fixtureSourceId;

    @Autowired
    private SourceRegistryRepository
            sourceRegistryRepository;

    @Autowired
    private SourceDocumentRepository
            sourceDocumentRepository;

    @Autowired
    private SourceDocumentWriter
            sourceDocumentWriter;

    @Autowired
    private JdbcTemplate
            jdbcTemplate;

    @BeforeEach
    void createFixture() {
        fixtureSourceKey = "test-writer-" + UUID.randomUUID();
        fixtureSourceId = UUID.randomUUID();
        jdbcTemplate.update(
                """
                INSERT INTO source_registry (
                    id, source_key, organization_name, official_domain,
                    canonical_url, acquisition_type, enabled, created_at, updated_at
                ) VALUES (?, ?, '금융위원회', 'fsc.go.kr', ?, 'HTML', TRUE,
                    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """,
                fixtureSourceId,
                fixtureSourceKey,
                "https://fsc.go.kr/test-writer/" + fixtureSourceId
        );
    }

    @org.junit.jupiter.api.AfterEach
    void deleteFixture() {
        jdbcTemplate.update(
                "DELETE FROM source_chunk_indexing WHERE source_chunk_id IN "
                        + "(SELECT id FROM source_chunk WHERE source_document_id IN "
                        + "(SELECT id FROM source_document WHERE source_registry_id = ?))",
                fixtureSourceId
        );
        jdbcTemplate.update(
                "DELETE FROM source_chunk WHERE source_document_id IN "
                        + "(SELECT id FROM source_document WHERE source_registry_id = ?)",
                fixtureSourceId
        );
        jdbcTemplate.update("DELETE FROM source_document WHERE source_registry_id = ?", fixtureSourceId);
        jdbcTemplate.update("DELETE FROM source_registry WHERE id = ?", fixtureSourceId);
    }

    // 정규화 본문이 같으면 새 버전을 만들지 않고 기존 버전을 재사용하는지 확인
    @Test
    void sameNormalizedContentReusesCurrentVersion() {

        SourceRegistry source =
                sourceRegistryRepository
                        .findBySourceKeyAndEnabledTrue(fixtureSourceKey)
                        .orElseThrow();

        SourceIngestionData.Snapshot snapshot =
                SourceIngestionData
                        .Snapshot
                        .from(source);

        OffsetDateTime firstTime =
                OffsetDateTime.of(
                        2026,
                        9,
                        11,
                        1,
                        0,
                        0,
                        0,
                        ZoneOffset.UTC
                );

        SourceIngestionResult first =
                sourceDocumentWriter
                        .saveIfCurrent(
                                snapshot,

                                parsed(
                                        source,
                                        "first raw html",
                                        "같은 공식 본문 내용입니다.",
                                        firstTime
                                )
                        );

        OffsetDateTime secondTime =
                firstTime.plusHours(1);

        SourceIngestionResult second =
                sourceDocumentWriter
                        .saveIfCurrent(
                                snapshot,

                                parsed(
                                        source,
                                        "different markup but same text",
                                        "같은 공식 본문 내용입니다.",
                                        secondTime
                                )
                        );

        assertThat(
                first.outcome()
        )
                .isEqualTo(
                        SourceIngestionResult
                                .Outcome
                                .CREATED
                );

        assertThat(
                second.outcome()
        )
                .isEqualTo(
                        SourceIngestionResult
                                .Outcome
                                .UNCHANGED
                );

        assertThat(
                sourceDocumentRepository
                        .findAllBySourceRegistry_IdOrderByDocumentVersionAsc(source.getId())
                        .size()
        )
                .isEqualTo(1);

        SourceDocument active =
                sourceDocumentRepository
                        .findBySourceRegistry_IdAndStatus(
                                source.getId(),
                                SourceDocumentStatus.ACTIVE
                        )
                        .orElseThrow();

        assertThat(
                active.getDocumentVersion()
        )
                .isEqualTo(1);

        assertThat(
                active.getLastCheckedAt()
        )
                .isEqualTo(
                        secondTime
                );
    }

    // 정규화 본문이 변경되면 기존 버전을 종료하고 새 버전을 생성하는지 확인
    @Test
    void changedNormalizedContentCreatesNextVersion() {

        SourceRegistry source =
                sourceRegistryRepository
                        .findBySourceKeyAndEnabledTrue(
                                fixtureSourceKey
                        )
                        .orElseThrow();

        SourceIngestionData.Snapshot snapshot =
                SourceIngestionData
                        .Snapshot
                        .from(source);

        OffsetDateTime firstTime =
                OffsetDateTime.of(
                        2026,
                        9,
                        11,
                        2,
                        0,
                        0,
                        0,
                        ZoneOffset.UTC
                );

        sourceDocumentWriter
                .saveIfCurrent(
                        snapshot,

                        parsed(
                                source,
                                "raw-v1",
                                "공식 본문 버전 1",
                                firstTime
                        )
                );

        SourceIngestionResult updated =
                sourceDocumentWriter
                        .saveIfCurrent(
                                snapshot,

                                parsed(
                                        source,
                                        "raw-v2",
                                        "공식 본문 버전 2",
                                        firstTime.plusHours(1)
                                )
                        );

        assertThat(
                updated.outcome()
        )
                .isEqualTo(
                        SourceIngestionResult
                                .Outcome
                                .UPDATED
                );

        assertThat(
                updated.documentVersion()
        )
                .isEqualTo(2);

        List<SourceDocument> versions =
                sourceDocumentRepository
                        .findAllBySourceRegistry_IdOrderByDocumentVersionAsc(
                                source.getId()
                        );

        assertThat(
                versions
        )
                .hasSize(2);

        assertThat(
                versions.get(0)
                        .getStatus()
        )
                .isEqualTo(
                        SourceDocumentStatus
                                .SUPERSEDED
                );

        assertThat(
                versions.get(1)
                        .getStatus()
        )
                .isEqualTo(
                        SourceDocumentStatus
                                .ACTIVE
                );
    }

    @Test
    void changedPdfRawBytesCreateNewVersionWithoutInheritingSyntheticReview() {
        jdbcTemplate.update("UPDATE source_registry SET acquisition_type = 'PDF' WHERE id = ?",
                fixtureSourceId);
        SourceRegistry source = sourceRegistryRepository
                .findBySourceKeyAndEnabledTrue(fixtureSourceKey).orElseThrow();
        var snapshot = SourceIngestionData.Snapshot.from(source);
        OffsetDateTime firstTime = OffsetDateTime.of(2026, 9, 11, 0, 0, 0, 0, ZoneOffset.UTC);
        var first = pdfParsed(source, "synthetic-pdf-raw-v1", "동일한 추출 본문", firstTime);
        sourceDocumentWriter.saveIfCurrent(snapshot, first);
        SourceDocument original = sourceDocumentRepository
                .findBySourceRegistry_IdAndStatus(fixtureSourceId, SourceDocumentStatus.ACTIVE)
                .orElseThrow();
        UUID reviewId = UUID.randomUUID();
        try {
            jdbcTemplate.update("""
                    INSERT INTO source_applicability_review (
                      id, source_document_id, reviewed_raw_sha256, scope_kind, scenario,
                      applicability_start_date, review_status, condition_required,
                      exception_required, conditions_reviewed, exceptions_reviewed,
                      reviewed_by, reviewed_at)
                    VALUES (?, ?, ?, 'COMMON', 'SYNTHETIC_TEST', ?, 'APPROVED',
                      FALSE, FALSE, TRUE, TRUE, 'isolated-test', CURRENT_TIMESTAMP)
                    """, reviewId, original.getId(), first.rawSha256(),
                    java.time.LocalDate.of(2025, 9, 1));

            var repeated = sourceDocumentWriter.saveIfCurrent(snapshot,
                    pdfParsed(source, "synthetic-pdf-raw-v1", "동일한 추출 본문",
                            firstTime.plusHours(1)));
            assertThat(repeated.outcome()).isEqualTo(SourceIngestionResult.Outcome.UNCHANGED);
            assertThat(repeated.documentVersion()).isEqualTo(1);

            var changed = sourceDocumentWriter.saveIfCurrent(snapshot,
                    pdfParsed(source, "synthetic-pdf-raw-v2", "동일한 추출 본문",
                            firstTime.plusHours(2)));
            assertThat(changed.outcome()).isEqualTo(SourceIngestionResult.Outcome.UPDATED);
            assertThat(changed.documentVersion()).isEqualTo(2);
            List<SourceDocument> versions = sourceDocumentRepository
                    .findAllBySourceRegistry_IdOrderByDocumentVersionAsc(fixtureSourceId);
            assertThat(versions).hasSize(2);
            assertThat(versions.get(0).getStatus()).isEqualTo(SourceDocumentStatus.SUPERSEDED);
            assertThat(versions.get(1).getStatus()).isEqualTo(SourceDocumentStatus.ACTIVE);
            assertThat(versions.get(1).getContentSha256()).isEqualTo(versions.get(0).getContentSha256());
            assertThat(versions.get(1).getRawSha256()).isNotEqualTo(versions.get(0).getRawSha256());
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM source_applicability_review WHERE source_document_id = ?",
                    Integer.class, versions.get(1).getId())).isZero();
            assertThat(jdbcTemplate.queryForObject("""
                    SELECT count(*) FROM source_applicability_review r
                    JOIN source_document d ON d.id = r.source_document_id
                    WHERE r.id = ? AND d.status = 'ACTIVE' AND d.raw_sha256 = r.reviewed_raw_sha256
                    """, Integer.class, reviewId)).isZero();
        } finally {
            jdbcTemplate.update("DELETE FROM source_applicability_review WHERE id = ?", reviewId);
        }
    }

    // fetch 도중 content selector가 변경되면 오래된 Snapshot을 저장하지 않는지 확인
    @Test
    void changedContentSelectorRejectsStaleSnapshot() {

        SourceRegistry source =
                sourceRegistryRepository
                        .findBySourceKeyAndEnabledTrue(
                                fixtureSourceKey
                        )
                        .orElseThrow();

        // fetch 시작 시점의 source 정의를 기억
        SourceIngestionData.Snapshot snapshot =
                SourceIngestionData
                        .Snapshot
                        .from(source);

        String originalSelector =
                source.getContentSelector();

        try {

            // fetch가 진행되는 동안 관리자가 source의 selector를
            // 변경했다고 가정
            jdbcTemplate.update(
                    """
                    UPDATE source_registry
                    SET
                        content_selector = ?,
                        updated_at = CURRENT_TIMESTAMP
                    WHERE id = ?
                    """,
                    ".changed-content-selector",
                    source.getId()
            );

            assertThatThrownBy(() ->
                    sourceDocumentWriter
                            .saveIfCurrent(
                                    snapshot,

                                    parsed(
                                            source,
                                            "raw-content",
                                            "공식 본문 내용입니다.",
                                            OffsetDateTime.of(
                                                    2026,
                                                    9,
                                                    11,
                                                    3,
                                                    0,
                                                    0,
                                                    0,
                                                    ZoneOffset.UTC
                                            )
                                    )
                            )
            )
                    .isInstanceOf(
                            SourceIngestionException.class
                    )

                    .hasMessageContaining(
                            "Source definition changed"
                    );

            // source 정의가 바뀌었기 때문에 문서는 저장되면 안 됨
            assertThat(
                    sourceDocumentRepository
                            .findAllBySourceRegistry_IdOrderByDocumentVersionAsc(source.getId())
                            .size()
            )
                    .isZero();

        } finally {

            // 다른 integration test에 영향을 주지 않도록 원래 값으로 복원
            jdbcTemplate.update(
                    """
                    UPDATE source_registry
                    SET
                        content_selector = ?,
                        updated_at = CURRENT_TIMESTAMP
                    WHERE id = ?
                    """,
                    originalSelector,
                    source.getId()
            );
        }
    }

    private SourceIngestionData.Parsed parsed(
            SourceRegistry source,
            String raw,
            String normalized,
            OffsetDateTime retrievedAt
    ) {

        byte[] rawBytes =
                raw.getBytes(
                        StandardCharsets.UTF_8
                );

        return new SourceIngestionData.Parsed(
                source.getCanonicalUrl(),
                source.getSourceKey(),
                null,
                retrievedAt,
                "text/html;charset=UTF-8",
                null,
                null,

                SourceHashing.sha256(
                        rawBytes
                ),

                SourceHashing.sha256(
                        normalized
                ),

                rawBytes,
                normalized
        );
    }

    private SourceIngestionData.Parsed pdfParsed(SourceRegistry source, String raw,
                                                   String normalized, OffsetDateTime retrievedAt) {
        byte[] bytes = raw.getBytes(StandardCharsets.UTF_8);
        return new SourceIngestionData.Parsed(source.getCanonicalUrl(), source.getSourceKey(),
                null, retrievedAt, "application/pdf", null, null,
                SourceHashing.sha256(bytes), SourceHashing.sha256(normalized), bytes, normalized);
    }

}
