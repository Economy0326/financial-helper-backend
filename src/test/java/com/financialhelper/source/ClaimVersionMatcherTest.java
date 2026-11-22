package com.financialhelper.source;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class ClaimVersionMatcherTest {
    private DocumentClaimReviewerService.Claim claim(String body, String context, String relation, int offset) {
        return claim(body, context, relation, offset, "CONDITION");
    }
    private DocumentClaimReviewerService.Claim claim(String body, String context, String relation, int offset, String role) {
        return new DocumentClaimReviewerService.Claim("id", UUID.randomUUID(), 1, "raw", "content", "v3", null, "NOT_VERIFIED",
                UUID.randomUUID(), UUID.randomUUID(), body, offset, offset + body.length(), context, offset,
                List.of(new DocumentClaimReviewerService.Relation(role, offset, offset + relation.length(), relation, false)), false, "", "PENDING");
    }
    @Test void offsetMovementDoesNotChangeIdentityButTextAndRelationsDo() {
        var original = claim("신청할 수 있습니다.", "대상과 신청 문맥", "성인인 경우", 0);
        assertThat(ClaimVersionMatcher.same(original, claim("신청할 수 있습니다.", "대상과 신청 문맥", "성인인 경우", 200))).isTrue();
        assertThat(ClaimVersionMatcher.same(original, claim("신청할 수 없습니다.", "대상과 신청 문맥", "성인인 경우", 0))).isFalse();
        assertThat(ClaimVersionMatcher.same(original, claim("신청할 수 있습니다.", "대상과 신청 문맥", "미성년인 경우", 0))).isFalse();
        assertThat(ClaimVersionMatcher.same(original, claim("신청할 수 있습니다.", "대상과 신청 문맥", "성인인 경우", 0, "EXCEPTION"))).isFalse();
        assertThat(ClaimVersionMatcher.same(original, claim("신청할 수 있습니다.", "다른 대상 문맥", "성인인 경우", 0))).isFalse();
    }
    @Test void changedSectionOrQuestionPreventsInheritance() {
        String original = "■ 첫 안내\n신청할 수 있습니다.";
        String moved = "다른 설명\n■ 첫 안내\n신청할 수 있습니다.";
        String changed = "■ 다른 안내\n신청할 수 있습니다.";
        assertThat(ClaimVersionMatcher.sameSection(original, original.indexOf("신청"), moved, moved.indexOf("신청"))).isTrue();
        assertThat(ClaimVersionMatcher.sameSection(original, original.indexOf("신청"), changed, changed.indexOf("신청"))).isFalse();
        assertThat(ClaimVersionMatcher.hasUnambiguousSection("상품 A·상품 B", "신청할 수 있습니다.", 0)).isFalse();
        assertThat(ClaimVersionMatcher.hasUnambiguousSection("상품 A·상품 B", original, original.indexOf("신청"))).isTrue();
    }
}
