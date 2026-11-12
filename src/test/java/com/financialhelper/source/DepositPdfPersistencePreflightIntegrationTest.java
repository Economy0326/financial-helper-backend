package com.financialhelper.source;

import com.financialhelper.retrieval.KureRuntimeHttpClient;
import com.financialhelper.retrieval.KureRuntimeClient;
import com.financialhelper.retrieval.RetrievalCorpusSnapshotService;
import com.financialhelper.retrieval.ReviewedSourceApplicabilityService;
import com.financialhelper.retrieval.OfficialSourceRetrievalService;
import com.financialhelper.retrieval.SourceKeywordSearchService;
import com.financialhelper.retrieval.OfficialSearchRequest;
import com.financialhelper.consultation.FinancialTopic;
import com.financialhelper.consultation.Consultation;
import com.financialhelper.consultation.ConsultationRepository;
import com.financialhelper.consultation.InformationIntakeService;
import com.financialhelper.guest.GuestSession;
import com.financialhelper.guest.GuestSessionRepository;
import com.financialhelper.guest.GuestSessionTokenService;
import com.financialhelper.retrieval.GeneralTopicInformationService;
import com.financialhelper.retrieval.GeneralTopicInformationRequest;
import com.financialhelper.ai.information.InformationReportService;
import com.financialhelper.ai.information.InformationExplanationService;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import jakarta.servlet.http.Cookie;
import com.financialhelper.guest.GuestSessionCookie;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/** Explicit, isolated preflight only. Never registers or approves an operating source. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "DEPOSIT_PDF_PREFLIGHT_PATH", matches = ".+")
class DepositPdfPersistencePreflightIntegrationTest {
    private static final String URL = "https://www.fsc.go.kr/comm/getFile?srvcId=BBSTY1&upperNo=84975&fileTy=ATTACH&fileNo=1";
    private static final String OBSERVED_HASH = "1b9df7cae3c3563f08e26194abb1c9735cd81dbe08dd79c9d7198066bcd4e989";

    @Autowired PdfSourceDocumentParser parser;
    @Autowired SourceDocumentWriter writer;
    @Autowired SourceDocumentRepository documents;
    @Autowired SourceChunkingService chunking;
    @Autowired SourceChunkRepository chunks;
    @Autowired JdbcTemplate jdbc;
    @Autowired KureRuntimeHttpClient kure;
    @Autowired RetrievalGenerationRepository generations;
    @Autowired SourceChunkIndexingRepository indexings;
    @Autowired ActiveRetrievalGenerationPersistenceService activeGeneration;
    @Autowired RetrievalCorpusSnapshotService corpus;
    @Autowired ReviewedSourceApplicabilityService applicability;
    @Autowired OfficialSourceRetrievalService retrieval;
    @Autowired SourceKeywordSearchService keywordSearch;
    @Autowired GeneralTopicInformationService generalInformation;
    @Autowired GuestSessionTokenService tokens;
    @Autowired GuestSessionRepository guests;
    @Autowired ConsultationRepository consultations;
    @Autowired InformationIntakeService intake;
    @Autowired InformationReportService informationReports;
    @Autowired MockMvc mvc;
    @MockitoBean InformationExplanationService explanationSelector;

    @Test
    void officialPdfChunksPersistThenOnlySyntheticReviewedQuestionsBecomeSearchable() throws Exception {
        byte[] raw = Files.readAllBytes(Path.of(System.getenv("DEPOSIT_PDF_PREFLIGHT_PATH")));
        assertThat(SourceHashing.sha256(raw)).isEqualTo(OBSERVED_HASH);
        assertThat(kure.identifier()).isEqualTo("nlpai-lab/KURE-v2");
        assertThat(kure.revision()).isEqualTo("3431f86d399d666083890dbb882aced6708873bc");

        UUID sourceId = UUID.randomUUID();
        String key = "test-deposit-pdf-" + sourceId.toString().replace("-", "").substring(0, 20);
        jdbc.update("""
                INSERT INTO source_registry (id, source_key, organization_name, official_domain,
                  canonical_url, acquisition_type, enabled, created_at, updated_at, content_selector)
                VALUES (?, ?, '금융위원회', 'fsc.go.kr', ?, 'PDF', TRUE,
                  CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, NULL)
                """, sourceId, key, URL);
        var snapshot = new SourceIngestionData.Snapshot(sourceId, key, "금융위원회",
                "fsc.go.kr", URL,
                SourceAcquisitionType.PDF, null);
        var parsed = parser.parse(snapshot, new SourceIngestionData.Fetched(URL,
                "application/octet-stream", null, null, raw, OffsetDateTime.now(ZoneOffset.UTC)));
        assertThat(parsed.rawSha256()).isEqualTo(OBSERVED_HASH);
        assertThat(parsed.title()).isEqualTo("예금보호한도 상향 관련 주요 QA");
        assertThat(parsed.publishedAt()).isEqualTo(java.time.LocalDate.of(2025, 7, 22));
        writer.saveIfCurrent(snapshot, parsed);
        SourceDocument document = documents.findBySourceRegistry_IdAndStatus(sourceId,
                SourceDocumentStatus.ACTIVE).orElseThrow();
        UUID documentId = document.getId();
        assertThat(document.getDocumentVersion()).isEqualTo(1);
        assertThat(document.getRawSha256()).isEqualTo(OBSERVED_HASH);
        assertThat(document.getTitle()).isEqualTo(parsed.title());
        assertThat(document.getPublishedAt()).isEqualTo(parsed.publishedAt());

        List<SourceChunk> created = chunking.chunk(document);
        assertThat(created).allSatisfy(chunk -> assertThat(chunk.getId()).isNotNull());
        List<SourceChunk> saved = chunks.findAllBySourceDocument_IdOrderBySequenceAsc(documentId);
        assertThat(saved).hasSameSizeAs(created);
        assertThat(saved).allSatisfy(chunk -> {
            assertThat(chunk.getReviewStatus()).isEqualTo(SourceChunkReviewStatus.PENDING);
            assertThat(chunk.getBody()).isEqualTo(document.getNormalizedContent().substring(
                    chunk.getSourceStartOffset(), chunk.getSourceEndOffset()));
            assertThat(chunk.getLocator()).isEqualTo("normalized-content:utf16:"
                    + chunk.getSourceStartOffset() + "-" + chunk.getSourceEndOffset());
            assertThat(chunk.getPageReference()).startsWith("p.");
            assertThat(chunk.getSourceDocument().getId()).isEqualTo(documentId);
        });
        for (int number = 1; number <= 10; number++) {
            int questionNumber = number;
            String marker = questionNumber + ". ";
            List<SourceChunk> question = saved.stream()
                    .filter(chunk -> chunk.getParentSection() != null
                            && chunk.getParentSection().startsWith(marker)).toList();
            assertThat(question).as("question " + questionNumber).isNotEmpty();
            assertThat(question.getFirst().getBody()).contains(marker);
            assertThat(question).allSatisfy(chunk -> assertThat(chunk.getBody())
                    .doesNotContain((questionNumber == 10 ? 1 : questionNumber + 1) + ". "));
        }
        String ten = saved.stream().filter(chunk -> chunk.getParentSection() != null
                && chunk.getParentSection().startsWith("10. "))
                .map(SourceChunk::getBody).reduce("", (left, right) -> left + "\n" + right);
        assertThat(ten).contains("사고보험금", "※ (예)", "별도보호한도",
                "예금 등 보호상품으로 운용되는 금액");
        String two = saved.stream().filter(chunk -> chunk.getParentSection() != null
                && chunk.getParentSection().startsWith("2. "))
                .map(SourceChunk::getBody).reduce("", String::concat);
        assertThat(two).contains("보호상품으로 운용되는 경우", "ISA", "DC형");
        String six = saved.stream().filter(chunk -> chunk.getParentSection() != null
                && chunk.getParentSection().startsWith("6. "))
                .map(SourceChunk::getBody).reduce("", String::concat);
        assertThat(six).contains("이자", "약정이율", "중앙회가 정하는 이율");
        var reviewCandidates = new DepositPdfReviewCandidateSelector().select(document, saved);
        assertThat(reviewCandidates).extracting(DepositPdfReviewCandidateSelector.Candidate::question)
                .containsExactly(1, 2, 3, 5, 6, 7, 8, 9, 10);
        assertThat(reviewCandidates).extracting(DepositPdfReviewCandidateSelector.Candidate::supportChunkId)
                .doesNotContain(saved.get(0).getId(), saved.get(4).getId());
        assertThat(reviewCandidates.stream().filter(candidate -> Set.of(2, 6, 10)
                .contains(candidate.question())).toList())
                .allSatisfy(candidate -> assertThat(candidate.mandatoryContextPassages()).isNotEmpty());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM source_applicability_review WHERE source_document_id = ?",
                Integer.class, documentId)).isZero();
        document.setApplicabilityWindow(LocalDate.of(2025, 9, 1), null);
        documents.saveAndFlush(document);
        var generation = generations.saveAndFlush(new RetrievalGeneration(
                new RetrievalGenerationData.Definition("deposit-preflight-" + sourceId,
                        "representation-v1", "model", "revision-1", kure.identifier(),
                        kure.revision(), "{}", "{}", "{}"), OffsetDateTime.now(ZoneOffset.UTC)));
        var now = OffsetDateTime.now(ZoneOffset.UTC);
        for (var candidate : reviewCandidates) {
            SourceChunk chunk = chunks.findWithDocumentById(candidate.supportChunkId()).orElseThrow();
            chunk.approve(now);
            chunks.saveAndFlush(chunk);
            var indexing = indexings.saveAndFlush(new SourceChunkIndexing(chunk, generation, now));
            indexing.markProcessing(now.plusSeconds(1));
            indexing.markReady(now.plusSeconds(2), "{}");
            indexings.saveAndFlush(indexing);
            UUID reviewId = UUID.randomUUID();
            boolean condition = Set.of(2, 6, 10).contains(candidate.question());
            boolean exception = Set.of(2, 10).contains(candidate.question());
            jdbc.update("""
                    INSERT INTO source_applicability_review (id, source_document_id, reviewed_raw_sha256,
                        scope_kind, scenario, review_target_kind, financial_topic,
                        applicability_start_date, review_status, condition_required, exception_required,
                        conditions_reviewed, exceptions_reviewed, reviewed_by, reviewed_at)
                    VALUES (?, ?, ?, 'COMMON', NULL, 'TOPIC', 'DEPOSITS_ACCOUNTS',
                        DATE '2025-09-01', 'APPROVED', ?, ?, TRUE, TRUE,
                        'isolated synthetic fixture', CURRENT_TIMESTAMP)
                    """, reviewId, documentId, OBSERVED_HASH, condition, exception);
            jdbc.update("""
                    INSERT INTO source_applicability_chunk
                        (review_id, source_document_id, source_chunk_id, evidence_role)
                    VALUES (?, ?, ?, 'SUPPORT')
                    """, reviewId, documentId, chunk.getId());
            if (condition) jdbc.update("""
                    INSERT INTO source_applicability_chunk
                        (review_id, source_document_id, source_chunk_id, evidence_role)
                    VALUES (?, ?, ?, 'CONDITION')
                    """, reviewId, documentId, chunk.getId());
            if (exception) jdbc.update("""
                    INSERT INTO source_applicability_chunk
                        (review_id, source_document_id, source_chunk_id, evidence_role)
                    VALUES (?, ?, ?, 'EXCEPTION')
                    """, reviewId, documentId, chunk.getId());
        }
        generation.markProcessing(now.plusSeconds(1));
        generation.markReady(9, now.plusSeconds(3));
        generations.saveAndFlush(generation);
        activeGeneration.activate(generation.getId());
        var ready = corpus.readyChunks(generation.getId());
        var eligible = applicability.eligibleTopic(FinancialTopic.DEPOSITS_ACCOUNTS,
                LocalDate.of(2026, 11, 9), null, null, ready);
        assertThat(eligible).hasSize(9).doesNotContainKeys(saved.get(0).getId(), saved.get(4).getId());
        boolean realKure = "true".equalsIgnoreCase(System.getenv("DEPOSIT_REAL_KURE_EVAL"));
        if (realKure) {
            var indexed = reviewCandidates.stream().map(candidate -> {
                var chunk = saved.stream().filter(item -> item.getId().equals(candidate.supportChunkId()))
                        .findFirst().orElseThrow();
                return new KureRuntimeClient.KureRuntimeBuildRequest.Document(chunk.getId(), chunk.getBody());
            }).toList();
            var built = kure.build(new KureRuntimeClient.KureRuntimeBuildRequest(
                    generation.getId(), "isolated-deposit-" + generation.getId(),
                    "nlpai-lab/KURE-v2", "3431f86d399d666083890dbb882aced6708873bc",
                    kure.identifier(), kure.revision(), "deposit-fsc-q-v1",
                    SourceHashing.sha256(document.getNormalizedContent()),
                    "{\"queryLength\":64,\"documentMaxTokens\":8192,\"doQueryExpansion\":true,\"queryPrefix\":\"\",\"documentPrefix\":\"\"}",
                    "{\"backend\":\"PLAID\",\"nbits\":4,\"seed\":42,\"useFast\":true,\"useTriton\":false}",
                    indexed));
            assertThat(built.readyDocumentIds()).containsExactlyInAnyOrderElementsOf(
                    indexed.stream().map(KureRuntimeClient.KureRuntimeBuildRequest.Document::id).toList());
            assertThat(kure.readiness(generation.getId()).ready()).isTrue();
        }
        var search = retrieval.searchReviewedCandidates(new OfficialSearchRequest(
                "예금", FinancialTopic.DEPOSITS_ACCOUNTS.name(), null, null,
                null, List.of(), 10, null), eligible.keySet());
        assertThat(search.status()).isEqualTo(com.financialhelper.retrieval.RetrievalStatus.FOUND);
        assertThat(search.candidates()).isNotEmpty();
        assertThat(search.candidates()).allSatisfy(candidate ->
                assertThat(eligible).containsKey(candidate.sourceChunkId()));
        if (realKure) {
            assertThat(search.candidates()).anySatisfy(candidate ->
                    assertThat(candidate.semanticScore()).isNotNull());
            assertThat(search.candidates()).allSatisfy(candidate ->
                    assertThat(candidate.rrfScore()).isPositive());
            var keywordHits = keywordSearch.searchApproved("예금", 100).stream()
                    .filter(chunk -> eligible.containsKey(chunk.getId())).toList();
            assertThat(keywordHits).isNotEmpty();
            System.out.println("DEPOSIT_REAL_BRANCH semantic=" + search.candidates().stream()
                    .filter(candidate -> candidate.semanticScore() != null).count()
                    + " keyword=" + keywordHits.size()
                    + " topRrf=" + search.candidates().getFirst().rrfScore());
        }
        String rawToken = tokens.generateRawToken();
        GuestSession guest = guests.saveAndFlush(new GuestSession(tokens.hashToken(rawToken), now,
                now.plusDays(1)));
        Consultation consultation = new Consultation(guest, now);
        consultation.updateInformationIntake("예금자보호 한도가 얼마인가요?", now.plusSeconds(1));
        consultation.confirmInformationTopic(FinancialTopic.DEPOSITS_ACCOUNTS, now.plusSeconds(2));
        consultation = consultations.saveAndFlush(consultation);
        assertThat(intake.get(consultation.getId(), rawToken).generalInformationSearchAvailable()).isTrue();
        var generalResult = generalInformation.search(consultation.getId(), rawToken,
                new GeneralTopicInformationRequest(consultation.getCaseInputRevision(),
                        GeneralTopicInformationRequest.TimeScope.CURRENT, null));
        assertThat(generalResult.status()).isEqualTo("READY_CANDIDATES");
        assertThat(generalResult.candidates()).isNotEmpty()
                .allSatisfy(candidate -> assertThat(eligible).containsKey(candidate.sourceChunkId()));
        when(explanationSelector.select(any())).thenAnswer(invocation ->
                ((com.financialhelper.ai.information.InformationEvidenceSnapshot)
                        invocation.getArgument(0)).evidence());
        var prepared = informationReports.prepare(consultation.getId(), rawToken,
                consultation.getCaseInputRevision(),
                GeneralTopicInformationRequest.TimeScope.CURRENT, null);
        assertThat(prepared.status()).isEqualTo("READY_CANDIDATES");
        assertThat(prepared.snapshot().financialTopic()).isEqualTo("DEPOSITS_ACCOUNTS");
        assertThat(prepared.snapshot().referenceDate())
                .isEqualTo(LocalDate.now(java.time.ZoneId.of("Asia/Seoul")));
        assertThat(prepared.selectedEvidence()).hasSize(1).allSatisfy(item -> {
            assertThat(item.questionNumber()).isEqualTo(6);
            assertThat(item.organizationName()).isEqualTo("금융위원회");
            assertThat(item.title()).isEqualTo("예금보호한도 상향 관련 주요 QA");
            assertThat(item.canonicalUrl()).isEqualTo(URL);
            assertThat(item.metadataSourceUrl()).isEqualTo(VerifiedPdfMetadata.DEPOSIT_POST_URL);
            assertThat(item.rawSha256()).isEqualTo(OBSERVED_HASH);
            assertThat(item.pageReference()).startsWith("p.");
            assertThat(item.conditions()).isNotEmpty();
            assertThat(item.supportText()).contains("약정이율", "중앙회가 정하는 이율");
        });
        if (realKure) logRealBranches("P1", "예금자보호 한도가 얼마인가요?",
                prepared.selectedEvidence().getFirst().supportChunkId());
        var reloaded = informationReports.get(consultation.getId(), rawToken);
        assertThat(reloaded.status()).isEqualTo("READY_CANDIDATES");
        assertThat(reloaded.selectedEvidence()).isEqualTo(prepared.selectedEvidence());
        String reportUrl = "/api/v1/consultations/" + consultation.getId() + "/information-report";
        Cookie cookie = new Cookie(GuestSessionCookie.NAME, rawToken);
        String requestBody = "{\"expectedCaseInputRevision\":" + consultation.getCaseInputRevision()
                + ",\"timeScope\":\"CURRENT\"}";
        mvc.perform(post(reportUrl + "/prepare").cookie(cookie).contentType("application/json")
                        .content(requestBody)).andExpect(status().isForbidden());
        mvc.perform(post(reportUrl + "/prepare").cookie(cookie).with(csrf())
                        .contentType("application/json").content(requestBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("READY_CANDIDATES"))
                .andExpect(jsonPath("$.selectedEvidence[0].questionNumber").value(6));
        mvc.perform(get(reportUrl).cookie(cookie)).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("READY_CANDIDATES"))
                .andExpect(jsonPath("$.snapshot.financialTopic").value("DEPOSITS_ACCOUNTS"));
        var answerable = java.util.Map.of(
                "통장이 여러 개면 각각 보호되나요?", 8,
                "다른 은행에 있는 예금은 어떻게 계산하나요?", 9,
                "ISA는 무조건 보호되나요?", 2,
                "원금과 이자는 어떻게 계산하나요?", 6,
                "퇴직연금에 별도 한도가 있나요?", 10);
        var ownedByQuestion = new java.util.HashMap<Integer, OwnedTopicConsultation>();
        answerable.forEach((question, expectedNumber) -> {
            var owned = topicConsultation(question);
            ownedByQuestion.put(expectedNumber, owned);
            var report = informationReports.prepare(owned.id(), owned.token(), owned.revision(),
                    GeneralTopicInformationRequest.TimeScope.CURRENT, null);
            assertThat(report.status()).as(question).isEqualTo("READY_CANDIDATES");
            assertThat(report.selectedEvidence()).as(question).hasSize(1);
            var evidence = report.selectedEvidence().getFirst();
            assertThat(evidence.questionNumber()).as(question).isEqualTo(expectedNumber);
            assertThat(evidence.supportText()).doesNotContain("4. 외화예금");
            if (Set.of(2, 6, 10).contains(expectedNumber)) {
                assertThat(evidence.conditions()).as(question).isNotEmpty();
                assertThat(report.explanations()).allSatisfy(item ->
                        assertThat(item.easyText()).contains(evidence.conditions().getFirst().text()));
            }
            if (Set.of(2, 10).contains(expectedNumber)) {
                assertThat(evidence.exceptions()).as(question).isNotEmpty();
                assertThat(report.explanations()).allSatisfy(item ->
                        assertThat(item.easyText()).contains(evidence.exceptions().getFirst().text()));
            }
            if (expectedNumber == 10) {
                assertThat(evidence.supportText()).contains("사고보험금", "미지급된", "※ (예)");
            }
            assertThat(informationReports.get(owned.id(), owned.token()).selectedEvidence())
                    .isEqualTo(report.selectedEvidence());
            if (realKure) logRealBranches("P" + expectedNumber, question, evidence.supportChunkId());
        });
        if (realKure) evaluateRealTopicReports(eligible);
        for (String question : List.of("외화예금도 보호되나요?", "제 펀드가 반드시 보호되나요?",
                "제가 가입한 ISA의 실제 보호금액을 알려주세요.",
                "2025년 9월 이전에 발생한 제 보호사고도 새 한도가 적용되나요?",
                "특정 금융기관이 현재 보호대상인지 확정해 주세요.",
                "공식 근거에 없는 배상·환급을 보장해 주세요.")) {
            var owned = topicConsultation(question);
            var refused = informationReports.prepare(owned.id(), owned.token(), owned.revision(),
                    GeneralTopicInformationRequest.TimeScope.CURRENT, null);
            assertThat(refused.status()).as(question).isNotEqualTo("READY_CANDIDATES");
            assertThat(refused.selectedEvidence()).isEmpty();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM information_report WHERE consultation_id = ?",
                    Integer.class, owned.id())).as(question).isZero();
        }
        var historical = topicConsultation("예금자보호 한도가 얼마인가요?");
        assertThat(informationReports.prepare(historical.id(), historical.token(), historical.revision(),
                GeneralTopicInformationRequest.TimeScope.HISTORICAL, LocalDate.of(2025, 9, 1)).status())
                .isEqualTo("NEEDS_CLARIFICATION");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM information_report WHERE consultation_id = ?",
                Integer.class, historical.id())).isZero();
        UUID q2Review = eligible.get(saved.get(2).getId()).reviewId();
        jdbc.update("""
                DELETE FROM source_applicability_chunk
                WHERE review_id = ? AND evidence_role = 'CONDITION'
                """, q2Review);
        assertThat(applicability.eligibleTopic(FinancialTopic.DEPOSITS_ACCOUNTS,
                LocalDate.of(2026, 11, 9), null, null, corpus.readyChunks(generation.getId())))
                .doesNotContainKey(saved.get(2).getId());
        UUID q10Review = eligible.get(saved.get(10).getId()).reviewId();
        jdbc.update("DELETE FROM source_applicability_chunk WHERE review_id = ? AND evidence_role = 'EXCEPTION'", q10Review);
        assertThat(applicability.eligibleTopic(FinancialTopic.DEPOSITS_ACCOUNTS,
                LocalDate.of(2026, 11, 9), null, null, corpus.readyChunks(generation.getId())))
                .doesNotContainKey(saved.get(10).getId());
        assertThat(applicability.eligibleTopic(FinancialTopic.DEPOSITS_ACCOUNTS,
                LocalDate.of(2026, 11, 9), null, null, List.of())).isEmpty();
        UUID q6Review = eligible.get(saved.get(6).getId()).reviewId();
        jdbc.update("""
                UPDATE source_applicability_review SET review_status = 'REVOKED',
                    revoked_at = CURRENT_TIMESTAMP, revoked_by = 'isolated synthetic fixture',
                    revocation_reason = 'test revocation' WHERE id = ?
                """, q6Review);
        var stale = informationReports.get(consultation.getId(), rawToken);
        assertThat(stale.status()).isEqualTo("STALE");
        assertThat(stale.selectedEvidence()).isEmpty();
        assertThat(stale.explanations()).isEmpty();
        mvc.perform(get(reportUrl).cookie(cookie)).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("STALE"))
                .andExpect(jsonPath("$.selectedEvidence.length()").value(0));
        var q8Owned = ownedByQuestion.get(8);
        assertThat(informationReports.get(q8Owned.id(), q8Owned.token()).status())
                .isEqualTo("READY_CANDIDATES");
        var revised = consultations.findById(q8Owned.id()).orElseThrow();
        revised.updateInformationIntake("예금자보호 한도를 다시 묻습니다.", now.plusSeconds(5));
        consultations.saveAndFlush(revised);
        assertThat(informationReports.get(q8Owned.id(), q8Owned.token()).status()).isEqualTo("STALE");
        var q9Owned = ownedByQuestion.get(9);
        var nextGeneration = generations.saveAndFlush(new RetrievalGeneration(
                new RetrievalGenerationData.Definition("deposit-next-" + sourceId,
                        "representation-v1", "model", "revision-1", kure.identifier(),
                        kure.revision(), "{}", "{}", "{}"), now));
        SourceChunk q9Chunk = chunks.findWithDocumentById(saved.get(9).getId()).orElseThrow();
        var nextIndex = indexings.saveAndFlush(new SourceChunkIndexing(q9Chunk, nextGeneration, now));
        nextIndex.markProcessing(now.plusSeconds(1));
        nextIndex.markReady(now.plusSeconds(2), "{}");
        indexings.saveAndFlush(nextIndex);
        nextGeneration.markProcessing(now.plusSeconds(1));
        nextGeneration.markReady(1, now.plusSeconds(3));
        generations.saveAndFlush(nextGeneration);
        activeGeneration.activate(nextGeneration.getId());
        assertThat(informationReports.get(q9Owned.id(), q9Owned.token()).status()).isEqualTo("STALE");
        if (realKure) {
            // This READY fixture has no PLAID artifact in the real runtime. The KURE 404
            // must never turn into an ungrounded Report when Keyword finds no scoped hit.
            var unavailable = topicConsultation("다른 은행에 있는 예금은 어떻게 계산하나요?");
            var declined = informationReports.prepare(unavailable.id(), unavailable.token(),
                    unavailable.revision(), GeneralTopicInformationRequest.TimeScope.CURRENT, null);
            assertThat(declined.status()).isEqualTo("TEMPORARILY_UNAVAILABLE");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM information_report WHERE consultation_id = ?",
                    Integer.class, unavailable.id())).isZero();
            System.out.println("DEPOSIT_REAL_RUNTIME_MISSING status=" + declined.status());
        }
        activeGeneration.activate(generation.getId());
        assertThat(informationReports.get(q9Owned.id(), q9Owned.token()).status())
                .isEqualTo("READY_CANDIDATES");
        var q10Owned = ownedByQuestion.get(10);
        assertThat(informationReports.get(q10Owned.id(), q10Owned.token()).status())
                .isEqualTo("STALE"); // q10's required exception link was deliberately removed above.
        document.supersede(now.plusSeconds(6));
        documents.saveAndFlush(document);
        assertThat(informationReports.get(q9Owned.id(), q9Owned.token()).status()).isEqualTo("STALE");
        System.out.println("DEPOSIT_PDF_PREFLIGHT document=" + documentId
                + " rawSha256=" + document.getRawSha256()
                + " version=" + document.getDocumentVersion()
                + " chunks=" + saved.size());
        saved.forEach(chunk -> System.out.println("DEPOSIT_PDF_CHUNK id=" + chunk.getId()
                + " question=" + (chunk.getParentSection() == null ? "preamble"
                : chunk.getParentSection().split("\\.", 2)[0])
                + " page=" + chunk.getPageReference()
                + " offset=" + chunk.getSourceStartOffset() + "-" + chunk.getSourceEndOffset()));
    }

    private OwnedTopicConsultation topicConsultation(String question) {
        var now = OffsetDateTime.now(ZoneOffset.UTC);
        String token = tokens.generateRawToken();
        GuestSession guest = guests.saveAndFlush(new GuestSession(tokens.hashToken(token), now,
                now.plusDays(1)));
        Consultation consultation = new Consultation(guest, now);
        consultation.updateInformationIntake(question, now.plusSeconds(1));
        consultation.confirmInformationTopic(FinancialTopic.DEPOSITS_ACCOUNTS, now.plusSeconds(2));
        consultation = consultations.saveAndFlush(consultation);
        return new OwnedTopicConsultation(consultation.getId(), token, consultation.getCaseInputRevision());
    }

    private void evaluateRealTopicReports(
            java.util.Map<UUID, ReviewedSourceApplicabilityService.Match> eligible) {
        var cases = List.of(
                new EvalCase("V01", "은행 예금은 어디까지 보호받을 수 있나요?", 6),
                new EvalCase("V02", "예금 보호 최대 금액이 궁금합니다.", 6),
                new EvalCase("V03", "한도 상향액을 알려주세요.", 6),
                new EvalCase("V04", "한 은행에서 적금 두 개를 들면 한도도 두 번인가요?", 8),
                new EvalCase("V05", "같은 금융기관 계좌들을 합쳐서 계산하나요?", 8),
                new EvalCase("V06", "한 곳에 통장을 여러 개 만들면 각 통장마다 보장되나요?", 8),
                new EvalCase("V07", "A은행과 B은행에 각각 돈을 맡기면 합산하나요?", 9),
                new EvalCase("V08", "서로 다른 은행의 보호한도는 따로 계산하나요?", 9),
                new EvalCase("V09", "은행 두 곳의 예금은 기관별로 보호되나요?", 9),
                new EvalCase("V10", "ISA 계좌 자산은 모두 예금보험이 적용되나요?", 2),
                new EvalCase("V11", "개인종합자산관리계좌에서 펀드와 예금의 취급이 같나요?", 2),
                new EvalCase("V12", "IRP 안의 예금으로 운용한 부분은 보호되나요?", 2),
                new EvalCase("V13", "보호되지 않는 금융상품에는 무엇이 있나요?", 3),
                new EvalCase("V14", "펀드는 예금처럼 보호 대상인가요?", 3),
                new EvalCase("V15", "채권형 펀드도 원금 보장 상품인가요?", 3),
                new EvalCase("V16", "보호한도가 인상되기 전에 가입한 적금도 해당되나요?", 5),
                new EvalCase("V17", "새 한도는 언제부터 시행됐나요?", 5),
                new EvalCase("V18", "기존 예금 가입자도 새 기준을 적용받나요?", 5),
                new EvalCase("V19", "예금의 이자는 어느 이율로 계산되나요?", 6),
                new EvalCase("V20", "약정이율과 보호기관 기준 이율 중 어느 것이 적용되나요?", 6),
                new EvalCase("V21", "원금과 소정의 이자를 합쳐 보호하나요?", 6),
                new EvalCase("V22", "사고보험금도 일반 예금과 별도 한도인가요?", 10),
                new EvalCase("V23", "퇴직연금과 연금저축의 보호한도는 분리되나요?", 10),
                new EvalCase("V24", "별도보호한도 예시가 무엇인가요?", 10));
        for (var item : cases) {
            var owned = topicConsultation(item.question());
            var purpose = com.financialhelper.retrieval.DepositQueryPurpose.questionNumbers(item.question());
            var report = informationReports.prepare(owned.id(), owned.token(), owned.revision(),
                    GeneralTopicInformationRequest.TimeScope.CURRENT, null);
            Integer actual = report.selectedEvidence().isEmpty() ? null
                    : report.selectedEvidence().getFirst().questionNumber();
            if (actual != null) {
                assertThat(actual).as(item.id()).isEqualTo(item.expectedQuestion());
                assertThat(report.selectedEvidence()).as(item.id()).hasSize(1)
                        .allSatisfy(evidence -> assertThat(eligible).containsKey(evidence.supportChunkId()));
                assertThat(informationReports.get(owned.id(), owned.token()).selectedEvidence())
                        .as(item.id()).isEqualTo(report.selectedEvidence());
                logRealBranches(item.id(), item.question(), report.selectedEvidence().getFirst().supportChunkId());
            } else {
                assertThat(jdbc.queryForObject("SELECT count(*) FROM information_report WHERE consultation_id = ?",
                        Integer.class, owned.id())).as(item.id()).isZero();
            }
            System.out.println("DEPOSIT_REAL_EVAL " + item.id() + " expected=" + item.expectedQuestion()
                    + " purpose=" + purpose + " status=" + report.status() + " evidence=" + actual);
        }
    }

    private void logRealBranches(String label, String question, UUID approvedChunkId) {
        String purposeQuery = com.financialhelper.retrieval.DepositQueryPurpose.query(question);
        assertThat(purposeQuery).as(label).isNotBlank();
        var keywordIds = keywordSearch.searchApproved(purposeQuery, 100).stream()
                .map(SourceChunk::getId).toList();
        var fused = retrieval.searchReviewedCandidates(new OfficialSearchRequest(
                purposeQuery, FinancialTopic.DEPOSITS_ACCOUNTS.name(), null, null,
                null, List.of(), 10, null), Set.of(approvedChunkId));
        assertThat(fused.status()).as(label).isEqualTo(com.financialhelper.retrieval.RetrievalStatus.FOUND);
        assertThat(fused.candidates()).as(label).hasSize(1)
                .allSatisfy(candidate -> assertThat(candidate.sourceChunkId()).isEqualTo(approvedChunkId));
        var hit = fused.candidates().getFirst();
        System.out.println("DEPOSIT_REAL_FUSION " + label + " semantic=" + (hit.semanticScore() != null)
                + " keyword=" + keywordIds.contains(approvedChunkId) + " rrf=" + hit.rrfScore());
    }

    private record EvalCase(String id, String question, int expectedQuestion) {}

    private record OwnedTopicConsultation(UUID id, String token, long revision) {}
}
