package com.financialhelper.retrieval;

import com.financialhelper.source.RetrievalGeneration;
import com.financialhelper.source.RetrievalGenerationData;
import com.financialhelper.source.RetrievalGenerationPersistenceService;
import com.financialhelper.source.SourceChunk;
import com.financialhelper.source.SourceChunkRepository;
import com.financialhelper.source.SourceChunkingProperties;
import com.financialhelper.source.SourceChunkingService;
import com.financialhelper.source.SourceDocument;
import com.financialhelper.source.SourceDocumentRepository;
import com.financialhelper.source.SourceDocumentStatus;
import com.financialhelper.source.SourceHashing;
import com.financialhelper.source.SourceIngestionResult;
import com.financialhelper.source.SourceIngestionService;
import com.financialhelper.source.SourceRegistryRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Explicit activation boundary for the reviewed breadth MVP corpus.  The
 * registry remains a catalogue; only these hash-pinned documents are allowed
 * into the immutable generation, and every resulting chunk is approved here
 * as part of the development-stage human review decision.
 */
@Service
public class BreadthOfficialCorpusActivationService {

    public static final String FSC_ALERT_SOURCE = "fsc-2026-alert";
    public static final String FSC_PERSONAL_INFO_SOURCE = "fsc-personal-info";
    public static final String KISA_118_SOURCE = "kisa-118";
    public static final String POLICE_CAMPAIGN_SOURCE = "police-campaign";
    public static final String POLICE_REPORTING_SOURCE = "police-reporting";
    public static final String POLICE_RESPONSE_SOURCE = "police-response";

    private static final List<String> BREADTH_SOURCE_KEYS = List.of(
            FSC_ALERT_SOURCE,
            FSC_PERSONAL_INFO_SOURCE,
            KISA_118_SOURCE,
            POLICE_CAMPAIGN_SOURCE,
            POLICE_REPORTING_SOURCE,
            POLICE_RESPONSE_SOURCE
    );

    /* Hashes of the official originals reviewed for the MVP source freeze. */
    private static final Map<String, String> VERIFIED_RAW_SHA256 = Map.of(
            FSC_ALERT_SOURCE,
            "076b08600a9ad15661e60417dfcd84ff7633f8e76f0e32ef81b7aa55d986cd26",
            FSC_PERSONAL_INFO_SOURCE,
            "4b353ea0d28fd603be638b7553a43251675bf87c40dba16b1c7cbadcf4f615a6",
            KISA_118_SOURCE,
            "d19a008088637dbe49bbb0823a8556628a6c06ca2c251d21874c7bd9ab980fd3",
            POLICE_CAMPAIGN_SOURCE,
            "6390bea2f50a6a862718d6ae35b1eba0ee3c9e205fde47393fc6ba4fa847515a",
            POLICE_REPORTING_SOURCE,
            "f697a8f49d948f93cce16855c8894d338b77916c88cb7d62a0b5958f97c42215",
            POLICE_RESPONSE_SOURCE,
            "bb1801e9d63b26c1288ce5bb6419bb9da28ee5614a8138bcd690042db960a324"
    );

    private static final List<String> CARD_SOURCE_KEYS = List.of(
            CardOfficialCorpusActivationService.KB_TERMS_SOURCE,
            CardOfficialCorpusActivationService.KB_FORM_SOURCE,
            CardOfficialCorpusActivationService.C2_GUIDELINE_SOURCE,
            CardOfficialCorpusActivationService.KB_COMPENSATION_PROCESS_SOURCE,
            CardOfficialCorpusActivationService.KB_LOSS_REPORT_SOURCE,
            CardOfficialCorpusActivationService.KB_TERMS_AMENDMENT_SOURCE
    );

    private final SourceIngestionService ingestionService;
    private final SourceRegistryRepository sourceRegistryRepository;
    private final SourceDocumentRepository sourceDocumentRepository;
    private final SourceChunkingService chunkingService;
    private final SourceChunkRepository sourceChunkRepository;
    private final SourceChunkingProperties chunkingProperties;
    private final RetrievalGenerationPersistenceService generationPersistence;
    private final RetrievalGenerationIndexingService generationIndexing;
    private final KureRuntimeProperties kureProperties;

    public BreadthOfficialCorpusActivationService(
            SourceIngestionService ingestionService,
            SourceRegistryRepository sourceRegistryRepository,
            SourceDocumentRepository sourceDocumentRepository,
            SourceChunkingService chunkingService,
            SourceChunkRepository sourceChunkRepository,
            SourceChunkingProperties chunkingProperties,
            RetrievalGenerationPersistenceService generationPersistence,
            RetrievalGenerationIndexingService generationIndexing,
            KureRuntimeProperties kureProperties
    ) {
        this.ingestionService = ingestionService;
        this.sourceRegistryRepository = sourceRegistryRepository;
        this.sourceDocumentRepository = sourceDocumentRepository;
        this.chunkingService = chunkingService;
        this.sourceChunkRepository = sourceChunkRepository;
        this.chunkingProperties = chunkingProperties;
        this.generationPersistence = generationPersistence;
        this.generationIndexing = generationIndexing;
        this.kureProperties = kureProperties;
    }

