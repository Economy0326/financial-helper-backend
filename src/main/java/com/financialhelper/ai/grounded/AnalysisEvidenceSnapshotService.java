package com.financialhelper.ai.grounded;

import com.financialhelper.ai.analysis.AnalysisData;
import com.financialhelper.ai.analysis.AnalysisJob;
import com.financialhelper.ai.analysis.AnalysisJobRepository;
import com.financialhelper.ai.analysis.AnalysisJobStatus;
import com.financialhelper.consultation.Consultation;
import com.financialhelper.procedure.FinancialActionPlan;
import com.financialhelper.procedure.FinancialActionPlanData;
import com.financialhelper.procedure.FinancialActionPlanRepository;
import com.financialhelper.procedure.FinancialActionPlanService;
import com.financialhelper.procedure.PlanStatus;
import com.financialhelper.procedure.ProcedureVersion;
import com.financialhelper.procedure.ProcedureVersionRepository;
import com.financialhelper.retrieval.ConfirmedCaseSnapshot;
import com.financialhelper.retrieval.ConfirmedCaseSnapshotData;
import com.financialhelper.retrieval.ConfirmedCaseSnapshotRepository;
import com.financialhelper.source.ActiveRetrievalGenerationPersistenceService;
import com.financialhelper.source.RetrievalGeneration;
import com.financialhelper.source.RetrievalGenerationRepository;
import com.financialhelper.source.RetrievalGenerationStatus;
import com.financialhelper.source.SourceChunk;
import com.financialhelper.source.SourceChunkIndexing;
import com.financialhelper.source.SourceChunkIndexingRepository;
import com.financialhelper.source.SourceChunkIndexingStatus;
import com.financialhelper.source.SourceChunkRepository;
import com.financialhelper.source.SourceChunkReviewStatus;
import com.financialhelper.source.SourceDocumentStatus;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Captures the exact deterministic inputs used by a CARD analysis.  The
 * preparation method performs no external call inside a database transaction;
 * only the final short save transaction locks and rechecks current state.
 */
@Service
public class AnalysisEvidenceSnapshotService {
    private final FinancialActionPlanService actionPlanService;
    private final FinancialActionPlanRepository actionPlanRepository;
    private final ProcedureVersionRepository procedureRepository;
    private final ConfirmedCaseSnapshotRepository caseSnapshotRepository;
    private final SourceChunkRepository sourceChunkRepository;
    private final SourceChunkIndexingRepository indexingRepository;
    private final ActiveRetrievalGenerationPersistenceService activeGeneration;
    private final RetrievalGenerationRepository generationRepository;
    private final AnalysisJobRepository analysisJobRepository;
    private final AnalysisEvidenceSnapshotRepository snapshotRepository;
    private final ReviewedCardLawEvidenceService lawEvidenceService;
    private final JsonMapper jsonMapper;
    private final TransactionTemplate transactionTemplate;

