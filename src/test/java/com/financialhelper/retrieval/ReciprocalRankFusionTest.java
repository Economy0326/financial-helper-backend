package com.financialhelper.retrieval;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ReciprocalRankFusionTest {
    @Test
    void uses_rank_only_formula_and_deterministic_tie_breaking() {
        UUID first = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID second = UUID.fromString("00000000-0000-0000-0000-000000000002");

        List<ReciprocalRankFusion.FusedHit> result = ReciprocalRankFusion.fuse(
                List.of(new ReciprocalRankFusion.RankedHit(
                        first, 1, 999.0, ReciprocalRankFusion.Branch.SEMANTIC)),
                List.of(new ReciprocalRankFusion.RankedHit(
                        second, 1, 0.01, ReciprocalRankFusion.Branch.KEYWORD)),
                10);

        assertThat(result).extracting(ReciprocalRankFusion.FusedHit::sourceChunkId)
                .containsExactly(first, second);
        assertThat(result.getFirst().rrfScore()).isEqualTo(1.0 / 61.0);
    }

    @Test
    void merges_duplicate_chunk_ranks_across_branches() {
        UUID id = UUID.randomUUID();
        List<ReciprocalRankFusion.FusedHit> result = ReciprocalRankFusion.fuse(
                List.of(new ReciprocalRankFusion.RankedHit(
                        id, 2, 3.0, ReciprocalRankFusion.Branch.SEMANTIC)),
                List.of(new ReciprocalRankFusion.RankedHit(
                        id, 1, 4.0, ReciprocalRankFusion.Branch.KEYWORD)),
                10);

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().rrfScore())
                .isEqualTo(1.0 / 62.0 + 1.0 / 61.0);
        assertThat(result.getFirst().semanticScore()).isEqualTo(3.0);
        assertThat(result.getFirst().keywordScore()).isEqualTo(4.0);
    }
}
