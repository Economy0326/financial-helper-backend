package com.financialhelper.retrieval;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Deterministic rank-only fusion; semantic and keyword scores are never added. */
public final class ReciprocalRankFusion {
    public static final int DEFAULT_K = 60;

    private ReciprocalRankFusion() {
    }

    public static List<FusedHit> fuse(
            List<RankedHit> semantic,
            List<RankedHit> keyword,
            int limit
    ) {
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be positive");
        }
        Map<UUID, FusedAccumulator> merged = new LinkedHashMap<>();
        add(merged, semantic);
        add(merged, keyword);
        return merged.entrySet().stream()
                .map(entry -> new FusedHit(
                        entry.getKey(),
                        entry.getValue().semanticScore,
                        entry.getValue().keywordScore,
                        entry.getValue().rrfScore))
                .sorted(Comparator.comparingDouble(FusedHit::rrfScore).reversed()
                        .thenComparing(hit -> hit.sourceChunkId().toString()))
                .limit(limit)
                .toList();
    }

    private static void add(Map<UUID, FusedAccumulator> merged, List<RankedHit> hits) {
        if (hits == null) {
            return;
        }
        for (int index = 0; index < hits.size(); index++) {
            RankedHit hit = hits.get(index);
            if (hit == null || hit.sourceChunkId() == null) {
                continue;
            }
            int rank = hit.rank() > 0 ? hit.rank() : index + 1;
            FusedAccumulator accumulator = merged.computeIfAbsent(
                    hit.sourceChunkId(), ignored -> new FusedAccumulator());
            accumulator.rrfScore += 1.0 / (DEFAULT_K + rank);
            if (hit.branch() == Branch.SEMANTIC) {
                accumulator.semanticScore = hit.score();
            } else if (hit.branch() == Branch.KEYWORD) {
                accumulator.keywordScore = hit.score();
            }
        }
    }

    public enum Branch { SEMANTIC, KEYWORD }

    public record RankedHit(UUID sourceChunkId, int rank, double score, Branch branch) {
        public RankedHit {
            if (sourceChunkId == null || branch == null) {
                throw new IllegalArgumentException("sourceChunkId and branch are required");
            }
        }
    }

    public record FusedHit(
            UUID sourceChunkId,
            double semanticScore,
            double keywordScore,
            double rrfScore
    ) {
    }

    private static final class FusedAccumulator {
        private double semanticScore;
        private double keywordScore;
        private double rrfScore;
    }
}
