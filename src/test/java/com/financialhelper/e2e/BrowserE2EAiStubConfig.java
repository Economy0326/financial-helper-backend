package com.financialhelper.e2e;

import com.financialhelper.ai.OpenAiStructuredClient;
import com.financialhelper.ai.AiProviderException;
import com.financialhelper.ai.information.InformationExplanationAiResult;
import com.financialhelper.ai.analysis.AnalysisAiResult;
import com.financialhelper.ai.report.ConsultationReportAiResult;
import com.financialhelper.ai.grounded.GroundedEvidenceCitation;
import tools.jackson.databind.json.JsonMapper;
import com.financialhelper.retrieval.KureRuntimeClient;
import com.financialhelper.law.KoreanLawOpenApiClient;
import com.financialhelper.ai.summary.ConsultationSummaryAiResult;
import com.financialhelper.ai.understanding.CaseUnderstandingAiResult;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import java.util.List;
import java.time.LocalDate;
import java.util.Map;

/** Only loaded by FollowUpBrowserE2EHarness from the test runtime classpath. */
@TestConfiguration(proxyBeanMethods = false)
public class BrowserE2EAiStubConfig {
    @Bean
    @Primary
    OpenAiStructuredClient browserE2eAiStub() {
        return new OpenAiStructuredClient(null, null, null) {
            @Override
            public <T> T generateStructured(String instructions, String input, Class<T> responseType) {
                if (responseType == InformationExplanationAiResult.class) {
                    throw new AiProviderException("synthetic browser E2E outage");
                }
                if (input == null || !input.contains("E2E_SYNTHETIC_")) {
                    throw new IllegalStateException("Only synthetic browser E2E input is accepted");
                }
                if (responseType == CaseUnderstandingAiResult.class) {
                    CaseUnderstandingAiResult result = new CaseUnderstandingAiResult();
                    result.facts = List.of();
                    result.missingInformation = List.of();
                    return responseType.cast(result);
                }
                if (responseType == ConsultationSummaryAiResult.class) {
                    ConsultationSummaryAiResult result = new ConsultationSummaryAiResult();
                    result.headline = "브라우저 E2E 테스트 상담";
                    result.summaryText = "E2E_SYNTHETIC_ 상담 입력을 확인하는 테스트용 요약입니다. 금융 행동을 제안하지 않습니다.";
                    ConsultationSummaryAiResult.KeyPoint first = new ConsultationSummaryAiResult.KeyPoint();
                    first.text = "테스트용 상담 입력이 저장되었습니다.";
                    ConsultationSummaryAiResult.KeyPoint second = new ConsultationSummaryAiResult.KeyPoint();
                    second.text = "추가 질문 답변이 기록되었습니다.";
                    result.keyPoints = List.of(first, second);
                    return responseType.cast(result);
                }
                if (responseType == AnalysisAiResult.class) {
                    AnalysisAiResult result = new AnalysisAiResult();
                    result.outcome = AnalysisAiResult.Outcome.READY_FOR_REPORT;
                    result.analysisSummary = "E2E_SYNTHETIC_ 검증된 합성 자료 범위만 확인합니다.";
                    AnalysisAiResult.KeyIssue issue = new AnalysisAiResult.KeyIssue();
                    issue.title = "합성 카드 자료";
                    issue.explanation = "E2E_SYNTHETIC_ 카드 자료의 조건을 확인합니다.";
                    result.keyIssues = List.of(issue);
                    result.additionalInformationNeeded = List.of();
                    result.evidenceCitations = citations(input);
                    return responseType.cast(result);
                }
                if (responseType == ConsultationReportAiResult.class) {
                    ConsultationReportAiResult result = new ConsultationReportAiResult();
                    result.headline = "E2E_SYNTHETIC_ 카드 자료 확인";
                    result.caseSummary = "E2E_SYNTHETIC_ 합성 상담에서 확인된 범위의 자료입니다.";
                    result.firstAction = new ConsultationReportAiResult.FirstAction();
                    result.firstAction.title = "합성 카드 자료 확인";
                    result.firstAction.description = "테스트용 합성 자료를 확인합니다.";
                    ConsultationReportAiResult.KeyIssue issue = new ConsultationReportAiResult.KeyIssue();
                    issue.title = "합성 자료의 조건";
                    issue.explanation = "E2E_SYNTHETIC_ 실제 금융 판단이 아닌 기술 검증입니다.";
                    result.keyIssues = List.of(issue);
                    result.actionSteps = List.of();
                    result.actionConsequences = List.of();
                    result.requiredDocuments = List.of();
                    result.terms = List.of();
                    result.complaintDraft = new ConsultationReportAiResult.ComplaintDraft();
                    result.complaintDraft.subject = "E2E_SYNTHETIC_ 합성 자료 확인";
                    result.complaintDraft.body = "E2E_SYNTHETIC_ 테스트 자료를 확인합니다.";
                    result.evidenceCitations = citations(input);
                    return responseType.cast(result);
                }                throw new IllegalStateException("Unexpected AI output type in browser E2E harness");
            }
        };
    }

