package com.financialhelper.ai.information;

import com.financialhelper.ai.OpenAiStructuredClient;
import com.financialhelper.guest.GuestSessionCookie;
import com.financialhelper.guest.GuestSessionTokenService;
import com.financialhelper.law.KoreanLawOpenApiClient;
import com.financialhelper.retrieval.ConsultationOfficialInformationResponse;
import com.financialhelper.retrieval.KureRuntimeHttpClient;
import com.financialhelper.testsafety.IsolatedDatabaseInitializer;
import jakarta.servlet.http.Cookie;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

import java.sql.DriverManager;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class LegacyInformationReportHttpIntegrationTest {
    private static final String SCHEMA = "legacy_" + UUID.randomUUID().toString().replace("-", "");
    private static final UUID CONSULTATION = UUID.randomUUID(), REPORT = UUID.randomUUID();
    private static final GuestSessionTokenService TOKENS = new GuestSessionTokenService();
    private static final String TOKEN = TOKENS.generateRawToken();
    @Autowired MockMvc mvc;
    @MockitoBean OpenAiStructuredClient openAi;
    @MockitoBean KureRuntimeHttpClient kure;
    @MockitoBean KoreanLawOpenApiClient law;

    @DynamicPropertySource
    static void prepareLegacyDatabase(DynamicPropertyRegistry properties) throws Exception {
        String url = System.getenv("TEST_DB_URL"), user = System.getenv("TEST_DB_USERNAME"), password = System.getenv("TEST_DB_PASSWORD");
        var env = new MockEnvironment().withProperty("spring.datasource.url", url)
                .withProperty("spring.datasource.username", user).withProperty("spring.datasource.password", password);
        IsolatedDatabaseInitializer.verify(env);
        Flyway.configure().dataSource(url, user, password).schemas(SCHEMA).defaultSchema(SCHEMA).target("39").load().migrate();
        var source = new InformationEvidenceSnapshot.Evidence(UUID.randomUUID(), 1, "a".repeat(64),
                UUID.randomUUID(), UUID.randomUUID(), "COMMON", "합성 기관", "합성 문서",
                "https://example.invalid/source", null, null, "page:1", "합성 근거", null, null,
                null, null, List.of(), List.of());
        var snapshot = new InformationEvidenceSnapshot(CONSULTATION, 0, 0, null, UUID.randomUUID(),
                List.of(), List.of(), List.of(), List.of(source), "CURRENT_READY_SOURCE_AND_HASH",
                "V38_SCOPE_REVIEWED", "NOT_DETERMINED");
        var candidates = new ConsultationOfficialInformationResponse(CONSULTATION, 0, 0, null, null,
                "FOUND", false, List.of(), List.of(), List.of(), List.of());
        JsonMapper mapper = JsonMapper.builder().build();
        UUID guest = UUID.randomUUID();
        try (var connection = DriverManager.getConnection(url, user, password)) {
            try (var s = connection.prepareStatement("INSERT INTO " + SCHEMA + ".guest_session (id, token_hash, created_at, expires_at) VALUES (?, ?, now(), now() + interval '1 day')")) {
                s.setObject(1, guest); s.setString(2, TOKENS.hashToken(TOKEN)); s.executeUpdate();
            }
            try (var s = connection.prepareStatement("INSERT INTO " + SCHEMA + ".consultation (id, guest_session_id, status, current_step, created_at, updated_at) VALUES (?, ?, 'IN_PROGRESS', 'CATEGORY', now(), now())")) {
                s.setObject(1, CONSULTATION); s.setObject(2, guest); s.executeUpdate();
            }
            try (var s = connection.prepareStatement("INSERT INTO " + SCHEMA + ".information_report (id, consultation_id, case_input_revision, follow_up_answer_revision, retrieval_generation_id, candidates_json, snapshot_json, selected_evidence_json, generated_at) VALUES (?, ?, 0, 0, ?, ?, ?, ?, now())")) {
                s.setObject(1, REPORT); s.setObject(2, CONSULTATION); s.setObject(3, snapshot.retrievalGenerationId());
                s.setString(4, mapper.writeValueAsString(candidates)); s.setString(5, mapper.writeValueAsString(snapshot));
                s.setString(6, mapper.writeValueAsString(List.of(source))); s.executeUpdate();
            }
        }
        Flyway.configure().dataSource(url, user, password).schemas(SCHEMA).defaultSchema(SCHEMA).load().migrate();
        properties.add("spring.datasource.hikari.schema", () -> SCHEMA);
        properties.add("spring.jpa.properties.hibernate.default_schema", () -> SCHEMA);
        properties.add("spring.flyway.schemas", () -> SCHEMA);
        properties.add("spring.flyway.default-schema", () -> SCHEMA);
    }

    @Test
    void originalV39ReportIsRetainedAndUnavailableEvidenceIsNotExposed() throws Exception {
        mvc.perform(get("/api/v1/consultations/{id}/information-report", CONSULTATION)
                        .cookie(new Cookie(GuestSessionCookie.NAME, TOKEN)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("STALE"))
                .andExpect(jsonPath("$.selectedEvidence").isEmpty()).andExpect(jsonPath("$.explanations").isEmpty());
        try (var c = DriverManager.getConnection(System.getenv("TEST_DB_URL"), System.getenv("TEST_DB_USERNAME"), System.getenv("TEST_DB_PASSWORD"));
             var s = c.prepareStatement("SELECT explanations_json FROM " + SCHEMA + ".information_report WHERE id = ?")) {
            s.setObject(1, REPORT);
            try (var rs = s.executeQuery()) { assertThat(rs.next()).isTrue(); assertThat(rs.getString(1)).isNull(); assertThat(rs.next()).isFalse(); }
        }
    }

    @AfterAll
    static void cleanup() throws Exception {
        String url=System.getenv("TEST_DB_URL"), user=System.getenv("TEST_DB_USERNAME"), password=System.getenv("TEST_DB_PASSWORD");
        IsolatedDatabaseInitializer.verify(new MockEnvironment().withProperty("spring.datasource.url", url)
                .withProperty("spring.datasource.username", user).withProperty("spring.datasource.password", password));
        try (var c = DriverManager.getConnection(url, user, password); var s = c.createStatement()) {
            s.execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
        }
    }
}
