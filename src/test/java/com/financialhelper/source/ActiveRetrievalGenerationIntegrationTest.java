package com.financialhelper.source;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
class ActiveRetrievalGenerationIntegrationTest {

    private final Set<UUID> ownedGenerationIds = new HashSet<>();
    private UUID originalActiveGenerationId;
    private String fixtureToken;

    @Autowired
    private RetrievalGenerationRepository generationRepository;

    @Autowired
    private ActiveRetrievalGenerationPersistenceService activeService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void captureActiveGeneration() {
        fixtureToken = UUID.randomUUID().toString();
        List<UUID> activeIds = jdbcTemplate.query(
                "SELECT retrieval_generation_id FROM active_retrieval_generation WHERE singleton_key = TRUE",
                (resultSet, rowNumber) -> resultSet.getObject(1, UUID.class)
        );
        originalActiveGenerationId = activeIds.isEmpty() ? null : activeIds.getFirst();
    }

    @AfterEach
    void cleanupOwnedRows() {
        // 이 test가 만든 row만 제거하기 전에 production과 유사한 pointer를 복원한다.
        // 이 test가 만든 generation만 제거한다.
        if (originalActiveGenerationId == null) {
            jdbcTemplate.update("DELETE FROM active_retrieval_generation");
        } else {
            jdbcTemplate.update(
                    "UPDATE active_retrieval_generation SET retrieval_generation_id = ?, switched_at = CURRENT_TIMESTAMP "
                            + "WHERE singleton_key = TRUE",
                    originalActiveGenerationId
            );
        }
        for (UUID generationId : ownedGenerationIds) {
            jdbcTemplate.update("DELETE FROM retrieval_generation WHERE id = ?", generationId);
        }
        ownedGenerationIds.clear();
    }

    @Test
    void onlyReadyGenerationCanBeActivatedAndPointerIsSingleton() {
        RetrievalGeneration pending = generationRepository.saveAndFlush(
                new RetrievalGeneration(definition("pending"), now())
        );
        ownedGenerationIds.add(pending.getId());
        assertThatThrownBy(() -> activeService.activate(pending.getId()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("READY");

        RetrievalGeneration first = ready("first");
        RetrievalGeneration second = ready("second");

        activeService.activate(first.getId());
        assertThat(activeService.current())
                .get()
                .extracting(RetrievalGeneration::getId)
                .isEqualTo(first.getId());

        activeService.activate(second.getId());
        assertThat(activeService.current())
                .get()
                .extracting(RetrievalGeneration::getId)
                .isEqualTo(second.getId());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM active_retrieval_generation",
                Integer.class
        )).isOne();
    }

    private RetrievalGeneration ready(String key) {
        RetrievalGeneration generation = generationRepository.saveAndFlush(
                new RetrievalGeneration(definition(key), now())
        );
        ownedGenerationIds.add(generation.getId());
        generation.markProcessing(now());
        generation.markReady(1, now());
        return generationRepository.saveAndFlush(generation);
    }

    private RetrievalGenerationData.Definition definition(String key) {
        return new RetrievalGenerationData.Definition(
                "test-active-" + fixtureToken + "-" + key,
                "kure-v2-plaid-v1",
                "nlpai-lab/KURE-v2",
                "3431f86d399d666083890dbb882aced6708873bc",
                "nlpai-lab/KURE-v2",
                "3431f86d399d666083890dbb882aced6708873bc",
                "{\"queryLength\":64,\"documentMaxTokens\":8192,\"doQueryExpansion\":true,\"queryPrefix\":\"\",\"documentPrefix\":\"\"}",
                "{\"backend\":\"PLAID\"}",
                "{}",
                "structure-first-v1",
                null
        );
    }

    private static OffsetDateTime now() {
        return OffsetDateTime.now(ZoneOffset.UTC);
    }
}
