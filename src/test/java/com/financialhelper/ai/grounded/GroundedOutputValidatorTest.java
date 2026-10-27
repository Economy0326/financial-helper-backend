package com.financialhelper.ai.grounded;

import com.financialhelper.ai.AiOutputContractException;
import com.financialhelper.ai.analysis.AnalysisAiResult;
import com.financialhelper.ai.report.ConsultationReportAiResult;
import com.financialhelper.procedure.ConditionResult;
import com.financialhelper.procedure.FinancialActionPlanData;
import com.financialhelper.procedure.PlanStatus;
import com.financialhelper.procedure.FinancialActionPlanService;
import com.financialhelper.procedure.FinancialActionPlanRepository;
import com.financialhelper.procedure.ProcedureVersion;
import com.financialhelper.procedure.ProcedureVersionData;
import com.financialhelper.procedure.ProcedureVersionRepository;
import com.financialhelper.procedure.FinancialActionPlan;
import com.financialhelper.ai.analysis.AnalysisJobRepository;
import com.financialhelper.retrieval.ConfirmedCaseSnapshotRepository;
import com.financialhelper.source.ActiveRetrievalGenerationPersistenceService;
import com.financialhelper.source.RetrievalGeneration;
import com.financialhelper.source.RetrievalGenerationRepository;
import com.financialhelper.source.SourceChunkRepository;
import com.financialhelper.source.SourceChunkIndexingRepository;
import com.financialhelper.source.SourceChunk;
import com.financialhelper.source.SourceChunkIndexing;
import com.financialhelper.source.SourceChunkIndexingStatus;
import com.financialhelper.source.SourceChunkReviewStatus;
import com.financialhelper.source.SourceDocument;
import com.financialhelper.source.SourceDocumentStatus;
import com.financialhelper.source.RetrievalGenerationStatus;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.json.JsonMapper;

