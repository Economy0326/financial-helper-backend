package com.financialhelper.source;

import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

class CurrentGuidanceEvidencePolicyTest {
    @Test void independentOfficialBasisAllowsOnlyConfiguredBoundedWindow() {
        var kind = CurrentGuidanceEvidencePolicy.Kind.OFFICIAL_CURRENT_GUIDANCE;
        var policy = new CurrentGuidanceEvidencePolicy(Map.of(kind, Duration.ofDays(7))); // fixture policy, not production
        UUID doc = UUID.randomUUID(), claim = UUID.randomUUID();
        Instant now = Instant.parse("2026-11-14T00:00:00Z");
        var basis = new CurrentGuidanceEvidencePolicy.Basis(doc, claim, "a".repeat(64),
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "fixture:official-current-page",
                kind, true, true, now.minusSeconds(60), now.plus(Duration.ofDays(3)));
        var result = policy.evaluate(doc, claim, "a".repeat(64), basis, now);
        assertThat(result.result()).isEqualTo(CurrentGuidanceEvidencePolicy.Result.ELIGIBLE);
        assertThat(result.window().until()).isEqualTo(now.plus(Duration.ofDays(3)));
        assertThat(result.window().until()).isNotEqualTo(now.plus(Duration.ofHours(1)));
        assertThat(policy.evaluate(doc, UUID.randomUUID(), "a".repeat(64), basis, now).result())
                .isEqualTo(CurrentGuidanceEvidencePolicy.Result.SCOPE_MISMATCH);
        assertThat(policy.evaluate(doc, claim, "a".repeat(64), basis, now.plus(Duration.ofDays(3))).result())
                .isEqualTo(CurrentGuidanceEvidencePolicy.Result.EXPIRED_BASIS);
        assertThat(new CurrentGuidanceEvidencePolicy(Map.of()).evaluate(doc, claim, "a".repeat(64), basis, now).result())
                .isEqualTo(CurrentGuidanceEvidencePolicy.Result.POLICY_UNCONFIGURED);
        var selfProof = new CurrentGuidanceEvidencePolicy.Basis(doc, claim, "a".repeat(64), UUID.randomUUID(),
                UUID.randomUUID(), claim, "fixture:proof", kind, true, true, now.minusSeconds(1), now.plusSeconds(100));
        assertThat(policy.evaluate(doc, claim, "a".repeat(64), selfProof, now).result())
                .isEqualTo(CurrentGuidanceEvidencePolicy.Result.UNTRUSTED_BASIS);
    }

    @Test void urlMatchAloneAndArchiveOrUnverifiedStatuteNeverEstablishCurrentness() {
        UUID doc = UUID.randomUUID(), claim = UUID.randomUUID();
        Instant now = Instant.parse("2026-11-14T00:00:00Z");
        var policy = new CurrentGuidanceEvidencePolicy(Map.of(CurrentGuidanceEvidencePolicy.Kind.OFFICIAL_CURRENT_GUIDANCE,
                Duration.ofDays(1)));
        assertThat(policy.evaluate(doc, claim, "b".repeat(64), null, now).result())
                .isEqualTo(CurrentGuidanceEvidencePolicy.Result.MISSING_BASIS);
        for (var kind : new CurrentGuidanceEvidencePolicy.Kind[]{CurrentGuidanceEvidencePolicy.Kind.ARCHIVED_NOTICE,
                CurrentGuidanceEvidencePolicy.Kind.STATUTE, CurrentGuidanceEvidencePolicy.Kind.UNKNOWN}) {
            var basis = new CurrentGuidanceEvidencePolicy.Basis(doc, claim, "b".repeat(64), UUID.randomUUID(),
                    UUID.randomUUID(), UUID.randomUUID(), "fixture:proof", kind, true, true,
                    now.minusSeconds(1), now.plusSeconds(3600));
            assertThat(policy.evaluate(doc, claim, "b".repeat(64), basis, now).result())
                    .isEqualTo(CurrentGuidanceEvidencePolicy.Result.UNTRUSTED_BASIS);
        }
        assertThatThrownBy(() -> new CurrentGuidanceEvidencePolicy(Map.of(
                CurrentGuidanceEvidencePolicy.Kind.OFFICIAL_CURRENT_GUIDANCE, Duration.ofDays(366))))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
