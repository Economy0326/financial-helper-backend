package com.financialhelper.ai.information;

import com.financialhelper.ai.OpenAiStructuredClient;
import com.financialhelper.law.KoreanLawOpenApiClient;
import com.financialhelper.retrieval.KureRuntimeHttpClient;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import javax.sql.DataSource;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** The Spring isolation initializer validates the container before this DataSource is created. */
@SpringBootTest
@ActiveProfiles("test")
class InformationReportMigrationIntegrationTest {
    @Autowired DataSource dataSource;
    @MockitoBean OpenAiStructuredClient openAi;
    @MockitoBean KureRuntimeHttpClient kure;
    @MockitoBean KoreanLawOpenApiClient law;

    @Test
    void v39ReportSurvivesUpgradeToV40WithNullExplanation() {
        String schema = "upgrade_" + UUID.randomUUID().toString().replace("-", "");
        Flyway v39 = Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema)
                .target("39").load();
        try {
            v39.migrate();
            JdbcTemplate jdbc = new JdbcTemplate(dataSource);
            UUID guest = UUID.randomUUID(), consultation = UUID.randomUUID(), report = UUID.randomUUID();
            jdbc.update("INSERT INTO " + schema + ".guest_session (id, token_hash, created_at, expires_at) VALUES (?, ?, now(), now() + interval '1 day')", guest, "fixture-token");
            jdbc.update("INSERT INTO " + schema + ".consultation (id, guest_session_id, status, current_step, created_at, updated_at) VALUES (?, ?, 'IN_PROGRESS', 'CATEGORY', now(), now())", consultation, guest);
            jdbc.update("INSERT INTO " + schema + ".information_report (id, consultation_id, case_input_revision, follow_up_answer_revision, candidates_json, snapshot_json, selected_evidence_json, generated_at) VALUES (?, ?, 0, 0, '{}', '{}', '[]', now())", report, consultation);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM " + schema + ".information_report", Integer.class)).isEqualTo(1);
            Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema).load().migrate();
            var row = jdbc.queryForMap("SELECT id, candidates_json, explanations_json FROM " + schema + ".information_report WHERE id = ?", report);
            assertThat(row.get("id")).isEqualTo(report);
            assertThat(row.get("candidates_json")).isEqualTo("{}");
            assertThat(row.get("explanations_json")).isNull();
        } finally {
            new JdbcTemplate(dataSource).execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
        }
    }
}
