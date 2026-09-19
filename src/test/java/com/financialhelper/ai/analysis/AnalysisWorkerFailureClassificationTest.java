package com.financialhelper.ai.analysis;

import com.financialhelper.ai.grounded.GroundedEvidenceUnavailableException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AnalysisWorkerFailureClassificationTest {
    @Test
    void distinguishes_fap_generation_mapping_and_approved_evidence_failures() {
        assertThat(AnalysisWorker.groundedFailureCode(
                new GroundedEvidenceUnavailableException("financial action plan is not READY: UNSUPPORTED")))
                .isEqualTo("FAP_UNAVAILABLE");
        assertThat(AnalysisWorker.groundedFailureCode(
                new GroundedEvidenceUnavailableException("retrieval generation changed")))
                .isEqualTo("RETRIEVAL_GENERATION_MISMATCH");
        assertThat(AnalysisWorker.groundedFailureCode(
                new GroundedEvidenceUnavailableException("source chunk index is not READY")))
                .isEqualTo("RETRIEVAL_MAPPING_MISSING");
        assertThat(AnalysisWorker.groundedFailureCode(
                new GroundedEvidenceUnavailableException("official evidence is unavailable")))
                .isEqualTo("NO_APPROVED_EVIDENCE");
    }

    @Test
    void distinguishes_law_api_configuration_transport_and_validation_failures() {
        assertThat(AnalysisWorker.groundedFailureCode(
                new GroundedEvidenceUnavailableException(
                        "reviewed law evidence is unavailable",
                        new RuntimeException("Korean Law Open API client is disabled"))))
                .isEqualTo("LAW_API_CONFIGURATION_MISSING");
        assertThat(AnalysisWorker.groundedFailureCode(
                new GroundedEvidenceUnavailableException(
                        "reviewed law evidence is unavailable",
                        new RuntimeException("Korean Law Open API HTTP error 503"))))
                .isEqualTo("LAW_API_HTTP_FAILED");
        assertThat(AnalysisWorker.groundedFailureCode(
                new GroundedEvidenceUnavailableException(
                        "reviewed law evidence is unavailable",
                        new RuntimeException("Open API response does not contain requested article"))))
                .isEqualTo("LAW_REQUIRED_ARTICLE_NOT_FOUND");
        assertThat(AnalysisWorker.groundedFailureCode(
                new GroundedEvidenceUnavailableException(
                        "reviewed law evidence is unavailable",
                        new RuntimeException("law evidence effective date is outside reviewed versions: 20260428"))))
                .isEqualTo("LAW_EVIDENCE_VALIDATION_FAILED");
        assertThat(AnalysisWorker.groundedFailureCode(
                new GroundedEvidenceUnavailableException(
                        "reviewed law evidence is unavailable",
                        new RuntimeException("Korean Law Open API returned malformed JSON"))))
                .isEqualTo("LAW_RESPONSE_PARSE_FAILED");
    }
}