    private static List<GroundedEvidenceCitation> citations(String input) {
        try {
            var root = JsonMapper.builder().build().readTree(input);
            var context = root.has("context") ? root.get("context") : root;
            var source = context.get("sourceEvidence");
            if (source == null || source.isEmpty()) throw new IllegalStateException("E2E_ACTION_SOURCE_EVIDENCE_MISSING");
            var first = source.get(0);
            GroundedEvidenceCitation citation = new GroundedEvidenceCitation();
            citation.evidenceId = first.get("evidenceId").asText();
            citation.locator = first.get("locator").asText();
            citation.target = "GENERAL";
            return List.of(citation);
        } catch (Exception exception) {
            throw new IllegalStateException("E2E_ACTION_AI_INPUT_CONTRACT");
        }
    }    /** Deterministic query-only external boundary; never contacts a KURE runtime. */
    @Bean
    @Primary
    @ConditionalOnProperty(name = "app.retrieval.kure.enabled", havingValue = "false", matchIfMissing = true)
    KureRuntimeClient browserE2eKureStub() {
        return new KureRuntimeClient() {
            @Override public KureRuntimeMetadata metadata() { throw new IllegalStateException("E2E_KURE_METADATA_UNSUPPORTED"); }
            @Override public KureRuntimeBuildResult build(KureRuntimeBuildRequest request) { throw new IllegalStateException("E2E_KURE_BUILD_UNSUPPORTED"); }
            @Override public KureRuntimeReadiness readiness(java.util.UUID id) { throw new IllegalStateException("E2E_KURE_READINESS_UNSUPPORTED"); }
            @Override public KureRuntimeQueryResult query(KureRuntimeQueryRequest request) {
                var ids = request.allowedDocumentIds().stream().sorted().limit(request.topK()).toList();
                var hits = new java.util.ArrayList<KureRuntimeQueryResult.Hit>();
                for (int i = 0; i < ids.size(); i++) {
                    hits.add(new KureRuntimeQueryResult.Hit(ids.get(i), i + 1, 1.0, request.generationId()));
                }
                return new KureRuntimeQueryResult(request.generationId(), hits);
            }
        };
    }

    /** Test-only law transport fixture. Production law selection and reviewed identity checks still run. */
    @Bean
    @Primary
    KoreanLawOpenApiClient browserE2eLawStub() {
        Map<String, KoreanLawOpenApiClient.LawVersion> versions = Map.of(
                "여신전문금융업법", lawVersion("여신전문금융업법", "000536", "277267", 2025, 10, 1),
                "여신전문금융업법 시행령", lawVersion("여신전문금융업법 시행령", "004186", "285799", 2025, 10, 1),
                "전자금융거래법", lawVersion("전자금융거래법", "010199", "280277", 2025, 12, 16),
                "전자금융거래법 시행령", lawVersion("전자금융거래법 시행령", "010366", "285727", 2026, 4, 28));
        return new KoreanLawOpenApiClient() {
            @Override public List<LawVersion> searchLaw(String lawName) {
                LawVersion version = versions.get(lawName);
                if (version == null) throw new IllegalStateException("E2E_LAW_OUTSIDE_SYNTHETIC_FIXTURE");
                return List.of(version);
            }
            @Override public LawDocument getLawText(LawVersion version, String articleLocator) {
                if (versions.get(version.statuteName()) != version
                        || !List.of("제16조", "제6조의9", "제9조", "제10조", "제8조").contains(articleLocator)) {
                    throw new IllegalStateException("E2E_LAW_DOCUMENT_OUTSIDE_SYNTHETIC_FIXTURE");
                }
                return new LawDocument(version.statuteName(), version.lawIdentifier(), version.mst(),
                        articleLocator, "E2E_SYNTHETIC_ " + articleLocator + " 법령 전송 경계 테스트 문구입니다.",
                        version.promulgationDate(), version.effectiveDate(), version.derivedUrl());
            }
        };
    }

    private static KoreanLawOpenApiClient.LawVersion lawVersion(
            String name, String identifier, String mst, int year, int month, int day) {
        LocalDate effective = LocalDate.of(year, month, day);
        return new KoreanLawOpenApiClient.LawVersion(name, identifier, mst, effective, effective,
                true, "https://www.law.go.kr/DRF/lawService.do?target=law&MST=" + mst);
    }
}
