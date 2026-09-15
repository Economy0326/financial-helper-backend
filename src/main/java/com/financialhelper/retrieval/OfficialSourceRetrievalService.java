package com.financialhelper.retrieval;

import com.financialhelper.source.ActiveRetrievalGenerationPersistenceService;
import com.financialhelper.source.RetrievalGeneration;
import com.financialhelper.source.RetrievalGenerationStatus;
import com.financialhelper.source.SourceChunk;
import com.financialhelper.source.SourceChunkRepository;
import com.financialhelper.source.SourceChunkReviewStatus;
import com.financialhelper.source.SourceDocumentStatus;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.annotation.Autowired;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Coordinates the two retrieval branches and owns the search-scoped
 * candidate boundary.  It deliberately returns candidates rather than
 * financial advice or a procedure.
 */
@Service
public class OfficialSourceRetrievalService {

    private final ActiveRetrievalGenerationPersistenceService activeGeneration;
    private final RetrievalCorpusSnapshotService corpusSnapshot;
    private final SourceKeywordSearchService keywordSearch;
    private final KureRuntimeClient kureRuntime;
    private final SourceChunkRepository sourceChunkRepository;
    private final ConfirmedCaseSnapshotService confirmedCaseSnapshotService;
    private final Map<UUID, SearchContext> contexts = new ConcurrentHashMap<>();

    public OfficialSourceRetrievalService(
            ActiveRetrievalGenerationPersistenceService activeGeneration,
            RetrievalCorpusSnapshotService corpusSnapshot,
            SourceKeywordSearchService keywordSearch,
            KureRuntimeClient kureRuntime,
            SourceChunkRepository sourceChunkRepository
    ) {
        this(activeGeneration, corpusSnapshot, keywordSearch, kureRuntime,
                sourceChunkRepository, null);
    }

    @Autowired
    public OfficialSourceRetrievalService(
            ActiveRetrievalGenerationPersistenceService activeGeneration,
            RetrievalCorpusSnapshotService corpusSnapshot,
            SourceKeywordSearchService keywordSearch,
            KureRuntimeClient kureRuntime,
            SourceChunkRepository sourceChunkRepository,
            ConfirmedCaseSnapshotService confirmedCaseSnapshotService
    ) {
        this.activeGeneration = activeGeneration;
        this.corpusSnapshot = corpusSnapshot;
        this.keywordSearch = keywordSearch;
        this.kureRuntime = kureRuntime;
        this.sourceChunkRepository = sourceChunkRepository;
        this.confirmedCaseSnapshotService = confirmedCaseSnapshotService;
    }

    public OfficialSearchResponse retrieve(OfficialSearchRequest request) {
        return search(request);
    }

    /** Captures the current consultation revision before using it as context. */
    public OfficialSearchResponse search(UUID consultationId, OfficialSearchRequest request) {
        if (confirmedCaseSnapshotService == null) {
            throw new IllegalStateException("confirmed case snapshot service is required");
        }
        ConfirmedCaseSnapshotData snapshot = confirmedCaseSnapshotService.capture(consultationId);
        OfficialSearchRequest enriched = new OfficialSearchRequest(
                request.query(), request.category(), request.institution(), request.productType(),
                request.incidentDate(), snapshot.facts(), request.limit());
        return search(enriched);
    }

    public OfficialSearchResponse search(OfficialSearchRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("request must not be null");
        }
        UUID searchId = UUID.randomUUID();
        if (request.institution() == null) {
            OfficialSearchResponse response = new OfficialSearchResponse(
                    searchId, RetrievalStatus.NEEDS_CLARIFICATION, false,
                    List.of("INSTITUTION"), List.of(), List.of());
            contexts.put(searchId, new SearchContext(response, Map.of()));
            return response;
        }

        RetrievalGeneration generation = activeGeneration.current().orElse(null);
        if (generation == null || generation.getStatus() != RetrievalGenerationStatus.READY) {
            OfficialSearchResponse response = new OfficialSearchResponse(
                    searchId, RetrievalStatus.NO_MATCH, false, List.of(),
                    List.of("NO_ACTIVE_READY_GENERATION"), List.of());
            contexts.put(searchId, new SearchContext(response, Map.of()));
            return response;
        }

        if (isOutOfScopeCardRequest(request)) {
            OfficialSearchResponse response = new OfficialSearchResponse(
                    searchId, RetrievalStatus.NO_MATCH, false, List.of(),
                    List.of("CARD_SCOPE_OUT_OF_SCOPE"), List.of());
            contexts.put(searchId, new SearchContext(response, Map.of()));
            return response;
        }

