package com.financialhelper.source;

import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Fictional normalized text only; these candidates are not official or approved. */
class GenericClaimCandidateExtractorTest {
    private final GenericClaimCandidateExtractor extractor = new GenericClaimCandidateExtractor();

    @Test void syntheticInsuranceConditionsAndOppositeExceptionsStayWithTheirOwnClaim() {
        String car = "자동차보험은 사고일로부터 30일 이내 청구할 수 있습니다. 다만 렌터카는 제외합니다.";
        String injury = "상해보험은 사고일로부터 60일 이내 청구할 수 있습니다. 다만 렌터카는 보상 대상입니다.";
        var carCandidate = extractor.extractNewDocument(document(car)).getFirst();
        var injuryCandidate = extractor.extractNewDocument(document(injury)).getFirst();
        assertThat(carCandidate.related()).anyMatch(r -> r.relation() == GenericClaimCandidateExtractor.Relation.CONDITION && r.span().text().contains("30일"));
        assertThat(carCandidate.related()).anyMatch(r -> r.relation() == GenericClaimCandidateExtractor.Relation.EXCEPTION && r.span().text().contains("제외합니다"));
        assertThat(injuryCandidate.related()).anyMatch(r -> r.relation() == GenericClaimCandidateExtractor.Relation.CONDITION && r.span().text().contains("60일"));
        assertThat(injuryCandidate.related()).anyMatch(r -> r.relation() == GenericClaimCandidateExtractor.Relation.EXCEPTION && r.span().text().contains("보상 대상"));
        assertThat(carCandidate.context().text()).doesNotContain("60일", "보상 대상");
        assertThat(injuryCandidate.context().text()).doesNotContain("30일", "제외합니다");
    }

    @Test void explicitAndAdjacentTableDependenciesAreHeldWithoutInventingRelations() {
        String text = "대출 안내\n대출은 아래 표에 명시된 조건에 따라 적용됩니다.\n| 대상 | 조건 |\n| 신규 | 기존 계약 제외 |";
        var candidate = extractor.extractNewDocument(document(text)).getFirst();
        assertThat(candidate.status()).isEqualTo(GenericClaimCandidateExtractor.ReviewStatus.HOLD);
        assertThat(candidate.warnings()).anyMatch(warning -> warning.contains("표"));
        assertThat(candidate.related()).noneMatch(r -> r.relation() == GenericClaimCandidateExtractor.Relation.EXCEPTION);
        assertSpan(text, candidate.excerpt());
    }

    @Test void noMeaningfulFinancialStatementReturnsZero() {
        assertThat(extractor.extractNewDocument(document("안내문\n오늘은 맑습니다.\n목차"))).isEmpty();
    }

    @Test void plannedMeasureRequiresReviewOfImplementationAndTime() {
        var candidate = extractor.extractNewDocument(document("금융 안내\n금융 이용 절차 개선을 추진할 예정입니다.")).getFirst();
        assertThat(candidate.status()).isEqualTo(GenericClaimCandidateExtractor.ReviewStatus.REVIEW_REQUIRED);
        assertThat(candidate.warnings()).anyMatch(w -> w.contains("시행 여부"));
    }

    @Test void unrelatedTableInSameSectionIsNoticeAndContextNotAutomaticHold() {
        String text = "# 일반 금융 안내\n대출 신청은 공식 채널에서 확인해야 합니다.\n이 문단은 별도의 안내입니다.\n| 구조화 표 0 | 셀: 별도 안내 |";
        var candidate = extractor.extractNewDocument(document(text)).stream()
                .filter(c -> c.excerpt().text().startsWith("대출 신청")) .findFirst().orElseThrow();
        assertThat(candidate.status()).isEqualTo(GenericClaimCandidateExtractor.ReviewStatus.REVIEW_REQUIRED);
        assertThat(candidate.warnings()).anyMatch(w -> w.contains("같은 절"));
        assertThat(candidate.context().text()).contains("| 구조화 표 0");
    }

    @Test void publicationDateIsMetadataNotAnInferredEffectiveDate() {
        SourceDocument doc = document("금융 안내\n대출 신청은 공식 채널에서 확인할 수 있습니다.");
        when(doc.getPublishedAt()).thenReturn(java.time.LocalDate.of(2022, 4, 11));
        var candidate = extractor.extractNewDocument(doc).getFirst();
        assertThat(candidate.source().publishedAt()).isEqualTo(java.time.LocalDate.of(2022, 4, 11));
        assertThat(candidate.related()).noneMatch(r -> r.relation() == GenericClaimCandidateExtractor.Relation.DATE);
        assertThat(candidate.excerpt().text()).doesNotContain("2022");
    }

