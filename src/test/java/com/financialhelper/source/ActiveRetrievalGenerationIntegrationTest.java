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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
class ActiveRetrievalGenerationIntegrationTest {

    @Autowired
    private RetrievalGenerationRepository generationRepository;

    @Autowired
    private ActiveRetrievalGenerationPersistenceService activeService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    @AfterEach
    void cleanRows() {
        jdbcTemplate.update("DELETE FROM active_retrieval_generation");
        jdbcTemplate.update("DELETE FROM retrieval_generation");
    }

    @Test
    void onlyReadyGenerationCanBeActivatedAndPointerIsSingleton() {
        RetrievalGeneration pending = generationRepository.saveAndFlush(
                new RetrievalGeneration(definition("pending"), now())
        );
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
        generation.markProcessing(now());
        generation.markReady(1, now());
        return generationRepository.saveAndFlush(generation);
    }

    private RetrievalGenerationData.Definition definition(String key) {
        return new RetrievalGenerationData.Definition(
                key,
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
