package com.financialhelper.procedure;

import com.financialhelper.source.ActiveRetrievalGenerationPersistenceService;
import com.financialhelper.source.RetrievalGeneration;
import com.financialhelper.source.SourceChunk;
import com.financialhelper.source.SourceChunkIndexing;
import com.financialhelper.source.SourceChunkIndexingRepository;
import com.financialhelper.source.SourceChunkIndexingStatus;
import com.financialhelper.source.SourceChunkRepository;
import com.financialhelper.source.SourceChunkReviewStatus;
import com.financialhelper.source.SourceDocument;
import com.financialhelper.source.SourceDocumentRepository;
import com.financialhelper.source.SourceDocumentStatus;
import com.financialhelper.source.SourceRegistry;
import com.financialhelper.source.SourceRegistryRepository;

import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/** Resolves Procedure evidence to the exact approved, active corpus rows. */
@Service
public class EvidenceBindingResolver {
    private final SourceRegistryRepository sourceRegistryRepository;
    private final SourceDocumentRepository sourceDocumentRepository;
    private final SourceChunkRepository sourceChunkRepository;
    private final SourceChunkIndexingRepository indexingRepository;
    private final ActiveRetrievalGenerationPersistenceService activeGeneration;

    public EvidenceBindingResolver(
            SourceRegistryRepository sourceRegistryRepository,
            SourceDocumentRepository sourceDocumentRepository,
            SourceChunkRepository sourceChunkRepository,
            SourceChunkIndexingRepository indexingRepository,
            ActiveRetrievalGenerationPersistenceService activeGeneration
    ) {
        this.sourceRegistryRepository = sourceRegistryRepository;
        this.sourceDocumentRepository = sourceDocumentRepository;
        this.sourceChunkRepository = sourceChunkRepository;
        this.indexingRepository = indexingRepository;
        this.activeGeneration = activeGeneration;
    }

    public Resolution resolve(
            List<ProcedureVersionData.EvidenceReference> references,
            LocalDate incidentDate
    ) {
        List<FinancialActionPlanData.EvidenceBinding> bindings = new ArrayList<>();
        List<String> gaps = new ArrayList<>();
        RetrievalGeneration generation = activeGeneration.current().orElse(null);
        if (generation == null
                || generation.getStatus() != com.financialhelper.source.RetrievalGenerationStatus.READY) {
            return new Resolution(List.of(), List.of("NO_ACTIVE_READY_GENERATION"));
        }
        for (ProcedureVersionData.EvidenceReference reference : references) {
            SourceRegistry registry = sourceRegistryRepository
                    .findBySourceKeyAndEnabledTrue(reference.sourceKey()).orElse(null);
            if (registry == null) {
                gaps.add("EVIDENCE_SOURCE_UNAVAILABLE:" + reference.sourceKey());
                continue;
            }
            SourceDocument document = sourceDocumentRepository
                    .findBySourceRegistry_IdAndStatus(registry.getId(), SourceDocumentStatus.ACTIVE)
                    .orElse(null);
            if (document == null
                    || document.getDocumentVersion() != reference.documentVersion()
                    || !reference.expectedRawSha256().equals(document.getRawSha256())) {
                gaps.add("EVIDENCE_VERSION_MISMATCH:" + reference.sourceKey());
                continue;
            }
            if (incidentDate != null && document.getApplicabilityStartDate() == null
                    && reference.historicalApplicabilityRequired()) {
                gaps.add("TEMPORAL_APPLICABILITY_UNKNOWN:" + reference.sourceKey());
                continue;
            }
            if (incidentDate != null && (document.getApplicabilityStartDate() != null
                    && incidentDate.isBefore(document.getApplicabilityStartDate())
                    || document.getApplicabilityEndDate() != null
                    && incidentDate.isAfter(document.getApplicabilityEndDate()))) {
                gaps.add("EVIDENCE_NOT_APPLICABLE:" + reference.sourceKey());
                continue;
            }
            List<SourceChunk> chunks = sourceChunkRepository
                    .findAllBySourceDocument_IdOrderBySequenceAsc(document.getId()).stream()
                    .filter(chunk -> chunk.getReviewStatus() == SourceChunkReviewStatus.APPROVED)
                    .filter(chunk -> matchesLocator(chunk, reference))
                    .filter(chunk -> isReady(chunk, generation))
                    .toList();
            if (chunks.isEmpty()) {
                gaps.add("EVIDENCE_CHUNK_UNAVAILABLE:" + reference.sourceKey());
                continue;
            }
            bindings.add(new FinancialActionPlanData.EvidenceBinding(
                    reference.sourceKey(), document.getId(), document.getDocumentVersion(),
                    chunks.stream().map(SourceChunk::getId).toList(), reference.articleReference(),
                    reference.pageReference(), reference.role()));
        }
        return new Resolution(List.copyOf(bindings), List.copyOf(gaps));
    }

    private boolean isReady(SourceChunk chunk, RetrievalGeneration generation) {
        return indexingRepository.findBySourceChunk_IdAndRetrievalGeneration_Id(
                        chunk.getId(), generation.getId())
                .map(SourceChunkIndexing::getIndexingStatus)
                .filter(status -> status == SourceChunkIndexingStatus.READY)
                .isPresent();
    }

    private boolean matchesLocator(SourceChunk chunk, ProcedureVersionData.EvidenceReference reference) {
        boolean articleMatch = reference.articleReference() == null
                || reference.articleReference().isBlank()
                || containsEither(chunk.getArticleReference(), reference.articleReference());
        boolean pageMatch = reference.pageReference() == null
                || reference.pageReference().isBlank()
                || containsEither(chunk.getPageReference(), reference.pageReference());
        return articleMatch && pageMatch;
    }

    private boolean containsEither(String actual, String expected) {
        return actual != null && (actual.contains(expected) || expected.contains(actual));
    }

    public record Resolution(
            List<FinancialActionPlanData.EvidenceBinding> bindings,
            List<String> coverageGaps
    ) {
        public Resolution {
            bindings = bindings == null ? List.of() : List.copyOf(bindings);
            coverageGaps = coverageGaps == null ? List.of() : List.copyOf(coverageGaps);
        }
    }
}
