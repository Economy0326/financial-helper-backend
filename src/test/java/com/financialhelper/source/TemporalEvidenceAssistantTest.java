package com.financialhelper.source;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class TemporalEvidenceAssistantTest {
    private TemporalEvidenceAssistant.Assistance analyze(String text) {
        return TemporalEvidenceAssistant.analyze(text, 0, text.length(), LocalDate.of(2026, 1, 1));
    }

    @Test void publicationIsNeverAnEffectiveDate() {
        var result = analyze("게시일 2025년 7월 22일\n일반 안내입니다.");
        assertThat(result.mentions()).hasSize(1);
        assertThat(result.mentions().getFirst().meaning())
                .isEqualTo(TemporalEvidenceAssistant.Meaning.PUBLICATION);
        assertThat(result.recommendedPolicy()).isEqualTo(TemporalEvidencePolicy.HOLD);
        assertThat(result.mentions().getFirst().locator()).isEqualTo("normalized-content:utf16:0-16");
    }

    @Test void noDateAndUnverifiedCurrentnessStayOnHold() {
        var result = analyze("공식 상담 채널에서 확인합니다.");
        assertThat(result.mentions()).isEmpty();
        assertThat(result.recommendedPolicy()).isEqualTo(TemporalEvidencePolicy.HOLD);
        assertThat(result.currentnessNotice()).contains("확인하지 않았습니다");
    }

    @Test void publicationAndEffectiveDatesRemainDistinct() {
        var result = analyze("2025년 1월 1일에 게시했습니다.\n2025년 9월 1일부터 시행합니다.");
        assertThat(result.mentions()).hasSize(2);
        assertThat(result.recommendedPolicy()).isEqualTo(TemporalEvidencePolicy.EXPLICIT_EFFECTIVE);
        assertThat(result.mentions()).extracting(TemporalEvidenceAssistant.DateMention::meaning)
                .contains(TemporalEvidenceAssistant.Meaning.PUBLICATION,
                        TemporalEvidenceAssistant.Meaning.EFFECTIVE);
    }

    @Test void conditionIsPreservedInExactSourceSentence() {
        String text = "신규 계약인 경우 2025년 1월 13일부터 적용합니다.";
        var result = analyze(text);
        assertThat(result.mentions()).hasSize(1);
        assertThat(result.mentions().getFirst().sentence()).isEqualTo(text);
        assertThat(result.mentions().getFirst().hasCondition()).isTrue();
        assertThat(result.recommendedPolicy()).isEqualTo(TemporalEvidencePolicy.HOLD);
    }

    @Test void exceptionWordsRemainAttachedToSourceSentence() {
        String text = "2025년 1월 13일부터 적용합니다. 다만 기존 계약은 제외합니다.";
        var result = analyze(text);
        assertThat(result.mentions()).hasSize(1);
        assertThat(result.mentions().getFirst().hasException()).isTrue();
        assertThat(result.mentions().getFirst().sentence()).contains("기존 계약은 제외합니다");
    }

    @Test void dateOutsideClaimIsContextOnly() {
        String text = "게시일 2025년 1월 1일\n신고는 공식 채널에서 합니다.";
        int start = text.indexOf("신고는");
        var result = TemporalEvidenceAssistant.analyze(text, start, text.length(), null);
        assertThat(result.mentions()).hasSize(1);
        assertThat(result.mentions().getFirst().inClaim()).isFalse();
        assertThat(result.recommendedPolicy()).isEqualTo(TemporalEvidencePolicy.HOLD);
    }

    @Test void lawVersionIsSuggestedButNotApproved() {
        var result = analyze("이 법은 2026년 1월 2일 시행한다.");
        assertThat(result.recommendedPolicy()).isEqualTo(TemporalEvidencePolicy.VERSION_EFFECTIVE);
        assertThat(result.mentions().getFirst().meaning())
                .isEqualTo(TemporalEvidenceAssistant.Meaning.LAW_EFFECTIVE);
    }

    @Test void lawVersionOutsideClaimRemainsContextUntilIdentityAndCurrentnessAreVerified() {
        String text = "금융소비자 보호에 관한 법률 [시행 2026. 1. 2.] [법률 제21065호, 2025. 10. 1., 타법개정]\n"
                + "제19조(설명의무) 금융상품에 관한 중요한 사항을 설명하여야 한다.";
        int start = text.indexOf("제19조");
        var result = TemporalEvidenceAssistant.analyze(text, start, text.length(), null);
        assertThat(result.mentions()).anySatisfy(mention -> {
            assertThat(mention.date()).isEqualTo(LocalDate.of(2026, 1, 2));
            assertThat(mention.inClaim()).isFalse();
        });
        assertThat(result.recommendedPolicy()).isEqualTo(TemporalEvidencePolicy.HOLD);
    }

    @Test void manyEarlierDocumentDatesCannotHideTheClaimDate() {
        String context = ("게시일 2020년 1월 1일\n").repeat(25);
        String claim = "제도는 2025년 9월 1일 시행합니다.";
        var result = TemporalEvidenceAssistant.analyze(context + claim,
                context.length(), context.length() + claim.length(), null);
        assertThat(result.mentions()).anySatisfy(mention -> {
            assertThat(mention.inClaim()).isTrue();
            assertThat(mention.date()).isEqualTo(LocalDate.of(2025, 9, 1));
        });
        assertThat(result.recommendedPolicy()).isEqualTo(TemporalEvidencePolicy.EXPLICIT_EFFECTIVE);
    }

    @Test void ambiguousStatementAndInvalidDateStayOnHold() {
        var result = analyze("2026년 2월 30일 게시 및 시행.\n2026년 3월 1일 게시 및 시행.");
        assertThat(result.mentions()).hasSize(1);
        assertThat(result.mentions().getFirst().meaning())
                .isEqualTo(TemporalEvidenceAssistant.Meaning.UNCLEAR);
        assertThat(result.recommendedPolicy()).isEqualTo(TemporalEvidencePolicy.HOLD);
    }

    @Test void invalidDateAlongsideValidEffectiveDateCannotBeIgnored() {
        var result = analyze("제도는 2026년 2월 30일 시행합니다.\n2026년 3월 1일 시행합니다.");
        assertThat(result.unresolvedDates()).hasSize(1);
        assertThat(result.unresolvedDates().getFirst().sourceText()).contains("2월 30일");
        assertThat(result.recommendedPolicy()).isEqualTo(TemporalEvidencePolicy.HOLD);
    }

    @Test void abbreviatedYearUsesNearbyPublicationCenturyButNotPublicationAsEffectiveDate() {
        var result = analyze("실손 청구전산화 시행(‘24.10.25일)");
        assertThat(result.mentions()).hasSize(1);
        assertThat(result.mentions().getFirst().date()).isEqualTo(LocalDate.of(2024, 10, 25));
        assertThat(result.mentions().getFirst().sourceText()).isEqualTo("‘24.10.25일");
        assertThat(result.recommendedPolicy()).isEqualTo(TemporalEvidencePolicy.EXPLICIT_EFFECTIVE);
        String withoutPublicationDate = "시행(‘24.10.25일)";
        assertThat(TemporalEvidenceAssistant.analyze(withoutPublicationDate,
                0, withoutPublicationDate.length(), null)
                .unresolvedDates()).hasSize(1);
    }

    @Test void repeatedSameContractDateIsNotConflicting() {
        var result = analyze("’25년 1월 13일 이후 계약 체결부터 적용\n기존 계약은 ’25년 1월 13일 이전에는 적용되지 않습니다.");
        assertThat(result.mentions()).hasSize(2);
        assertThat(result.mentions()).extracting(TemporalEvidenceAssistant.DateMention::date)
                .containsOnly(LocalDate.of(2025, 1, 13));
        assertThat(result.reason()).doesNotContain("여러 날짜");
    }

    @Test void lawEffectiveAndAmendmentDatesRemainSeparate() {
        var result = analyze("법 시행 2026. 1. 2.\n타법개정 2025. 10. 1.");
        assertThat(result.mentions()).extracting(TemporalEvidenceAssistant.DateMention::meaning)
                .contains(TemporalEvidenceAssistant.Meaning.LAW_EFFECTIVE,
                        TemporalEvidenceAssistant.Meaning.AMENDMENT);
        assertThat(result.recommendedPolicy()).isEqualTo(TemporalEvidencePolicy.VERSION_EFFECTIVE);
    }

    @Test void competingEffectiveDatesRemainOnHold() {
        var result = analyze("2025년 1월 1일 시행\n2026년 1월 1일 시행");
        assertThat(result.recommendedPolicy()).isEqualTo(TemporalEvidencePolicy.HOLD);
    }

    @Test void apostropheKoreanContractDateIsNormalizedWithoutUsingPublicationDate() {
        String text = "게시일 2025년 1월 9일\n’25년 1월 13일 이후 대출에 관한 계약을 체결(기존 계약을 갱신하는 경우 포함)하는 경우부터 적용";
        int start = text.indexOf("’25년");
        var result = TemporalEvidenceAssistant.analyze(text, start, text.length(), null);
        assertThat(result.mentions()).anySatisfy(mention -> {
            assertThat(mention.sourceText()).isEqualTo("’25년 1월 13일");
            assertThat(mention.date()).isEqualTo(LocalDate.of(2025, 1, 13));
            assertThat(mention.inClaim()).isTrue();
            assertThat(mention.meaning()).isEqualTo(TemporalEvidenceAssistant.Meaning.CONTRACT);
            assertThat(mention.hasCondition()).isTrue();
        });
        assertThat(result.recommendedPolicy()).isEqualTo(TemporalEvidencePolicy.HOLD);
    }
}
