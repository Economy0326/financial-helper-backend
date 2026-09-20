package com.financialhelper.support;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class LocalQaResetScriptTest {
    @Test
    void reset_is_local_opt_in_and_preserves_reviewed_runtime_authorities() throws Exception {
        String script = Files.readString(Path.of("scripts/local/reset-qa-state.ps1"));

        assertThat(script)
                .contains("RESET_LOCAL_QA", "localhost", "127.0.0.1", "::1")
                .contains("'consultation_report'", "'analysis_job'", "'consultation'")
                .contains("'account_session'", "'account_identity'", "'guest_session'")
                .contains("'source_registry'", "'source_document'", "'source_chunk'")
                .contains("'retrieval_generation'", "'active_retrieval_generation'")
                .contains("'procedure_version'", "'flyway_schema_history'")
                .doesNotContain("DELETE FROM source_registry")
                .doesNotContain("DELETE FROM source_document")
                .doesNotContain("DELETE FROM source_chunk")
                .doesNotContain("DELETE FROM retrieval_generation")
                .doesNotContain("DELETE FROM procedure_version")
                .doesNotContain("DELETE FROM flyway_schema_history");
    }
}