        List<RetrievalCorpusSnapshotService.EligibleChunk> scopedCorpus = corpusSnapshot
                .readyChunks(generation.getId()).stream()
                .filter(chunk -> matchesNonTemporalScope(chunk, request))
                .toList();
        boolean temporalUnknown = request.incidentDate() != null
                && scopedCorpus.stream().anyMatch(chunk ->
                chunk.applicabilityStartDate() == null);
        List<RetrievalCorpusSnapshotService.EligibleChunk> corpus = scopedCorpus.stream()
                .filter(chunk -> isTemporallyApplicable(chunk, request.incidentDate()))
                .toList();
        if (corpus.isEmpty()) {
            List<String> gaps = temporalUnknown
                    ? List.of("TEMPORAL_APPLICABILITY_UNKNOWN")
                    : List.of("NO_APPROVED_READY_CORPUS_FOR_SCOPE");
            OfficialSearchResponse response = new OfficialSearchResponse(
                    searchId, RetrievalStatus.NO_MATCH, false, List.of(),
                    gaps, List.of());
            contexts.put(searchId, new SearchContext(response, Map.of()));
            return response;
        }

        Map<UUID, RetrievalCorpusSnapshotService.EligibleChunk> eligible = new HashMap<>();
        corpus.forEach(chunk -> eligible.put(chunk.sourceChunkId(), chunk));
        List<ReciprocalRankFusion.RankedHit> semantic = List.of();
        List<ReciprocalRankFusion.RankedHit> keyword = List.of();
        boolean semanticFailed = false;
        boolean keywordFailed = false;

        try {
            KureRuntimeClient.KureRuntimeQueryResult result = kureRuntime.query(
                    new KureRuntimeClient.KureRuntimeQueryRequest(
                            generation.getId(), request.query().isBlank()
                                    ? request.institution() : request.query(),
                            Math.min(100, request.limit()),
                            eligible.keySet()));
            if (!generation.getId().equals(result.generationId())) {
                throw new KureRuntimeException("KURE query generation mismatch");
            }
            List<ReciprocalRankFusion.RankedHit> semanticHits = new ArrayList<>();
            for (KureRuntimeClient.KureRuntimeQueryResult.Hit hit : result.results()) {
                if (!generation.getId().equals(hit.generationId())
                        || !eligible.containsKey(hit.sourceChunkId())) {
                    throw new KureRuntimeException("KURE returned an ineligible source chunk");
                }
                semanticHits.add(new ReciprocalRankFusion.RankedHit(
                        hit.sourceChunkId(), hit.rank(), hit.maxSimScore(),
                        ReciprocalRankFusion.Branch.SEMANTIC));
            }
            semantic = List.copyOf(semanticHits);
        } catch (RuntimeException exception) {
            semanticFailed = true;
        }

        try {
            List<SourceChunk> keywordChunks = keywordSearch.searchApproved(
                    request.query().isBlank() ? request.institution() : request.query(),
                    // The keyword repository is intentionally generation
                    // agnostic.  Fetch its bounded maximum before applying
                    // the immutable, generation-scoped eligibility set;
                    // otherwise an older chunk version can consume the
                    // caller's small limit and hide all eligible chunks.
                    100);
            List<ReciprocalRankFusion.RankedHit> keywordHits = new ArrayList<>();
            int rank = 0;
            for (SourceChunk chunk : keywordChunks) {
                if (eligible.containsKey(chunk.getId())) {
                    rank++;
                    keywordHits.add(new ReciprocalRankFusion.RankedHit(
                            chunk.getId(), rank, 0.0,
                            ReciprocalRankFusion.Branch.KEYWORD));
                }
            }
            keyword = List.copyOf(keywordHits);
        } catch (RuntimeException exception) {
            keywordFailed = true;
        }

        List<ReciprocalRankFusion.FusedHit> fused = ReciprocalRankFusion.fuse(
                semantic, keyword, request.limit());
        boolean degraded = semanticFailed || keywordFailed;
        RetrievalStatus status;
        List<String> gaps = new ArrayList<>();
        if (temporalUnknown) {
            gaps.add("TEMPORAL_APPLICABILITY_UNKNOWN");
        }
        if (semanticFailed) {
            gaps.add("SEMANTIC_BRANCH_UNAVAILABLE");
        }
        if (keywordFailed) {
            gaps.add("KEYWORD_BRANCH_UNAVAILABLE");
        }
        if (semanticFailed && keywordFailed) {
            status = RetrievalStatus.TEMPORARILY_UNAVAILABLE;
        } else if (fused.isEmpty()) {
            status = RetrievalStatus.NO_MATCH;
            if (gaps.isEmpty()) {
                gaps.add("NO_MATCHING_APPROVED_EVIDENCE");
            }
        } else {
            status = RetrievalStatus.FOUND;
        }