    @Test void oneExplanationPreservesExactVersionHashAndUtf16Offset() {
        String text = "[PAGE 2]\n📌 금융 안내\n예금은 일정한 금액을 맡기는 금융상품을 의미합니다.";
        SourceDocument doc = document(text);
        var result = extractor.extractNewDocument(doc);
        assertThat(result).hasSize(1);
        var candidate = result.getFirst();
        assertThat(candidate.source().documentId()).isEqualTo(doc.getId());
        assertThat(candidate.source().documentVersion()).isEqualTo(3);
        assertThat(candidate.source().rawSha256()).isEqualTo(doc.getRawSha256());
        assertSpan(text, candidate.excerpt());
        assertThat(candidate.page()).isEqualTo(2);
        assertThat(candidate.selectionReason()).contains("설명");
        assertThat(candidate.status()).isEqualTo(GenericClaimCandidateExtractor.ReviewStatus.REVIEW_REQUIRED);
    }

    @Test void definitionScopeAndProcedureAreSeparateCandidates() {
        String text = "보험 안내\n보험은 위험에 대비하는 제도를 의미합니다.\n"
                + "보험 청구 대상은 계약에 명시된 사람입니다.\n보험 청구는 공식 채널에서 신청할 수 있습니다.";
        var result = extractor.extractNewDocument(document(text));
        assertThat(result).hasSize(3);
        assertThat(result).allSatisfy(candidate -> assertSpan(text, candidate.excerpt()));
        assertThat(result.get(1).related()).anyMatch(item -> item.relation() == GenericClaimCandidateExtractor.Relation.CONDITION);
    }

    @Test void adjacentExceptionAndDateRemainLinkedToSupport() {
        String text = "대출 안내\n2025년 1월 1일 이후 신규 대출 계약에 적용됩니다. 다만 기존 계약을 동일 조건으로 갱신하는 경우는 제외합니다.";
        var result = extractor.extractNewDocument(document(text));
        assertThat(result).hasSize(1);
        var candidate = result.getFirst();
        assertThat(candidate.related()).anyMatch(item -> item.relation() == GenericClaimCandidateExtractor.Relation.DATE);
        assertThat(candidate.related()).anyMatch(item -> item.relation() == GenericClaimCandidateExtractor.Relation.EXCEPTION
                && item.span().text().contains("제외합니다"));
        assertThat(candidate.context().text()).contains("다만 기존 계약");
        candidate.related().forEach(item -> assertSpan(text, item.span()));
    }

    @Test void crossParagraphExceptionAndPronounAreRetainedButHeld() {
        String text = "예금 안내\n예금 보호 대상은 별도 조건을 따릅니다.\n\n다만 이 경우 일부 계좌는 제외합니다.\n이 제도는 신청할 수 있습니다.";
        var result = extractor.extractNewDocument(document(text));
        assertThat(result).hasSize(2);
        assertThat(result.getFirst().status()).isEqualTo(GenericClaimCandidateExtractor.ReviewStatus.HOLD);
        assertThat(result.getFirst().related()).anyMatch(item -> item.relation() == GenericClaimCandidateExtractor.Relation.EXCEPTION && item.ambiguous());
        assertThat(result.get(1).status()).isEqualTo(GenericClaimCandidateExtractor.ReviewStatus.HOLD);
        assertThat(result.get(1).context().text()).contains("다만 이 경우");
    }

    @Test void exceptionAcrossPageBoundaryIsRetainedAndHeld() {
        String text = "[PAGE 1]\n대출은 신규 계약에 적용됩니다.\n[PAGE 2]\n다만 기존 계약은 제외합니다.";
        var candidate = extractor.extractNewDocument(document(text)).getFirst();
        assertThat(candidate.status()).isEqualTo(GenericClaimCandidateExtractor.ReviewStatus.HOLD);
        assertThat(candidate.related()).anyMatch(item -> item.relation() == GenericClaimCandidateExtractor.Relation.EXCEPTION
                && item.ambiguous() && item.span().text().contains("기존 계약"));
        assertSpan(text, candidate.context());
    }