    public ActivationResult activate() {
        Map<String, SourceIngestionResult> ingestion = new LinkedHashMap<>();
        for (String sourceKey : BREADTH_SOURCE_KEYS) {
            SourceIngestionResult result = ingestionService.ingestOne(sourceKey);
            if (result.outcome() == SourceIngestionResult.Outcome.FAILED) {
                throw new IllegalStateException(
                        "breadth source ingestion failed: " + sourceKey
                                + " (" + result.failureCode() + ")");
            }
            ingestion.put(sourceKey, result);
        }

        Map<String, Integer> chunkCounts = new LinkedHashMap<>();
        for (String sourceKey : BREADTH_SOURCE_KEYS) {
            SourceDocument document = requireReviewedDocument(sourceKey);
            setApplicability(document);
            List<SourceChunk> chunks = chunkingService.chunk(document);
            approveVerifiedChunks(chunks);
            chunkCounts.put(sourceKey, chunks.size());
        }

        List<String> generationSources = new ArrayList<>(CARD_SOURCE_KEYS);
        generationSources.addAll(BREADTH_SOURCE_KEYS);
        List<SourceChunk> approved = sourceChunkRepository.findAllApprovedActive().stream()
                .filter(chunk -> generationSources.contains(
                        chunk.getSourceDocument().getSourceRegistry().getSourceKey()))
                .filter(chunk -> chunkingProperties.configVersion()
                        .equals(chunk.getChunkConfigVersion()))
                .sorted(Comparator.comparing(chunk -> chunk.getId().toString()))
                .toList();
        if (approved.isEmpty()) {
            throw new IllegalStateException("breadth activation produced no approved chunks");
        }

        String corpusSnapshot = SourceHashing.sha256(approved.stream()
                .map(chunk -> String.join("|",
                        chunk.getId().toString(),
                        chunk.getBodySha256(),
                        chunk.getSourceDocument().getId().toString(),
                        chunk.getSourceDocument().getContentSha256()))
                .reduce((left, right) -> left + "\n" + right)
                .orElseThrow());
        String generationKey = "mvp-breadth-" + corpusSnapshot.substring(0, 16);
        String sourceKeysJson = generationSources.stream()
                .map(key -> "\"" + key + "\"")
                .reduce((left, right) -> left + "," + right)
                .map(value -> "{\"corpus\":\"MVP-BREADTH\",\"sourceKeys\":[" + value + "]}")
                .orElseThrow();
        RetrievalGeneration generation = generationPersistence.getOrCreate(
                new RetrievalGenerationData.Definition(
                        generationKey,
                        "kure-v2-plaid-v1",
                        kureProperties.modelIdentifier(),
                        kureProperties.modelRevision(),
                        kureProperties.tokenizerIdentifier(),
                        kureProperties.tokenizerRevision(),
                        "{\"queryLength\":64,\"documentMaxTokens\":8192,\"doQueryExpansion\":true,\"queryPrefix\":\"\",\"documentPrefix\":\"\"}",
                        "{\"backend\":\"PLAID\",\"nbits\":4,\"seed\":42,\"useFast\":true,\"useTriton\":false}",
                        sourceKeysJson,
                        chunkingProperties.configVersion(),
                        corpusSnapshot
                )
        );
        generationIndexing.buildAndActivate(generation.getId());
        return new ActivationResult(
                List.copyOf(ingestion.keySet()),
                Map.copyOf(chunkCounts),
                generation.getId(),
                corpusSnapshot
        );
    }

    private SourceDocument requireReviewedDocument(String sourceKey) {
        SourceDocument document = sourceRegistryRepository
                .findBySourceKeyAndEnabledTrue(sourceKey)
                .flatMap(registry -> sourceDocumentRepository
                        .findBySourceRegistry_IdAndStatus(registry.getId(), SourceDocumentStatus.ACTIVE))
                .orElseThrow(() -> new IllegalStateException(
                        "breadth source has no active document: " + sourceKey));
        String expected = VERIFIED_RAW_SHA256.get(sourceKey);
        if (expected == null || !expected.equals(document.getRawSha256())) {
            throw new IllegalStateException(
                    "breadth source hash is not the reviewed official original: " + sourceKey);
        }
        return document;
    }

    @Transactional
    protected void setApplicability(SourceDocument document) {
        String sourceKey = document.getSourceRegistry().getSourceKey();
        if (FSC_ALERT_SOURCE.equals(sourceKey)) {
            document.setApplicabilityWindow(LocalDate.of(2026, 2, 12), null);
        } else if (FSC_PERSONAL_INFO_SOURCE.equals(sourceKey)) {
            document.setApplicabilityWindow(LocalDate.of(2023, 1, 12), null);
        } else {
            // The current operational pages do not expose a reviewed effective
            // window.  A dated incident therefore remains fail-closed.
            document.setApplicabilityWindow(null, null);
        }
        sourceDocumentRepository.save(document);
    }

    @Transactional
    protected void approveVerifiedChunks(List<SourceChunk> chunks) {
        chunks.forEach(SourceChunk::approve);
        sourceChunkRepository.saveAll(chunks);
    }

    public record ActivationResult(
            List<String> sourceKeys,
            Map<String, Integer> chunkCounts,
            java.util.UUID generationId,
            String corpusSnapshotSha256
    ) {
    }
}
