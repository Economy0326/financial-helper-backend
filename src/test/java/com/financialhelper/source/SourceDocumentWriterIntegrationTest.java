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

import static org.assertj.core.api.Assertions
        .assertThat;

import static org.assertj.core.api.Assertions
        .assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
class SourceDocumentWriterIntegrationTest {

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
    void cleanDocuments() {

        sourceDocumentRepository
                .deleteAll();
    }

    // 정규화 본문이 같으면 새 버전을 만들지 않고 기존 버전을 재사용하는지 확인
    @Test
    void sameNormalizedContentReusesCurrentVersion() {

        SourceRegistry source =
                sourceRegistryRepository
                        .findBySourceKeyAndEnabledTrue(
                                "fsc-2026-alert"
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
                sourceDocumentRepository.count()
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
                                "police-response"
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

    // fetch 도중 content selector가 변경되면 오래된 Snapshot을 저장하지 않는지 확인
    @Test
    void changedContentSelectorRejectsStaleSnapshot() {

        SourceRegistry source =
                sourceRegistryRepository
                        .findBySourceKeyAndEnabledTrue(
                                "fsc-2026-alert"
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
                    sourceDocumentRepository.count()
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

}