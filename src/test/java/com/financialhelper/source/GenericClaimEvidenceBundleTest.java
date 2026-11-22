package com.financialhelper.source;

import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class GenericClaimEvidenceBundleTest {
    private final GenericClaimEvidenceBundle service = new GenericClaimEvidenceBundle();

    @Test void optionalReadOnlyOfficialComparison() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(System.getenv("FH_V3_DOC_1") != null);
        var mapper = tools.jackson.databind.json.JsonMapper.builder().build();
        for (int index = 1; index <= 5; index++) {
            var row = mapper.readTree(java.util.Objects.requireNonNull(System.getenv("FH_V3_DOC_" + index)));
            var doc = document(new String(java.util.Base64.getDecoder().decode(row.get("text").asText()), StandardCharsets.UTF_8));
            var result = service.prepare(doc, "generic-claim-v3");
            int exact = 0, relationsExact = 0;
            for (var approved : row.get("approved")) {
                String oldBody = new String(java.util.Base64.getDecoder().decode(approved.get("body").asText()), StandardCharsets.UTF_8);
                var match = result.stream().filter(c -> c.body().equals(oldBody)).findFirst();
                if (match.isPresent()) {
                    exact++;
                    var expected = new java.util.HashSet<String>();
                    for (var relation : approved.get("relations")) {
                        int start = relation.get("start").asInt(), end = relation.get("end").asInt();
                        expected.add(relation.get("role").asText() + ":" + doc.getNormalizedContent().substring(start, end));
                    }
                    var actual = new java.util.HashSet<String>();
                    match.get().related().stream().filter(r -> r.relation() == GenericClaimCandidateExtractor.Relation.CONDITION || r.relation() == GenericClaimCandidateExtractor.Relation.EXCEPTION)
                            .forEach(r -> actual.add(r.relation().name() + ":" + r.span().text()));
                    if (expected.equals(actual)) relationsExact++;
                    else {
                        System.out.println("V3_RELATION_CHANGED " + row.get("label").asText() + " " + SourceHashing.sha256(oldBody).substring(0, 12));
                        if ("true".equals(System.getenv("FH_V3_DETAIL")))
                            System.out.println("V3_DETAIL " + oldBody + "\nOLD " + expected + "\nNEW " + actual);
                    }
                } else {
                    var sentences = java.util.Arrays.stream(oldBody.split("(?<=[.!?。！？])\\s+"))
                            .map(String::trim).filter(s -> !s.isEmpty()).toList();
                    long covered = sentences.stream().filter(sentence -> result.stream().anyMatch(c -> c.body().contains(sentence))).count();
                    System.out.println("V3_UNMATCHED " + row.get("label").asText() + " " + SourceHashing.sha256(oldBody).substring(0, 12)
                            + " sentences_present=" + covered + "/" + sentences.size());
                    if ("true".equals(System.getenv("FH_V3_DETAIL"))) {
                        System.out.println("V3_DETAIL " + oldBody);
                        for (var relation : approved.get("relations")) {
                            int start = relation.get("start").asInt(), end = relation.get("end").asInt();
                            System.out.println("OLD " + relation.get("role").asText() + " " + doc.getNormalizedContent().substring(start, end));
                        }
                        for (String sentence : sentences) result.stream().filter(c -> c.body().contains(sentence)).findFirst()
                                .ifPresent(c -> System.out.println("NEW " + c.body() + " RELATIONS " + c.related() + " HOLD " + c.hold()));
                    }
                }
            }
            long markers = result.stream().filter(c -> c.body().contains("미디어 제외") || c.body().contains("연락처 제외") || c.body().contains("인용 제외")).count();
            long questions = result.stream().filter(c -> c.body().endsWith("?") || c.body().endsWith("？")).count();
            System.out.println("V3_COMPARE " + row.get("label").asText() + " candidates=" + result.size()
                    + " hold=" + result.stream().filter(GenericClaimEvidenceBundle.Bundle::hold).count()
                    + " markers=" + markers + " questions=" + questions + " approved_exact=" + exact
                    + "/" + row.get("approved").size() + " relations_exact=" + relationsExact + "/" + row.get("approved").size());
            if (doc.getNormalizedContent().contains("저금리 정책금융상품, 중도금 집단대출"))
                System.out.println("V3_EXCLUSION " + result.stream().anyMatch(c -> c.body().contains("저금리 정책금융상품, 중도금 집단대출")
                        && c.related().stream().anyMatch(r -> r.relation() == GenericClaimCandidateExtractor.Relation.EXCEPTION)));
        }
    }

    @Test void versionThreeSeparatesMarkersQuestionsAndActualExclusions() {
        String text = "# 금융 안내\n◆ 대출 Q&A\nㅇ 어디에서 신청하나요? ☞ 대출은 영업점에서 신청할 수 있습니다.\n| 미디어 제외 |\n안심차단 현황을 확인할 수 있습니다.\n저금리 상품과 집단대출은 갈아타기가 불가하다.";
        var doc = document(text);
        var old = service.prepare(doc, "generic-claim-v1");
        var v3 = service.prepare(doc, "generic-claim-v3");
        assertThat(v3).noneMatch(c -> c.body().contains("미디어 제외") || c.body().endsWith("?") || c.body().contains("Q&A"));
        assertThat(v3).anySatisfy(c -> { assertThat(c.body()).contains("영업점"); assertThat(c.context().text()).contains("어디에서 신청"); });
        assertThat(v3).anySatisfy(c -> { assertThat(c.body()).contains("안심차단 현황"); assertThat(c.related()).noneMatch(r -> r.relation() == GenericClaimCandidateExtractor.Relation.EXCEPTION); });
        assertThat(v3).anySatisfy(c -> { assertThat(c.body()).contains("집단대출"); assertThat(c.related()).anyMatch(r -> r.relation() == GenericClaimCandidateExtractor.Relation.EXCEPTION); });
        assertThat(service.prepare(doc, "generic-claim-v1")).isEqualTo(old);
    }

    @Test void versionThreeKeepsFootnotesAndTableDependenciesOnHold() {
        String text = "# 합성 보험 안내\n보험 신청은 아래 표에 따라 적용됩니다.\n| 구조화 표 0 | 머리글: 대상 | 셀: 조건 | 행병합 2 |\n보험은 가입 후 이용할 수 있습니다.\n※ 일부 조건에서는 보장이 제외됩니다.";
        var candidates = service.prepare(document(text), "generic-claim-v3");
        assertThat(candidates).anySatisfy(c -> { assertThat(c.body()).contains("아래 표"); assertThat(c.hold()).isTrue(); });
        assertThat(candidates).anySatisfy(c -> { assertThat(c.body()).contains("가입 후"); assertThat(c.hold()).isTrue(); assertThat(c.related()).anyMatch(r -> r.relation() == GenericClaimCandidateExtractor.Relation.NOTE); });
        assertThat(candidates).noneMatch(c -> c.body().startsWith("| 구조화 표"));
    }

    @Test void versionThreeRetainsBothConditionAndExceptionWithoutIdenticalSpans() {
        var candidates = service.prepare(document("금융 안내\n대출 대상인 경우 일부 상품은 제외합니다."), "generic-claim-v3");
        assertThat(candidates).singleElement().satisfies(candidate -> {
            assertThat(candidate.hold()).isTrue();
            var condition = candidate.related().stream().filter(r -> r.relation() == GenericClaimCandidateExtractor.Relation.CONDITION).findFirst().orElseThrow();
            var exception = candidate.related().stream().filter(r -> r.relation() == GenericClaimCandidateExtractor.Relation.EXCEPTION).findFirst().orElseThrow();
            assertThat(condition.span().text()).contains("대상");
            assertThat(exception.span().text()).contains("제외");
            assertThat(condition.span()).isNotEqualTo(exception.span());
        });
    }

    @Test void versionThreeDoesNotTreatFinancialSubjectsAsConditions() {
        var candidates = service.prepare(document("금융 안내\n예금 보호 한도는 1억 원입니다.\n대출 만기는 5년입니다."), "generic-claim-v3");
        assertThat(candidates).hasSize(2).allSatisfy(candidate ->
                assertThat(candidate.related()).noneMatch(r -> r.relation() == GenericClaimCandidateExtractor.Relation.CONDITION));
        var bounded = service.prepare(document("금융 안내\n대출금이 3억 원 이하인 경우 신청할 수 있습니다."), "generic-claim-v3");
        assertThat(bounded).singleElement().satisfies(candidate ->
                assertThat(candidate.related()).anyMatch(r -> r.relation() == GenericClaimCandidateExtractor.Relation.CONDITION));
        var unrestricted = service.prepare(document("금융 안내\n계약 기간에는 제한을 두지 않고 운영합니다."), "generic-claim-v3");
        assertThat(unrestricted).singleElement().satisfies(candidate ->
                assertThat(candidate.related()).anyMatch(r -> r.relation() == GenericClaimCandidateExtractor.Relation.CONDITION));
    }

    @Test void versionThreeDoesNotDropAQualifierWhenThePreviousSentenceWasNotAClaim() {
        var candidates = service.prepare(document("금융 안내\n신청 조건은 다음과 같습니다. 다만 기존 계약은 제외합니다."), "generic-claim-v3");
        assertThat(candidates).anySatisfy(candidate -> {
            assertThat(candidate.body()).contains("기존 계약은 제외");
            assertThat(candidate.hold()).isTrue();
        });
    }

    @Test void versionThreeDoesNotTurnANegatedTargetIntoAnAffirmativeCondition() {
        var candidates = service.prepare(document("금융 안내\n신규 계약은 지원 대상이 아니며 기존 계약은 제외합니다."), "generic-claim-v3");
        assertThat(candidates).singleElement().satisfies(candidate -> {
            assertThat(candidate.hold()).isTrue();
            assertThat(candidate.related()).noneMatch(r -> r.relation() == GenericClaimCandidateExtractor.Relation.CONDITION);
            assertThat(candidate.related()).anySatisfy(r -> {
                assertThat(r.relation()).isEqualTo(GenericClaimCandidateExtractor.Relation.EXCEPTION);
                assertThat(r.span().text()).contains("대상이 아니며", "제외");
            });
        });
    }

    @Test void versionThreePreservesAnaphoricResultsAsContextNotConditions() {
        var candidates = service.prepare(document("금융 안내\n금융 서비스는 영업점에서 신청할 수 있습니다.\n이 경우 신청 즉시 등록됩니다."), "generic-claim-v3");
        assertThat(candidates).anySatisfy(candidate -> {
            assertThat(candidate.body()).contains("신청 즉시 등록됩니다");
            assertThat(candidate.related()).anyMatch(r -> r.relation() == GenericClaimCandidateExtractor.Relation.NOTE && r.span().text().contains("신청 즉시"));
            assertThat(candidate.related()).noneMatch(r -> r.relation() == GenericClaimCandidateExtractor.Relation.CONDITION && r.span().text().contains("신청 즉시"));
        });
    }

    @Test void additionalVersionFindsLimitsWithoutChangingExistingVersion() {
        var doc = document("금융 안내\n합성 대출 만기는 7일 이내입니다.\n합성 대출의 상환은 자동으로 진행됩니다.");
        var before = service.prepare(doc, "generic-claim-v1");
        var expanded = service.prepare(doc, "generic-claim-v2");
        assertThat(before).isEqualTo(service.prepare(doc, "generic-claim-v1"));
        assertThat(expanded).anySatisfy(candidate -> {
            assertThat(candidate.body()).contains("만기");
            assertThat(candidate.related()).anyMatch(r -> r.relation() == GenericClaimCandidateExtractor.Relation.CONDITION);
        });
        assertThat(expanded).anyMatch(candidate -> candidate.body().contains("상환"));
        var bullet = service.prepare(document("금융 안내\n운전자금대출 10억 원 이하 및 정책상품 제외"), "generic-claim-v2");
        assertThat(bullet).singleElement().satisfies(candidate -> {
            assertThat(candidate.body()).contains("10억", "제외");
            assertThat(candidate.hold()).isTrue();
        });
        var adjacent = service.prepare(document("금융 안내\n합성 대출은 10억 원 이하입니다. 정책 상품은 제외합니다."), "generic-claim-v2");
        assertThat(adjacent).anySatisfy(candidate -> {
            assertThat(candidate.body()).contains("10억", "제외");
            assertThat(candidate.hold()).isTrue();
            assertThat(candidate.related()).anyMatch(r -> r.relation() == GenericClaimCandidateExtractor.Relation.EXCEPTION && r.ambiguous());
        });
        var interruption = service.prepare(document("금융 안내\n금융 서비스는 중단 됩니다. 금융 거래도 중단 됩니다."), "generic-claim-v2");
        assertThat(interruption).hasSize(2).allSatisfy(candidate -> {
            assertThat(candidate.related()).noneMatch(r -> r.relation() == GenericClaimCandidateExtractor.Relation.EXCEPTION);
            assertThat(candidate.hold()).isFalse();
        });
    }

    @Test void tableDependentStatementCannotBecomeAnApprovableBundle() {
        var document = document("대출 안내\n대출은 아래 표에 명시된 조건에 따라 적용됩니다.\n| 대상 | 조건 |\n| 신규 | 기존 계약 제외 |");
        var bundle = service.prepare(document, "synthetic-v1").getFirst();
        assertThat(bundle.hold()).isTrue();
        assertThat(bundle.warnings()).anyMatch(w -> w.contains("표"));
    }

    @Test void includesAdjacentExceptionInEvidenceBodyAndHasStableVersionedIdentity() {
        String text = "대출 안내\n신규 대출은 2025년 이후 적용됩니다. 다만 기존 계약은 제외합니다.";
        var doc = document(text);
        var candidate = service.prepare(doc, "synthetic-v1").getFirst();
        assertThat(candidate.body()).isEqualTo(text.substring(candidate.start(), candidate.end()))
                .contains("다만 기존 계약은 제외합니다.");
        assertThat(candidate.related()).anyMatch(r -> r.relation() == GenericClaimCandidateExtractor.Relation.EXCEPTION);
        assertThat(candidate.candidateId()).isEqualTo(service.prepare(doc, "synthetic-v1").getFirst().candidateId());
        assertThat(candidate.candidateId()).isNotEqualTo(service.prepare(doc, "synthetic-v2").getFirst().candidateId());
    }

    @Test void zeroMultipleAndAmbiguousCandidatesRemainReviewOnly() {
        assertThat(service.prepare(document("안녕하세요."), "v1")).isEmpty();
        var multiple = service.prepare(document("보험 안내\n보험은 위험에 대비하는 제도를 의미합니다.\n보험 청구는 공식 채널에서 신청할 수 있습니다."), "v1");
        assertThat(multiple).hasSize(2);
        var held = service.prepare(document("예금 안내\n예금 보호 대상은 별도 조건을 따릅니다.\n\n다만 일부 계좌는 제외합니다."), "v1");
        assertThat(held.getFirst().hold()).isTrue();
        assertThat(held.getFirst().body()).contains("다만 일부 계좌는 제외합니다.");
    }

    @Test void alteredDocumentHashCannotProduceBundle() {
        var doc = document("금융 안내\n대출 조건을 확인해야 합니다.");
        when(doc.getContentSha256()).thenReturn("0".repeat(64));
        assertThatThrownBy(() -> service.prepare(doc, "v1")).isInstanceOf(IllegalArgumentException.class);
    }

    private static SourceDocument document(String text) {
        var doc = mock(SourceDocument.class);
        var registry = mock(SourceRegistry.class);
        byte[] raw = text.getBytes(StandardCharsets.UTF_8);
        when(registry.getSourceKey()).thenReturn("synthetic-source");
        when(doc.getSourceRegistry()).thenReturn(registry);
        when(doc.getId()).thenReturn(UUID.randomUUID());
        when(doc.getDocumentVersion()).thenReturn(1);
        when(doc.getStatus()).thenReturn(SourceDocumentStatus.ACTIVE);
        when(doc.getCanonicalUrl()).thenReturn("https://synthetic.invalid/document");
        when(doc.getTitle()).thenReturn("합성 문서");
        when(doc.getOriginalContent()).thenReturn(raw);
        when(doc.getRawSha256()).thenReturn(SourceHashing.sha256(raw));
        when(doc.getNormalizedContent()).thenReturn(text);
        when(doc.getContentSha256()).thenReturn(SourceHashing.sha256(text));
        return doc;
    }
}