    public AnalysisEvidenceSnapshotService(
            FinancialActionPlanService actionPlanService,
            FinancialActionPlanRepository actionPlanRepository,
            ProcedureVersionRepository procedureRepository,
            ConfirmedCaseSnapshotRepository caseSnapshotRepository,
            SourceChunkRepository sourceChunkRepository,
            SourceChunkIndexingRepository indexingRepository,
            ActiveRetrievalGenerationPersistenceService activeGeneration,
            RetrievalGenerationRepository generationRepository,
            AnalysisJobRepository analysisJobRepository,
            AnalysisEvidenceSnapshotRepository snapshotRepository,
            ReviewedCardLawEvidenceService lawEvidenceService,
            JsonMapper jsonMapper,
            org.springframework.transaction.PlatformTransactionManager transactionManager
    ) {
        this.actionPlanService = actionPlanService;
        this.actionPlanRepository = actionPlanRepository;
        this.procedureRepository = procedureRepository;
        this.caseSnapshotRepository = caseSnapshotRepository;
        this.sourceChunkRepository = sourceChunkRepository;
        this.indexingRepository = indexingRepository;
        this.activeGeneration = activeGeneration;
        this.generationRepository = generationRepository;
        this.analysisJobRepository = analysisJobRepository;
        this.snapshotRepository = snapshotRepository;
        this.lawEvidenceService = lawEvidenceService;
        this.jsonMapper = jsonMapper;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    /** Returns null for legacy consultations without a supported scenario. */
    public AnalysisEvidenceSnapshotData prepare(AnalysisData.Snapshot input) {
        if (input == null || input.scenario() == null
                || input.scenario() == com.financialhelper.consultation.ConsultationScenario.UNKNOWN) {
            return null;
        }

        FinancialActionPlanData plan = actionPlanService.buildForCurrent(input.consultationId());
        if (plan.status() != PlanStatus.READY || plan.id() == null) {
            throw new GroundedEvidenceUnavailableException(
                    "financial action plan is not READY: " + plan.status());
        }

        ConfirmedCaseSnapshot caseSnapshot = caseSnapshotRepository
                .findByConsultation_IdAndCaseInputRevisionAndFollowUpAnswerRevision(
                        input.consultationId(), input.caseInputRevision(), input.followUpAnswerRevision())
                .orElseThrow(() -> new GroundedEvidenceUnavailableException(
                        "confirmed case snapshot is unavailable"));
        ConfirmedCaseSnapshotData caseData = toCaseData(caseSnapshot);
        LocalDate incidentDate = incidentDate(caseData, input.scenario());

        RetrievalGeneration generation = activeGeneration.current().orElseThrow(() ->
                new GroundedEvidenceUnavailableException("active retrieval generation is unavailable"));
        if (generation.getStatus() != RetrievalGenerationStatus.READY) {
            throw new GroundedEvidenceUnavailableException("active retrieval generation is not READY");
        }

        List<AnalysisEvidenceSnapshotData.SourceEvidence> sourceEvidence =
                loadSourceEvidence(plan, generation);
        if (sourceEvidence.isEmpty()) {
            throw new GroundedEvidenceUnavailableException("official evidence is unavailable");
        }

        // This is intentionally outside saveSnapshot's transaction.  A live
        // law API call can be slow or fail and must never hold a DB lock.
        List<AnalysisEvidenceSnapshotData.ReviewedLawEvidence> lawEvidence =
                lawEvidenceService.loadForScenario(input.scenario().name(), incidentDate);
        if (lawEvidence.isEmpty() && input.scenario()
                == com.financialhelper.consultation.ConsultationScenario.CARD_LOSS_UNAUTHORIZED_USE) {
            throw new GroundedEvidenceUnavailableException("reviewed CARD law evidence is empty");
        }

        AnalysisEvidenceSnapshotData draft = new AnalysisEvidenceSnapshotData(
                null,
                input.consultationId(),
                input.jobId(),
                input.caseInputRevision(),
                input.followUpAnswerRevision(),
                plan.procedureVersionId(),
                plan.id(),
                generation.getId(),
                generation.getGenerationKey(),
                generation.getModelIdentifier(),
                generation.getModelRevision(),
                generation.getTokenizerIdentifier(),
                generation.getTokenizerRevision(),
                generation.getEncodingConfigJson(),
                generation.getIndexConfigJson(),
                generation.getCorpusSnapshotSha256(),
                plan,
                sourceEvidence,
                lawEvidence,
                1,
                AnalysisEvidenceSnapshotStatus.READY,
                OffsetDateTime.now(ZoneOffset.UTC),
                input.scenario()
        );
        AnalysisEvidenceSnapshotData saved = transactionTemplate.execute(status -> saveIfCurrent(draft));
        if (saved == null) {
            throw new GroundedEvidenceUnavailableException("analysis evidence snapshot could not be saved");
        }
        return saved;
    }

    @Transactional(readOnly = true)
    public AnalysisEvidenceSnapshotData loadForJob(
            UUID jobId,
            long caseInputRevision,
            long followUpAnswerRevision
    ) {
        return snapshotRepository
                .findByAnalysisJob_IdAndCaseInputRevisionAndFollowUpAnswerRevision(
                        jobId, caseInputRevision, followUpAnswerRevision)
                .filter(snapshot -> snapshot.getStatus() == AnalysisEvidenceSnapshotStatus.READY)
                .map(this::toData)
                .orElseThrow(() -> new GroundedEvidenceUnavailableException(
                        "analysis evidence snapshot is unavailable"));
    }

    @Transactional(readOnly = true)
    public AnalysisEvidenceSnapshotData loadForId(UUID snapshotId) {
        return snapshotRepository.findById(snapshotId)
                .filter(snapshot -> snapshot.getStatus() == AnalysisEvidenceSnapshotStatus.READY)
                .map(this::toData)
                .orElseThrow(() -> new GroundedEvidenceUnavailableException(
                        "analysis evidence snapshot is unavailable"));
    }

    /** Called at the analysis save boundary to reject stale evidence. */
    @Transactional(readOnly = true)
    public void assertCurrent(AnalysisEvidenceSnapshotData snapshot) {
        AnalysisJob job = analysisJobRepository.findById(snapshot.analysisJobId())
                .orElseThrow(() -> new GroundedEvidenceUnavailableException("analysis job is unavailable"));
        Consultation consultation = job.getConsultation();
        if (job.getStatus() != AnalysisJobStatus.PROCESSING
                || consultation.getCaseInputRevision() != snapshot.caseInputRevision()
                || consultation.getFollowUpAnswerRevision() != snapshot.followUpAnswerRevision()) {
            throw new GroundedEvidenceUnavailableException("analysis evidence snapshot is stale");
        }
        RetrievalGeneration current = activeGeneration.current().orElse(null);
        if (current == null || !current.getId().equals(snapshot.retrievalGenerationId())
                || current.getStatus() != RetrievalGenerationStatus.READY) {
            throw new GroundedEvidenceUnavailableException("retrieval generation changed");
        }
    }

    /** Report generation may read a completed analysis job, but the case and
     * active generation still have to be the same as the captured snapshot. */
    @Transactional(readOnly = true)
    public void assertCurrentForReport(AnalysisEvidenceSnapshotData snapshot) {
        AnalysisJob job = analysisJobRepository.findById(snapshot.analysisJobId())
                .orElseThrow(() -> new GroundedEvidenceUnavailableException("analysis job is unavailable"));
        Consultation consultation = job.getConsultation();
        if (job.getStatus() != AnalysisJobStatus.COMPLETED
                || consultation.getCaseInputRevision() != snapshot.caseInputRevision()
                || consultation.getFollowUpAnswerRevision() != snapshot.followUpAnswerRevision()) {
            throw new GroundedEvidenceUnavailableException("report evidence snapshot is stale");
        }
        RetrievalGeneration current = activeGeneration.current().orElse(null);
        if (current == null || !current.getId().equals(snapshot.retrievalGenerationId())
                || current.getStatus() != RetrievalGenerationStatus.READY) {
            throw new GroundedEvidenceUnavailableException("report retrieval generation changed");
        }
    }

    @Transactional(readOnly = true)
    protected List<AnalysisEvidenceSnapshotData.SourceEvidence> loadSourceEvidence(
            FinancialActionPlanData plan,
            RetrievalGeneration generation
    ) {
        List<AnalysisEvidenceSnapshotData.SourceEvidence> result = new ArrayList<>();
        for (FinancialActionPlanData.EvidenceBinding binding : plan.evidence()) {
            for (UUID chunkId : binding.sourceChunkIds()) {
                SourceChunk chunk = sourceChunkRepository.findWithDocumentById(chunkId)
                        .orElseThrow(() -> new GroundedEvidenceUnavailableException(
                                "source chunk is unavailable: " + chunkId));
                if (chunk.getReviewStatus() != SourceChunkReviewStatus.APPROVED
                        || chunk.getSourceDocument().getStatus() != SourceDocumentStatus.ACTIVE
                        || !chunk.getSourceDocument().getId().equals(binding.sourceDocumentId())
                        || chunk.getSourceDocument().getDocumentVersion() != binding.documentVersion()) {
                    throw new GroundedEvidenceUnavailableException("source evidence changed");
                }
                SourceChunkIndexing indexing = indexingRepository
                        .findBySourceChunk_IdAndRetrievalGeneration_Id(chunkId, generation.getId())
                        .orElseThrow(() -> new GroundedEvidenceUnavailableException(
                                "source chunk is not indexed in active generation"));
                if (indexing.getIndexingStatus() != SourceChunkIndexingStatus.READY) {
                    throw new GroundedEvidenceUnavailableException("source chunk index is not READY");
                }
                result.add(new AnalysisEvidenceSnapshotData.SourceEvidence(
                        "source:" + chunkId,
                        chunk.getSourceDocument().getId(),
                        chunk.getSourceDocument().getDocumentVersion(),
                        chunk.getSourceDocument().getRawSha256(),
                        chunk.getSourceDocument().getContentSha256(),
                        chunk.getSourceDocument().getOrganizationName(),
                        chunk.getSourceDocument().getOfficialDomain(),
                        chunk.getSourceDocument().getCanonicalUrl(),
                        chunk.getSourceDocument().getResolvedUrl(),
                        chunk.getSourceDocument().getTitle(),
                        chunk.getId(),
                        chunk.getSequence(),
                        chunk.getArticleReference(),
                        chunk.getPageReference(),
                        chunk.getLocator(),
                        chunk.getBody()));
            }
        }
        return List.copyOf(result);
    }

    @Transactional
    protected AnalysisEvidenceSnapshotData saveIfCurrent(AnalysisEvidenceSnapshotData draft) {
        AnalysisJob job = analysisJobRepository.findForUpdateById(draft.analysisJobId())
                .orElseThrow(() -> new GroundedEvidenceUnavailableException("analysis job is unavailable"));
        Consultation consultation = job.getConsultation();
        if (job.getStatus() != AnalysisJobStatus.PROCESSING
                || consultation.getCaseInputRevision() != draft.caseInputRevision()
                || consultation.getFollowUpAnswerRevision() != draft.followUpAnswerRevision()) {
            throw new GroundedEvidenceUnavailableException("analysis evidence snapshot is stale");
        }
        AnalysisEvidenceSnapshot existing = snapshotRepository
                .findByAnalysisJob_IdAndCaseInputRevisionAndFollowUpAnswerRevision(
                        draft.analysisJobId(), draft.caseInputRevision(), draft.followUpAnswerRevision())
                .orElse(null);
        if (existing != null) {
            if (existing.getStatus() != AnalysisEvidenceSnapshotStatus.READY) {
                throw new GroundedEvidenceUnavailableException("stored analysis evidence snapshot is invalid");
            }
            if (!existing.getRetrievalGeneration().getId().equals(draft.retrievalGenerationId())
                    || !existing.getProcedureVersion().getId().equals(draft.procedureVersionId())
                    || !existing.getFinancialActionPlan().getId().equals(draft.financialActionPlanId())) {
                throw new GroundedEvidenceUnavailableException(
                        "existing evidence snapshot belongs to a different procedure or generation");
            }
            return toData(existing);
        }
        ProcedureVersion procedure = procedureRepository.findById(draft.procedureVersionId())
                .orElseThrow(() -> new GroundedEvidenceUnavailableException("procedure is unavailable"));
        FinancialActionPlan plan = actionPlanRepository.findById(draft.financialActionPlanId())
                .orElseThrow(() -> new GroundedEvidenceUnavailableException("action plan is unavailable"));
        RetrievalGeneration generation = generationRepository.findById(draft.retrievalGenerationId())
                .filter(item -> item.getStatus() == RetrievalGenerationStatus.READY)
                .orElseThrow(() -> new GroundedEvidenceUnavailableException("generation is unavailable"));
        try {
            String json = jsonMapper.writeValueAsString(draft);
            AnalysisEvidenceSnapshot saved = snapshotRepository.saveAndFlush(
                    new AnalysisEvidenceSnapshot(
                            consultation,
                            job,
                            procedure,
                            plan,
                            generation,
                            draft.caseInputRevision(),
                            draft.followUpAnswerRevision(),
                            draft.snapshotRevision(),
                            draft.status(),
                            json,
                            draft.capturedAt()));
            return withId(draft, saved.getId());
        } catch (JacksonException exception) {
            throw new GroundedEvidenceUnavailableException("analysis evidence snapshot could not be serialized", exception);
        }
    }

    private AnalysisEvidenceSnapshotData toData(AnalysisEvidenceSnapshot entity) {
        try {
            AnalysisEvidenceSnapshotData data = jsonMapper.readValue(
                    entity.getSnapshotJson(), AnalysisEvidenceSnapshotData.class);
            return withId(data, entity.getId());
        } catch (JacksonException exception) {
            throw new GroundedEvidenceUnavailableException("stored analysis evidence snapshot is malformed", exception);
        }
    }

    private AnalysisEvidenceSnapshotData withId(AnalysisEvidenceSnapshotData data, UUID id) {
        return new AnalysisEvidenceSnapshotData(
                id, data.consultationId(), data.analysisJobId(), data.caseInputRevision(),
                data.followUpAnswerRevision(), data.procedureVersionId(), data.financialActionPlanId(),
                data.retrievalGenerationId(), data.retrievalGenerationKey(), data.retrievalModelIdentifier(),
                data.retrievalModelRevision(), data.retrievalTokenizerIdentifier(), data.retrievalTokenizerRevision(),
                data.retrievalEncodingConfigJson(), data.retrievalIndexConfigJson(), data.retrievalCorpusSnapshotSha256(),
                data.actionPlan(), data.sourceEvidence(), data.reviewedLawEvidence(), data.snapshotRevision(),
                data.status(), data.capturedAt(), data.scenario());
    }

    private ConfirmedCaseSnapshotData toCaseData(ConfirmedCaseSnapshot entity) {
        try {
            ConfirmedCaseSnapshotData.Fact[] facts = jsonMapper.readValue(
                    entity.getFactsJson(), ConfirmedCaseSnapshotData.Fact[].class);
            String[] missing = jsonMapper.readValue(
                    entity.getMissingFactsJson(), String[].class);
            return new ConfirmedCaseSnapshotData(
                    entity.getId(), entity.getConsultation().getId(),
                    entity.getCaseInputRevision(), entity.getFollowUpAnswerRevision(),
                    facts == null ? List.of() : List.of(facts),
                    missing == null ? List.of() : List.of(missing), entity.getCreatedAt());
        } catch (JacksonException exception) {
            throw new GroundedEvidenceUnavailableException("confirmed case facts are malformed", exception);
        }
    }

    private LocalDate incidentDate(
            ConfirmedCaseSnapshotData data,
            com.financialhelper.consultation.ConsultationScenario scenario
    ) {
        return data.facts().stream()
                .filter(fact -> "incidentDate".equals(fact.key())
                        || (scenario == com.financialhelper.consultation.ConsultationScenario.UNAUTHORIZED_ACCOUNT_TRANSFER
                        && "transactionDate".equals(fact.key())))
                .map(ConfirmedCaseSnapshotData.Fact::value)
                .filter(value -> value != null && !value.isBlank() && !"UNKNOWN".equalsIgnoreCase(value))
                .findFirst()
                .map(value -> {
                    try {
                        return LocalDate.parse(value);
                    } catch (RuntimeException exception) {
                        throw new GroundedEvidenceUnavailableException("incidentDate is invalid", exception);
                    }
                })
                .orElseGet(() -> {
                    // CARD reviewed law evidence requires a date to select an
                    // applicable legal version. Breadth procedures currently
                    // have no reviewed law allowlist, so an UNKNOWN date is
                    // retained as UNKNOWN and does not block their approved
                    // source evidence snapshot.
                    if (scenario == com.financialhelper.consultation.ConsultationScenario.CARD_LOSS_UNAUTHORIZED_USE) {
                        throw new GroundedEvidenceUnavailableException(
                                "incidentDate is required for reviewed law applicability");
                    }
                    return null;
                });
    }
}