        Map<UUID, OfficialEvidenceCandidate> candidateById = new HashMap<>();
        List<OfficialEvidenceCandidate> candidates = new ArrayList<>();
        int rank = 1;
        for (ReciprocalRankFusion.FusedHit hit : fused) {
            RetrievalCorpusSnapshotService.EligibleChunk chunk = eligible.get(hit.sourceChunkId());
            UUID candidateId = UUID.randomUUID();
            OfficialEvidenceCandidate candidate = new OfficialEvidenceCandidate(
                    candidateId, chunk.sourceChunkId(), chunk.sourceDocumentId(), generation.getId(),
                    rank++, hit.semanticScore() == 0.0 ? null : hit.semanticScore(),
                    hit.keywordScore() == 0.0 ? null : hit.keywordScore(), hit.rrfScore(),
                    chunk.organizationName(), chunk.title(), chunk.documentVersion(),
                    chunk.parentSection(),
                    chunk.representationMetadataJson(),
                    chunk.articleReference(), chunk.pageReference(), chunk.locator(),
                    chunk.canonicalUrl(), chunk.body());
            candidateById.put(candidateId, candidate);
            candidates.add(candidate);
        }
        OfficialSearchResponse response = new OfficialSearchResponse(
                searchId, status, degraded, List.of(), gaps, candidates);
        contexts.put(searchId, new SearchContext(response, candidateById));
        trimContexts();
        return response;
    }

    /** Returns expanded passages only for candidate IDs issued by this search. */
    @Transactional(readOnly = true)
    public List<SourcePassage> getPassages(UUID searchId, Collection<UUID> candidateIds) {
        if (searchId == null || candidateIds == null || candidateIds.isEmpty()) {
            throw new IllegalArgumentException("searchId and candidateIds are required");
        }
        SearchContext context = contexts.get(searchId);
        if (context == null) {
            throw new UnknownRetrievalCandidateException();
        }
        Set<UUID> requested = new HashSet<>(candidateIds);
        if (requested.size() != candidateIds.size()
                || !context.candidates.keySet().containsAll(requested)) {
            throw new UnknownRetrievalCandidateException();
        }
        List<SourcePassage> passages = new ArrayList<>();
        for (UUID candidateId : candidateIds) {
            OfficialEvidenceCandidate candidate = context.candidates.get(candidateId);
            Set<UUID> referencedIds = referencedChunkIds(candidate);
            List<SourceChunk> chunks = sourceChunkRepository
                    .findAllBySourceDocument_IdOrderBySequenceAsc(candidate.sourceDocumentId()).stream()
                    .filter(chunk -> chunk.getReviewStatus() == SourceChunkReviewStatus.APPROVED)
                    .filter(chunk -> chunk.getSourceDocument().getStatus() == SourceDocumentStatus.ACTIVE)
                    .filter(chunk -> sameExpansionUnit(chunk, candidate)
                            || referencedIds.contains(chunk.getId()))
                    .sorted(Comparator.comparingInt(SourceChunk::getSequence))
                    .toList();
            if (chunks.stream().noneMatch(chunk -> chunk.getId().equals(candidate.sourceChunkId()))) {
                throw new UnknownRetrievalCandidateException();
            }
            String text = chunks.stream().map(SourceChunk::getBody)
                    .reduce((left, right) -> left + "\n" + right).orElse(candidate.body());
            passages.add(new SourcePassage(
                    UUID.randomUUID(), candidateId, candidate.sourceDocumentId(),
                    candidate.documentVersion(), chunks.stream().map(SourceChunk::getId).toList(),
                    text, candidate.articleReference(), candidate.pageReference(),
                    candidate.locator(), candidate.canonicalUrl()));
        }
        return List.copyOf(passages);
    }

    private boolean matchesNonTemporalScope(
            RetrievalCorpusSnapshotService.EligibleChunk chunk,
            OfficialSearchRequest request
    ) {
        String institution = normalize(request.institution());
        String organization = normalize(chunk.organizationName());
        boolean commonSource = contains(organization, "여신금융협회")
                || contains(organization, "금융위원회")
                || contains(organization, "금융감독원");
        if (institution != null && !commonSource
                && !contains(organization, institution)
        ) {
            return false;
        }
        if (request.productType() != null) {
            String product = normalize(request.productType());
            if (containsAny(product, CARD_OUT_OF_SCOPE_TERMS)
                    || !contains(product, "신용카드")) {
                return false;
            }
        }
        if (request.category() != null && !"CARD".equalsIgnoreCase(request.category())) {
            return false;
        }
        return true;
    }

    private boolean isOutOfScopeCardRequest(OfficialSearchRequest request) {
        if (request == null || (request.category() != null
                && !"CARD".equalsIgnoreCase(request.category()))) {
            return false;
        }
        return containsAny(normalize(request.query()), CARD_OUT_OF_SCOPE_TERMS)
                || (request.productType() != null
                && (containsAny(normalize(request.productType()), CARD_OUT_OF_SCOPE_TERMS)
                || !contains(normalize(request.productType()), "신용카드")));
    }

    private static final List<String> CARD_OUT_OF_SCOPE_TERMS = List.of(
            "체크카드", "선불카드", "법인카드", "가족카드", "kb비씨", "비씨카드",
            "카드론", "현금서비스", "해외", "계좌이체", "송금", "보이스피싱"
    );

    private boolean isTemporallyApplicable(
            RetrievalCorpusSnapshotService.EligibleChunk chunk,
            java.time.LocalDate incidentDate
    ) {
        if (incidentDate == null) {
            return true;
        }
        if (chunk.applicabilityStartDate() == null) {
            return false;
        }
        return !incidentDate.isBefore(chunk.applicabilityStartDate())
                && (chunk.applicabilityEndDate() == null
                || !incidentDate.isAfter(chunk.applicabilityEndDate()));
    }

    private boolean sameExpansionUnit(SourceChunk chunk, OfficialEvidenceCandidate candidate) {
        if (chunk.getId().equals(candidate.sourceChunkId())) {
            return true;
        }
        boolean sameArticle = candidate.articleReference() != null
                && candidate.articleReference().equals(chunk.getArticleReference());
        boolean sameParent = candidate.parentSection() != null
                && candidate.parentSection().equals(chunk.getParentSection());
        // Parent section chunks are retained together with explicit
        // condition/exception chunks.  Expansion is bounded to one immutable
        // document version, so similarly worded clauses from another source
        // cannot be pulled into the passage.
        boolean heading = chunk.getArticleReference() == null;
        boolean explicitConditionOrException = chunk.getBody() != null
                && (chunk.getBody().contains("조건")
                || chunk.getBody().contains("예외")
                || chunk.getBody().contains("단서")
                || chunk.getBody().contains("다만"));
        return sameArticle || (sameParent && (heading || explicitConditionOrException));
    }

    /** Reads only explicit UUID links supplied by reviewed chunk metadata. */
    private Set<UUID> referencedChunkIds(OfficialEvidenceCandidate candidate) {
        if (candidate.representationMetadataJson() == null
                || candidate.representationMetadataJson().isBlank()) {
            return Set.of();
        }
        try {
            JsonNode root = JsonMapper.builder().build()
                    .readTree(candidate.representationMetadataJson());
            JsonNode references = root == null ? null : root.get("referencedChunkIds");
            if (references == null || !references.isArray()) {
                return Set.of();
            }
            Set<UUID> result = new HashSet<>();
            for (JsonNode reference : references) {
                if (reference != null && reference.isString()) {
                    try {
                        result.add(UUID.fromString(reference.asString()));
                    } catch (IllegalArgumentException ignored) {
                        // Invalid explicit links are ignored, never inferred.
                    }
                }
            }
            return Set.copyOf(result);
        } catch (RuntimeException ignored) {
            return Set.of();
        }
    }

    private static String normalize(String value) {
        return value == null ? "" : value.toLowerCase().replaceAll("\\s+", "");
    }

    private static boolean contains(String value, String needle) {
        return needle != null && !needle.isBlank() && value.contains(needle);
    }

    private static boolean containsAny(String value, List<String> needles) {
        return needles.stream().anyMatch(needle -> contains(value, needle));
    }

    private void trimContexts() {
        if (contexts.size() <= 1000) {
            return;
        }
        contexts.entrySet().stream()
                .sorted(Comparator.comparing(entry -> entry.getValue().createdAt))
                .limit(contexts.size() - 1000L)
                .map(Map.Entry::getKey)
                .forEach(contexts::remove);
    }

    private record SearchContext(
            OfficialSearchResponse response,
            Map<UUID, OfficialEvidenceCandidate> candidates,
            OffsetDateTime createdAt
    ) {
        private SearchContext(OfficialSearchResponse response,
                              Map<UUID, OfficialEvidenceCandidate> candidates) {
            this(response, Map.copyOf(candidates), OffsetDateTime.now(ZoneOffset.UTC));
        }
    }
}