import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.ArrayList;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GroundedOutputValidatorTest {
    private final GroundedOutputValidator validator = new GroundedOutputValidator();

    @Test
    void analysisRejectsCitationOutsideSnapshot() {
        AnalysisAiResult result = new AnalysisAiResult();
        result.evidenceCitations = List.of(citation("source:missing", "제40조"));

        assertThatThrownBy(() -> validator.validateAnalysis(result, snapshot()))
                .isInstanceOf(AiOutputContractException.class);
    }

    @Test
    void reportMustUseProcedureActionAndDocumentIdentities() {
        AnalysisEvidenceSnapshotData snapshot = snapshot();
        ConsultationReportAiResult result = validReport(snapshot);
        result.actionSteps.get(0).description = "모델이 만든 다른 행동";

        assertThatThrownBy(() -> validator.validateReport(result, snapshot))
                .isInstanceOf(AiOutputContractException.class);
    }

    @Test
    void validGroundedReportPasses() {
        AnalysisEvidenceSnapshotData snapshot = snapshot();
        assertThatCode(() -> validator.validateReport(validReport(snapshot), snapshot))
                .doesNotThrowAnyException();
    }

    @Test
    void actionCitationCannotBorrowAnotherActionsValidEvidence() {
        AnalysisEvidenceSnapshotData snapshot = scopedSnapshot(false);
        AnalysisAiResult result = new AnalysisAiResult();
        result.evidenceCitations = List.of(actionCitation(snapshot.sourceEvidence().get(0), "action-b"));

        assertThatThrownBy(() -> validator.validateAnalysis(result, snapshot))
                .isInstanceOf(AiOutputContractException.class);
    }

    @Test
    void explicitlySharedSourceCanBeCitedByBothActions() {
        AnalysisEvidenceSnapshotData snapshot = scopedSnapshot(true);
        AnalysisAiResult result = new AnalysisAiResult();
        result.evidenceCitations = List.of(
                actionCitation(snapshot.sourceEvidence().get(0), "action-a"),
                actionCitation(snapshot.sourceEvidence().get(0), "action-b"));

        assertThatCode(() -> validator.validateAnalysis(result, snapshot)).doesNotThrowAnyException();
    }

    @Test
    void generalCitationDoesNotSatisfyActionCitationRequirement() {
        AnalysisEvidenceSnapshotData snapshot = scopedSnapshot(true);
        AnalysisAiResult result = new AnalysisAiResult();
        GroundedEvidenceCitation general = citation(snapshot.sourceEvidence().get(0).evidenceId(), "제1조");
        general.target = "GENERAL";
        result.evidenceCitations = List.of(general);

        assertThatThrownBy(() -> validator.validateAnalysis(result, snapshot))
                .isInstanceOf(AiOutputContractException.class);
    }

    @Test
    void actionCitationRejectsWrongLocatorAndLawEvidence() {
        AnalysisEvidenceSnapshotData snapshot = scopedSnapshot(true);
        AnalysisAiResult result = new AnalysisAiResult();
        GroundedEvidenceCitation wrong = actionCitation(snapshot.sourceEvidence().get(0), "action-a");
        wrong.locator = "제2조";
        result.evidenceCitations = List.of(wrong);
        assertThatThrownBy(() -> validator.validateAnalysis(result, snapshot))
                .isInstanceOf(AiOutputContractException.class);
    }

    @Test
    void legacySnapshotDoesNotClaimActionScopedCitation() {
        AnalysisEvidenceSnapshotData snapshot = snapshot();
        ConsultationReportAiResult result = validReport(snapshot);
        result.evidenceCitations.get(0).target = "ACTION";
        result.evidenceCitations.get(0).actionId = "report-loss";

        assertThatThrownBy(() -> validator.validateReport(result, snapshot))
                .isInstanceOf(AiOutputContractException.class);
    }

    @Test
    void actionScopedReportKeepsApprovedActionIdentity() {
        AnalysisEvidenceSnapshotData snapshot = scopedSnapshot(true);
        ConsultationReportAiResult report = validReport(snapshot);
        report.firstAction.actionId = "action-a";
        report.firstAction.title = "A";
        report.firstAction.description = "A 설명";
        report.actionSteps = List.of(step("action-a", "A", "A 설명"),
                step("action-b", "B", "B 설명"));
        report.actionSteps.get(1).order = 2;
        report.requiredDocuments = List.of();
        report.evidenceCitations = List.of(
                actionCitation(snapshot.sourceEvidence().get(0), "action-a"),
                actionCitation(snapshot.sourceEvidence().get(0), "action-b"));

        assertThatCode(() -> validator.validateReport(report, snapshot)).doesNotThrowAnyException();
    }

    @Test
    void snapshotRelationBuilderRecordsExplicitSharedActionAssignments() {
        AnalysisEvidenceSnapshotData snapshot = scopedSnapshot(true);
        AnalysisEvidenceSnapshotService service = snapshotService(mock(FinancialActionPlanService.class),
                mock(AnalysisEvidenceSnapshotRepository.class));
        AnalysisEvidenceSnapshotData.SourceEvidence source = snapshot.sourceEvidence().get(0);
        FinancialActionPlanService.ValidatedEvidencePlan plan = new FinancialActionPlanService.ValidatedEvidencePlan(
                snapshot.actionPlan(), ProcedureVersionData.EvidenceContract.ACTION_SCOPED,
                List.of(new FinancialActionPlanService.ValidatedReference("ref-a",
                        List.of("action-a", "action-b"), source.rawSha256(),
                        new FinancialActionPlanData.EvidenceBinding("source", source.sourceDocumentId(),
                                source.documentVersion(), List.of(source.sourceChunkId()), null, null,
                                "PROCEDURE"))));

        assertThat(service.expectedActionRelations(plan, snapshot.sourceEvidence()))
                .extracting(AnalysisEvidenceSnapshotData.ActionEvidenceRelation::actionId)
                .containsExactly("action-a", "action-b");
    }

    @Test
    void snapshotRelationBuilderRejectsRawHashMismatch() {
        AnalysisEvidenceSnapshotData snapshot = scopedSnapshot(true);
        AnalysisEvidenceSnapshotService service = snapshotService(mock(FinancialActionPlanService.class),
                mock(AnalysisEvidenceSnapshotRepository.class));
        AnalysisEvidenceSnapshotData.SourceEvidence source = snapshot.sourceEvidence().get(0);
        FinancialActionPlanService.ValidatedEvidencePlan plan = new FinancialActionPlanService.ValidatedEvidencePlan(
                snapshot.actionPlan(), ProcedureVersionData.EvidenceContract.ACTION_SCOPED,
                List.of(new FinancialActionPlanService.ValidatedReference("ref-a",
                        List.of("action-a", "action-b"), "different-hash",
                        new FinancialActionPlanData.EvidenceBinding("source", source.sourceDocumentId(),
                                source.documentVersion(), List.of(source.sourceChunkId()), null, null,
                                "PROCEDURE"))));

        assertThatThrownBy(() -> service.expectedActionRelations(plan, snapshot.sourceEvidence()))
                .isInstanceOf(GroundedEvidenceUnavailableException.class);
    }

    @Test
    void oldActionScopedSnapshotWithoutRelationsCannotBeLoadedAsReady() throws Exception {
        AnalysisEvidenceSnapshotData scoped = scopedSnapshot(true);
        AnalysisEvidenceSnapshotData old = new AnalysisEvidenceSnapshotData(scoped.id(),
                scoped.consultationId(), scoped.analysisJobId(), scoped.caseInputRevision(),
                scoped.followUpAnswerRevision(), scoped.procedureVersionId(),
                scoped.financialActionPlanId(), scoped.retrievalGenerationId(),
                scoped.retrievalGenerationKey(), scoped.retrievalModelIdentifier(),
                scoped.retrievalModelRevision(), scoped.retrievalTokenizerIdentifier(),
                scoped.retrievalTokenizerRevision(), scoped.retrievalEncodingConfigJson(),
                scoped.retrievalIndexConfigJson(), scoped.retrievalCorpusSnapshotSha256(),
                scoped.actionPlan(), scoped.sourceEvidence(), scoped.reviewedLawEvidence(),
                scoped.snapshotRevision(), scoped.status(), scoped.capturedAt(), scoped.scenario());
        FinancialActionPlanService plans = mock(FinancialActionPlanService.class);
        AnalysisEvidenceSnapshotRepository repository = mock(AnalysisEvidenceSnapshotRepository.class);
        AnalysisEvidenceSnapshot entity = mock(AnalysisEvidenceSnapshot.class);
        ProcedureVersion procedure = mock(ProcedureVersion.class);
        FinancialActionPlan plan = mock(FinancialActionPlan.class);
        RetrievalGeneration generation = mock(RetrievalGeneration.class);
        when(repository.findById(old.id())).thenReturn(Optional.of(entity));
        when(entity.getStatus()).thenReturn(AnalysisEvidenceSnapshotStatus.READY);
        when(entity.getId()).thenReturn(old.id());
        when(entity.getSnapshotJson()).thenReturn(JsonMapper.builder().build().writeValueAsString(old));
        when(entity.getProcedureVersion()).thenReturn(procedure);
        when(entity.getFinancialActionPlan()).thenReturn(plan);
        when(entity.getRetrievalGeneration()).thenReturn(generation);
        when(procedure.getId()).thenReturn(old.procedureVersionId());
        when(plan.getId()).thenReturn(old.financialActionPlanId());
        when(generation.getId()).thenReturn(old.retrievalGenerationId());
        when(plans.validatedEvidencePlan(old.financialActionPlanId())).thenReturn(
                new FinancialActionPlanService.ValidatedEvidencePlan(old.actionPlan(),
                        ProcedureVersionData.EvidenceContract.ACTION_SCOPED, List.of()));

        assertThatThrownBy(() -> snapshotService(plans, repository).loadForId(old.id()))
                .isInstanceOf(GroundedEvidenceUnavailableException.class)
                .hasMessageContaining("relation");
    }

    @Test
    void currentActionScopedSnapshotReplaysWithItsExactSourceAndRelation() throws Exception {
        AnalysisEvidenceSnapshotData fixture = scopedSnapshot(true);
        AnalysisEvidenceSnapshotData.SourceEvidence source = fixture.sourceEvidence().get(0);
        FinancialActionPlanData.EvidenceBinding binding = new FinancialActionPlanData.EvidenceBinding(
                "source", source.sourceDocumentId(), source.documentVersion(),
                List.of(source.sourceChunkId()), null, null, "PROCEDURE");
        FinancialActionPlanData base = fixture.actionPlan();
        FinancialActionPlanData plan = new FinancialActionPlanData(base.id(), base.consultationId(),
                base.confirmedCaseSnapshotId(), base.procedureVersionId(), base.scenario(),
                base.procedureVersion(), base.caseInputRevision(), base.followUpAnswerRevision(),
                base.status(), base.actions(), base.requiredDocuments(), List.of(binding),
                base.unresolvedFacts(), base.coverageGaps(), base.warnings());
        AnalysisEvidenceSnapshotData stored = new AnalysisEvidenceSnapshotData(fixture.id(),
                fixture.consultationId(), fixture.analysisJobId(), fixture.caseInputRevision(),
                fixture.followUpAnswerRevision(), fixture.procedureVersionId(), fixture.financialActionPlanId(),
                fixture.retrievalGenerationId(), fixture.retrievalGenerationKey(),
                fixture.retrievalModelIdentifier(), fixture.retrievalModelRevision(),
                fixture.retrievalTokenizerIdentifier(), fixture.retrievalTokenizerRevision(),
                fixture.retrievalEncodingConfigJson(), fixture.retrievalIndexConfigJson(),
                fixture.retrievalCorpusSnapshotSha256(), plan, fixture.sourceEvidence(),
                fixture.reviewedLawEvidence(), fixture.snapshotRevision(), fixture.status(),
                fixture.capturedAt(), fixture.scenario(), fixture.actionEvidenceRelations());
        FinancialActionPlanService plans = mock(FinancialActionPlanService.class);
        AnalysisEvidenceSnapshotRepository snapshots = mock(AnalysisEvidenceSnapshotRepository.class);
        SourceChunkRepository chunks = mock(SourceChunkRepository.class);
        SourceChunkIndexingRepository indexings = mock(SourceChunkIndexingRepository.class);
        ActiveRetrievalGenerationPersistenceService active =
                mock(ActiveRetrievalGenerationPersistenceService.class);
        AnalysisEvidenceSnapshot row = mock(AnalysisEvidenceSnapshot.class);
        ProcedureVersion procedure = mock(ProcedureVersion.class);
        FinancialActionPlan planRow = mock(FinancialActionPlan.class);
        RetrievalGeneration generation = mock(RetrievalGeneration.class);
        SourceChunk chunk = mock(SourceChunk.class);
        SourceDocument document = mock(SourceDocument.class);
        SourceChunkIndexing indexing = mock(SourceChunkIndexing.class);
        when(snapshots.findById(stored.id())).thenReturn(Optional.of(row));
        when(row.getStatus()).thenReturn(AnalysisEvidenceSnapshotStatus.READY);
        when(row.getId()).thenReturn(stored.id());
        when(row.getSnapshotJson()).thenReturn(JsonMapper.builder().build().writeValueAsString(stored));
        when(row.getProcedureVersion()).thenReturn(procedure);
        when(row.getFinancialActionPlan()).thenReturn(planRow);
        when(row.getRetrievalGeneration()).thenReturn(generation);
        when(procedure.getId()).thenReturn(stored.procedureVersionId());
        when(planRow.getId()).thenReturn(stored.financialActionPlanId());
        when(generation.getId()).thenReturn(stored.retrievalGenerationId());
        when(generation.getStatus()).thenReturn(RetrievalGenerationStatus.READY);
        when(active.current()).thenReturn(Optional.of(generation));
        when(plans.validatedEvidencePlan(plan.id())).thenReturn(
                new FinancialActionPlanService.ValidatedEvidencePlan(plan,
                        ProcedureVersionData.EvidenceContract.ACTION_SCOPED,
                        List.of(new FinancialActionPlanService.ValidatedReference("ref-a",
                                List.of("action-a", "action-b"), source.rawSha256(), binding))));
        when(chunks.findWithDocumentById(source.sourceChunkId())).thenReturn(Optional.of(chunk));
        when(chunk.getReviewStatus()).thenReturn(SourceChunkReviewStatus.APPROVED);
        when(chunk.getSourceDocument()).thenReturn(document);
        when(document.getStatus()).thenReturn(SourceDocumentStatus.ACTIVE);
        when(document.getId()).thenReturn(source.sourceDocumentId());
        when(document.getDocumentVersion()).thenReturn(source.documentVersion());
        when(document.getRawSha256()).thenReturn(source.rawSha256());
        when(document.getContentSha256()).thenReturn(source.contentSha256());
        when(document.getOrganizationName()).thenReturn(source.organizationName());
        when(document.getOfficialDomain()).thenReturn(source.officialDomain());
        when(document.getCanonicalUrl()).thenReturn(source.canonicalUrl());
        when(document.getResolvedUrl()).thenReturn(source.resolvedUrl());
        when(document.getTitle()).thenReturn(source.title());
        when(chunk.getId()).thenReturn(source.sourceChunkId());
        when(chunk.getSequence()).thenReturn(source.sequence());
        when(chunk.getArticleReference()).thenReturn(source.articleReference());
        when(chunk.getPageReference()).thenReturn(source.pageReference());
        when(chunk.getLocator()).thenReturn(source.locator());
        when(chunk.getBody()).thenReturn(source.body());
        when(indexings.findBySourceChunk_IdAndRetrievalGeneration_Id(
                source.sourceChunkId(), generation.getId())).thenReturn(Optional.of(indexing));
        when(indexing.getIndexingStatus()).thenReturn(SourceChunkIndexingStatus.READY);
        AnalysisEvidenceSnapshotService service = snapshotService(plans, snapshots, chunks, indexings, active);

        assertThat(service.loadForId(stored.id()).actionEvidenceRelations())
                .containsExactlyElementsOf(stored.actionEvidenceRelations());
    }

    private AnalysisEvidenceSnapshotService snapshotService(FinancialActionPlanService plans,
                                                              AnalysisEvidenceSnapshotRepository snapshots) {
        return snapshotService(plans, snapshots, mock(SourceChunkRepository.class),
                mock(SourceChunkIndexingRepository.class),
                mock(ActiveRetrievalGenerationPersistenceService.class));
    }

    private AnalysisEvidenceSnapshotService snapshotService(FinancialActionPlanService plans,
            AnalysisEvidenceSnapshotRepository snapshots, SourceChunkRepository chunks,
            SourceChunkIndexingRepository indexings,
            ActiveRetrievalGenerationPersistenceService active) {
        return new AnalysisEvidenceSnapshotService(plans,
                mock(FinancialActionPlanRepository.class), mock(ProcedureVersionRepository.class),
                mock(ConfirmedCaseSnapshotRepository.class), chunks,
                indexings, active,
                mock(RetrievalGenerationRepository.class), mock(AnalysisJobRepository.class),
                snapshots, mock(ReviewedCardLawEvidenceService.class), JsonMapper.builder().build(),
                mock(PlatformTransactionManager.class));
    }

    private AnalysisEvidenceSnapshotData scopedSnapshot(boolean shared) {
        UUID documentId = UUID.randomUUID();
        UUID chunkId = UUID.randomUUID();
        String evidenceId = "source:" + chunkId;
        FinancialActionPlanData plan = new FinancialActionPlanData(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "VOICE_PHISHING_SUSPICIOUS_TRANSFER", 2, 1, 1, PlanStatus.READY,
                List.of(new FinancialActionPlanData.Action("action-a", 1, "A", "A 설명",
                                ConditionResult.TRUE, null),
                        new FinancialActionPlanData.Action("action-b", 2, "B", "B 설명",
                                ConditionResult.TRUE, null)),
                List.of(), List.of(), List.of(), List.of(), List.of());
        List<AnalysisEvidenceSnapshotData.ActionEvidenceRelation> relations = new ArrayList<>();
        relations.add(new AnalysisEvidenceSnapshotData.ActionEvidenceRelation(
                "action-a", "ref-a", evidenceId, "제1조"));
        if (shared) relations.add(new AnalysisEvidenceSnapshotData.ActionEvidenceRelation(
                "action-b", "ref-a", evidenceId, "제1조"));
        return new AnalysisEvidenceSnapshotData(UUID.randomUUID(), plan.consultationId(),
                UUID.randomUUID(), 1, 1, plan.procedureVersionId(), plan.id(), UUID.randomUUID(),
                "generation", "model", "revision", "tokenizer", "tokenizer-revision",
                "{}", "{}", "hash", plan,
                List.of(new AnalysisEvidenceSnapshotData.SourceEvidence(evidenceId, documentId,
                        1, "raw", "content", "publisher", "official.example", "url", "url",
                        "Document", chunkId, 1, "제1조", "p.1", "제1조", "내용")),
                List.of(), 1, AnalysisEvidenceSnapshotStatus.READY,
                OffsetDateTime.now(ZoneOffset.UTC), null, relations);
    }

    private GroundedEvidenceCitation actionCitation(
            AnalysisEvidenceSnapshotData.SourceEvidence evidence, String actionId) {
        GroundedEvidenceCitation citation = citation(evidence.evidenceId(), evidence.locator());
        citation.target = "ACTION";
        citation.actionId = actionId;
        return citation;
    }

    private AnalysisEvidenceSnapshotData snapshot() {
        UUID sourceDocumentId = UUID.randomUUID();
        UUID chunkId = UUID.randomUUID();
        FinancialActionPlanData plan = new FinancialActionPlanData(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "CARD_LOSS_UNAUTHORIZED_USE", 1, 1, 1, PlanStatus.READY,
                List.of(new FinancialActionPlanData.Action(
                        "report-loss", 7, "분실·도난 신고", "카드사 공식 채널에 신고하세요.",
                        ConditionResult.TRUE, "kb-card-loss")),
                List.of(new FinancialActionPlanData.Document(
                        "incident-record", "거래 내역", "CONDITIONAL", ConditionResult.TRUE, "kb-form")),
                List.of(), List.of(), List.of(), List.of());
        return new AnalysisEvidenceSnapshotData(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 1, 1,
                plan.procedureVersionId(), plan.id(), UUID.randomUUID(), "generation",
                "model", "revision", "tokenizer", "tokenizer-revision", "{}", "{}", "hash",
                plan,
                List.of(new AnalysisEvidenceSnapshotData.SourceEvidence(
                        "source:" + chunkId, sourceDocumentId, 1, "raw", "content",
                        "publisher", "official.example", "https://official.example/source",
                        "https://official.example/source", "Document",
                        chunkId, 1, "제40조", "p.30", "제40조", "분실 신고")),
                List.of(), 1, AnalysisEvidenceSnapshotStatus.READY,
                OffsetDateTime.now(ZoneOffset.UTC));
    }

    private ConsultationReportAiResult validReport(AnalysisEvidenceSnapshotData snapshot) {
        ConsultationReportAiResult result = new ConsultationReportAiResult();
        String evidenceId = snapshot.sourceEvidence().get(0).evidenceId();
        GroundedEvidenceCitation citation = citation(evidenceId, "제40조");
        result.evidenceCitations = List.of(citation);
        result.firstAction = new ConsultationReportAiResult.FirstAction();
        result.firstAction.actionId = "report-loss";
        result.firstAction.title = "분실·도난 신고";
        result.firstAction.description = "카드사 공식 채널에 신고하세요.";
        result.actionSteps = List.of(step("report-loss", "분실·도난 신고", "카드사 공식 채널에 신고하세요."));
        result.requiredDocuments = List.of(document("incident-record", "거래 내역"));
        return result;
    }

    private ConsultationReportAiResult.ActionStep step(String id, String title, String description) {
        ConsultationReportAiResult.ActionStep step = new ConsultationReportAiResult.ActionStep();
        step.actionId = id;
        step.order = 1;
        step.title = title;
        step.description = description;
        return step;
    }

    private ConsultationReportAiResult.RequiredDocument document(String id, String name) {
        ConsultationReportAiResult.RequiredDocument document = new ConsultationReportAiResult.RequiredDocument();
        document.documentId = id;
        document.name = name;
        return document;
    }

    private GroundedEvidenceCitation citation(String id, String locator) {
        GroundedEvidenceCitation citation = new GroundedEvidenceCitation();
        citation.evidenceId = id;
        citation.locator = locator;
        return citation;
    }
}
