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
import com.financialhelper.source.SourceRegistry;
import com.financialhelper.source.SourceRegistryRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 승인된 KB CARD Evidence set의 명시적 활성화 경계다.
 * allowlist source key 밖의 항목은 ingestion, 검토 또는 retrieval generation에 포함하지 않는다.
 */
@Service
public class CardOfficialCorpusActivationService {

    public static final String KB_TERMS_SOURCE = "kb-personal-card-terms-260402";
    public static final String KB_FORM_SOURCE = "kb-unauthorized-compensation-form-260209";
    public static final String C2_GUIDELINE_SOURCE =
            "crefia-card-loss-compensation-guideline-221021";
    public static final String KB_COMPENSATION_PROCESS_SOURCE =
            "kb-card-compensation-process";
    public static final String KB_LOSS_REPORT_SOURCE =
            "kb-card-loss-report-ars";
    public static final String KB_TERMS_AMENDMENT_SOURCE =
            "kb-card-terms-amendment-260402";

    private static final List<String> APPROVED_SOURCE_KEYS = List.of(
            KB_TERMS_SOURCE,
            KB_FORM_SOURCE,
            C2_GUIDELINE_SOURCE,
            KB_COMPENSATION_PROCESS_SOURCE,
            KB_LOSS_REPORT_SOURCE,
            KB_TERMS_AMENDMENT_SOURCE
    );

    /* 사용자가 검증한 공식 file의 hash다. 변경된 download는 승인 corpus로
       조용히 승격하지 않고 fail-closed로 처리한다. */
    private static final Map<String, String> VERIFIED_RAW_SHA256 = Map.of(
            KB_TERMS_SOURCE,
            "fa7261248a6ac0b30ada429f4a37947e6a8b31101db981ad66261c2ca29f3c33",
            KB_FORM_SOURCE,
            "e3a566e95f166bbbc07fd667e47c3dbd60bb14147626ffae5f2093721b3c6c02",
            C2_GUIDELINE_SOURCE,
            "bfffe9832f9d3ba7db80e613a120c332ad4e909d2eb8629917dc0cb695011dd6"
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

    public CardOfficialCorpusActivationService(
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
        for (String sourceKey : APPROVED_SOURCE_KEYS) {
            SourceIngestionResult result = ingestionService.ingestOne(sourceKey);
            if (result.outcome() == SourceIngestionResult.Outcome.FAILED) {
                throw new IllegalStateException(
                        "CARD source ingestion failed: " + sourceKey
                                + " (" + result.failureCode() + ")"
                );
            }
            ingestion.put(sourceKey, result);
        }

        List<SourceDocument> documents = APPROVED_SOURCE_KEYS.stream()
                .map(this::requireApprovedDocument)
                .toList();

        Map<String, Integer> chunkCounts = new LinkedHashMap<>();
        for (SourceDocument document : documents) {
            setApplicability(document);
            List<SourceChunk> chunks = chunkingService.chunk(document);
            approveVerifiedChunks(document, chunks);
            chunkCounts.put(
                    document.getSourceRegistry().getSourceKey(),
                    chunks.size()
            );
        }

        List<SourceChunk> approved = sourceChunkRepository.findAllApprovedActive().stream()
                .filter(chunk -> APPROVED_SOURCE_KEYS.contains(
                        chunk.getSourceDocument().getSourceRegistry().getSourceKey()))
                .filter(chunk -> chunkingProperties.configVersion()
                        .equals(chunk.getChunkConfigVersion()))
                .sorted(Comparator.comparing(chunk -> chunk.getId().toString()))
                .toList();
        if (approved.isEmpty()) {
            throw new IllegalStateException("CARD activation produced no approved chunks");
        }

        // 활성화 정의를 persistence guard와 동일하게 유지한다. document identity와
        // content hash는 immutable corpus snapshot의 일부이므로 chunk 본문을
        // version 사이에서 조용히 재사용할 수 없다.
        String corpusSnapshot = SourceHashing.sha256(approved.stream()
                .map(chunk -> String.join("|",
                        chunk.getId().toString(),
                        chunk.getBodySha256(),
                        chunk.getSourceDocument().getId().toString(),
                        chunk.getSourceDocument().getContentSha256()))
                .reduce((left, right) -> left + "\n" + right)
                .orElseThrow());
        String generationKey = "card-kb-" + corpusSnapshot.substring(0, 16);
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
                        "{\"corpus\":\"CARD-KB\",\"sourceKeys\":[\""
                                + KB_TERMS_SOURCE + "\",\"" + KB_FORM_SOURCE
                                + "\",\"" + C2_GUIDELINE_SOURCE + "\",\""
                                + KB_COMPENSATION_PROCESS_SOURCE + "\",\""
                                + KB_LOSS_REPORT_SOURCE + "\",\""
                                + KB_TERMS_AMENDMENT_SOURCE + "\"]}",
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

    private SourceDocument requireApprovedDocument(String sourceKey) {
        SourceRegistry registry = sourceRegistryRepository
                .findBySourceKeyAndEnabledTrue(sourceKey)
                .orElseThrow(() -> new IllegalStateException(
                        "CARD source registry entry is missing: " + sourceKey));
        SourceDocument document = sourceDocumentRepository
                .findBySourceRegistry_IdAndStatus(registry.getId(), SourceDocumentStatus.ACTIVE)
                .orElseThrow(() -> new IllegalStateException(
                        "CARD source has no active document: " + sourceKey));
        String expected = VERIFIED_RAW_SHA256.get(sourceKey);
        if (expected != null && !expected.equals(document.getRawSha256())) {
            throw new IllegalStateException(
                    "CARD source hash is not the human-verified file: " + sourceKey);
        }
        if (expected == null && document.getDocumentVersion() != 1) {
            throw new IllegalStateException(
                    "CARD HTML source changed after human verification: " + sourceKey);
        }
        return document;
    }

    @Transactional
    protected void setApplicability(SourceDocument document) {
        if (KB_TERMS_SOURCE.equals(document.getSourceRegistry().getSourceKey())) {
            document.setApplicabilityWindow(LocalDate.of(2026, 5, 12), null);
        } else if (C2_GUIDELINE_SOURCE.equals(
                document.getSourceRegistry().getSourceKey())) {
            document.setApplicabilityWindow(LocalDate.of(2022, 11, 28), null);
        } else {
            // 검증된 file에서 이 양식의 시행일을 독립적으로 확인할 수 없다.
            // 따라서 날짜가 있는 사고는 fail-closed로 처리한다.
            document.setApplicabilityWindow(null, null);
        }
        sourceDocumentRepository.save(document);
    }

    @Transactional
    protected void approveVerifiedChunks(
            SourceDocument document,
            List<SourceChunk> chunks
    ) {
        for (SourceChunk chunk : chunks) {
            chunk.approve();
        }
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