    @Test void exactDuplicatesAreConsolidatedWithoutLosingOtherOffset() {
        String text = "카드 안내\n카드 사용자는 공식 채널에서 신청할 수 있습니다.\n카드 사용자는 공식 채널에서 신청할 수 있습니다.";
        var result = extractor.extractNewDocument(document(text));
        assertThat(result).hasSize(1);
        assertThat(result.getFirst().duplicateExcerpts()).hasSize(1);
        assertSpan(text, result.getFirst().duplicateExcerpts().getFirst());
        assertThat(result.getFirst().duplicateExcerpts().getFirst().start()).isGreaterThan(result.getFirst().excerpt().start());
    }

    @Test void distinctExceptionsAreNotDeduplicated() {
        String text = "대출 안내\n대출은 신청할 수 있습니다. 다만 연체는 제외합니다.\n대출은 신청할 수 있습니다. 다만 미성년자는 제외합니다.";
        var result = extractor.extractNewDocument(document(text));
        assertThat(result).hasSize(2);
        assertThat(result.get(0).related().getFirst().span().text()).contains("연체");
        assertThat(result.get(1).related().getFirst().span().text()).contains("미성년자");
    }

    @Test void tableFootnoteAndIncompleteExtractionAreHeld() {
        String text = "보험 안내\n보험 신청 조건은 다음과 같습니다:\n| 대상 | 신청 |\n※ 보험 청구는 별도 확인이 필요합니다.";
        var result = extractor.extractNewDocument(document(text));
        assertThat(result).isNotEmpty();
        assertThat(result.getFirst().status()).isEqualTo(GenericClaimCandidateExtractor.ReviewStatus.HOLD);
        assertThat(result.getFirst().warnings()).isNotEmpty();
        assertThat(result).allSatisfy(candidate -> assertSpan(text, candidate.context()));
    }

    @Test void otherFinancialTopicsDoNotRequireTopicSpecificBoundaries() {
        assertThat(extractor.extractNewDocument(document("투자 안내\n투자 위험은 상품 설명에서 확인해야 합니다."))).hasSize(1);
        assertThat(extractor.extractNewDocument(document("카드 안내\n카드 분실은 공식 채널에 신고해야 합니다."))).hasSize(1);
    }

    @Test void existingSevenDocumentRoutesAndDamagedContentAreRejected() {
        for (String key : OfficialInformationClaimSelector.sourceKeys()) {
            SourceDocument doc = document("금융 안내\n금융 상품의 조건을 확인해야 합니다.");
            when(doc.getSourceRegistry().getSourceKey()).thenReturn(key);
            assertThatThrownBy(() -> extractor.extractNewDocument(doc)).isInstanceOf(IllegalArgumentException.class);
        }
        SourceDocument deposit = document("예금 안내\n예금 보호 조건을 확인해야 합니다.");
        when(deposit.getCanonicalUrl()).thenReturn(VerifiedPdfMetadata.DEPOSIT_PDF_URL);
        assertThatThrownBy(() -> extractor.extractNewDocument(deposit)).isInstanceOf(IllegalArgumentException.class);
        SourceDocument changed = document("금융 안내\n금융 상품의 조건을 확인해야 합니다.");
        when(changed.getContentSha256()).thenReturn("0".repeat(64));
        assertThatThrownBy(() -> extractor.extractNewDocument(changed)).isInstanceOf(IllegalArgumentException.class);
    }

    private static SourceDocument document(String text) {
        SourceDocument doc = mock(SourceDocument.class);
        SourceRegistry registry = mock(SourceRegistry.class);
        byte[] raw = ("fictional bytes:" + text).getBytes(StandardCharsets.UTF_8);
        when(registry.getSourceKey()).thenReturn("new-fictional-source");
        when(doc.getSourceRegistry()).thenReturn(registry);
        when(doc.getId()).thenReturn(UUID.randomUUID());
        when(doc.getDocumentVersion()).thenReturn(3);
        when(doc.getStatus()).thenReturn(SourceDocumentStatus.ACTIVE);
        when(doc.getCanonicalUrl()).thenReturn("https://synthetic.invalid/doc");
        when(doc.getTitle()).thenReturn("합성 문서");
        when(doc.getOriginalContent()).thenReturn(raw);
        when(doc.getRawSha256()).thenReturn(SourceHashing.sha256(raw));
        when(doc.getNormalizedContent()).thenReturn(text);
        when(doc.getContentSha256()).thenReturn(SourceHashing.sha256(text));
        return doc;
    }

    private static void assertSpan(String text, GenericClaimCandidateExtractor.Span span) {
        assertThat(span.text()).isEqualTo(text.substring(span.start(), span.end()));
        assertThat(span.locator()).isEqualTo("normalized-content:utf16:" + span.start() + "-" + span.end());
    }
}
