package com.financialhelper.law;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.LocalDate;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LawEvidenceServiceTest {

    private static final String SEARCH = """
            검색 결과 (총 1건):

            1. 여신전문금융업법 [현행]
               - 법령ID: 12345
               - MST: 67890
               - 공포일: 20250101 / 시행일: 20250201
            """;
    private static final String TEXT = """
            법령명: 여신전문금융업법
            공포일: 2025.1.1.
            시행일: 2025.2.1.

            제70조(벌칙) 법령 본문
            """;

    @Mock
    private KoreanLawMcpClient client;

    private LawEvidenceService service;

    @BeforeEach
    void setUp() {
        KoreanLawMcpProperties properties = new KoreanLawMcpProperties(
                true,
                "http://127.0.0.1:8000/mcp",
                Duration.ofSeconds(1),
                Duration.ofSeconds(2),
                "",
                "4.9.1",
                "https://github.com/chrisryugj/korean-law-mcp",
                "v4.9.1",
                "860cfcbce9c01c664766ec1badca8d4468b87488");
        service = new LawEvidenceService(client, properties);
        lenient().when(client.metadata()).thenReturn(
                new KoreanLawMcpClient.ServerMetadata("korean-law", "4.9.1", "2024-11-05"));
    }

    @Test
    void validates_current_card_statute_text_and_provenance() {
        when(client.call(eq("search_law"), any())).thenReturn(
                new KoreanLawMcpClient.ToolResult("search_law", SEARCH));
        when(client.call(eq("get_law_text"), any())).thenReturn(
                new KoreanLawMcpClient.ToolResult("get_law_text", TEXT));

        LawEvidence evidence = service.lookup(
                new LawEvidenceRequest("여신전문금융업법", "제70조", null));

        assertThat(evidence.statuteName()).isEqualTo("여신전문금융업법");
        assertThat(evidence.lawIdentifier()).isEqualTo("12345");
        assertThat(evidence.mst()).isEqualTo("67890");
        assertThat(evidence.effectiveDate()).isEqualTo("20250201");
        assertThat(evidence.validationStatus()).isEqualTo("VALIDATED_PENDING_REVIEW");
        assertThat(evidence.canonicalUrl()).isNull();
        assertThat(evidence.sourceUrl()).isNull();
        assertThat(evidence.derivedUrl()).startsWith("https://www.law.go.kr/법령/");
        verify(client).call("get_law_text", Map.of("mst", "67890", "jo", "제70조"));
    }

    @Test
    void incident_date_uses_applicable_law_mst_and_rejects_future_version() {
        when(client.call(eq("search_law"), any())).thenReturn(
                new KoreanLawMcpClient.ToolResult("search_law", SEARCH));
        when(client.call(eq("legal_analysis"), any())).thenReturn(
                new KoreanLawMcpClient.ToolResult(
                        "legal_analysis",
                        "═══ 행위시법 판단 ═══\n  여신전문금융업법 [시행 2024.12.31.] (MST 11111)"));
        when(client.call(eq("get_law_text"), any())).thenReturn(
                new KoreanLawMcpClient.ToolResult("get_law_text", """
                        법령명: 여신전문금융업법
                        공포일: 2024.1.1.
                        시행일: 2024.12.31.
                        제70조 본문
                        """));

        LawEvidence evidence = service.lookup(new LawEvidenceRequest(
                "여신전문금융업법", "제70조", LocalDate.of(2025, 1, 1)));

        assertThat(evidence.mst()).isEqualTo("11111");
        assertThat(evidence.applicabilityBasis()).isEqualTo("INCIDENT_DATE");
        verify(client).call("get_law_text", Map.of(
                "mst", "11111", "jo", "제70조", "efYd", "20241231"));
    }

    @Test
    void retries_detail_read_by_exact_law_id_when_mst_is_rejected() {
        when(client.call(eq("search_law"), any())).thenReturn(
                new KoreanLawMcpClient.ToolResult("search_law", SEARCH));
        when(client.call(eq("get_law_text"), any()))
                .thenThrow(new KoreanLawMcpException("MST rejected"))
                .thenReturn(new KoreanLawMcpClient.ToolResult("get_law_text", TEXT));

        LawEvidence evidence = service.lookup(
                new LawEvidenceRequest("여신전문금융업법", "제70조", null));

        assertThat(evidence.lawIdentifier()).isEqualTo("12345");
        verify(client).call("get_law_text", Map.of("mst", "67890", "jo", "제70조"));
        verify(client).call("get_law_text", Map.of("lawId", "12345", "jo", "제70조"));
    }

    @Test
    void rejects_text_from_a_different_selected_version() {
        when(client.call(eq("search_law"), any())).thenReturn(
                new KoreanLawMcpClient.ToolResult("search_law", SEARCH));
        when(client.call(eq("get_law_text"), any())).thenReturn(
                new KoreanLawMcpClient.ToolResult("get_law_text", TEXT.replace("2025.2.1.", "2025.3.1.")));

        assertThatThrownBy(() -> service.lookup(
                new LawEvidenceRequest("여신전문금융업법", "제70조", null)))
                .isInstanceOf(KoreanLawMcpException.class)
                .hasMessageContaining("does not match selected effective date");
    }

    @Test
    void rejects_applicability_result_for_a_different_statute() {
        when(client.call(eq("search_law"), any())).thenReturn(
                new KoreanLawMcpClient.ToolResult("search_law", SEARCH));
        when(client.call(eq("legal_analysis"), any())).thenReturn(
                new KoreanLawMcpClient.ToolResult(
                        "legal_analysis",
                        "═══ 행위시법 판단: 전자금융거래법 @ 2025.1.1. ═══\n"
                                + "전자금융거래법 [시행 2024.12.31.] (MST 11111)"));

        assertThatThrownBy(() -> service.lookup(new LawEvidenceRequest(
                "여신전문금융업법", "제70조", LocalDate.of(2025, 1, 1))))
                .isInstanceOf(KoreanLawMcpException.class)
                .hasMessageContaining("different statute");
    }

    @Test
    void unrelated_search_result_fails_closed() {
        when(client.call(eq("search_law"), any())).thenReturn(
                new KoreanLawMcpClient.ToolResult("search_law", SEARCH.replace("여신전문금융업법", "전자금융거래법")));

        assertThatThrownBy(() -> service.lookup(
                new LawEvidenceRequest("여신전문금융업법", null, null)))
                .isInstanceOf(KoreanLawMcpException.class)
                .hasMessageContaining("did not return");
    }

    @Test
    void server_version_mismatch_fails_closed_before_law_query() {
        when(client.metadata()).thenReturn(
                new KoreanLawMcpClient.ServerMetadata("korean-law", "4.9.0", "2024-11-05"));

        assertThatThrownBy(() -> service.lookup(
                new LawEvidenceRequest("여신전문금융업법", null, null)))
                .isInstanceOf(KoreanLawMcpException.class)
                .hasMessageContaining("version mismatch");
    }

    @Test
    void repository_pin_mismatch_fails_closed() {
        KoreanLawMcpProperties mismatched = new KoreanLawMcpProperties(
                true,
                "http://127.0.0.1:8000/mcp",
                Duration.ofSeconds(1),
                Duration.ofSeconds(2),
                "",
                "4.9.1",
                "https://example.invalid/law",
                "v4.9.1",
                "860cfcbce9c01c664766ec1badca8d4468b87488");
        LawEvidenceService mismatchedService = new LawEvidenceService(client, mismatched);

        assertThatThrownBy(() -> mismatchedService.lookup(
                new LawEvidenceRequest("여신전문금융업법", null, null)))
                .isInstanceOf(KoreanLawMcpException.class)
                .hasMessageContaining("repository/tag/commit");
    }
}
