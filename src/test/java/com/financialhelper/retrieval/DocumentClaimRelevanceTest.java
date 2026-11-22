package com.financialhelper.retrieval;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class DocumentClaimRelevanceTest {
    @Test void purposesComeFromApprovedTextAndDoNotDuplicateMethod() {
        assertThat(DocumentClaimRelevance.availablePurposes("앱에서 신청하고 필요한 서류를 제출합니다."))
                .contains("신청", "필요 서류").doesNotContain("방법");
        assertThat(DocumentClaimRelevance.availablePurposes("일반적인 안내입니다.")).isEmpty();
    }
    @Test void generalProcedureUsesApprovedDocumentSubjectButNotProductConditions() {
        String document = "가상상품과 다른상품을 이용할 수 있습니다.";
        String title = "가상상품·다른상품 안내";
        assertThat(DocumentClaimRelevance.generalProcedureReference("가상상품은 어떻게 신청하나요?", title, document, "앱에서 신청합니다.")).isTrue();
        assertThat(DocumentClaimRelevance.generalProcedureReference("제삼상품은 어떻게 신청하나요?", title, document, "앱에서 신청합니다.")).isFalse();
        assertThat(DocumentClaimRelevance.generalProcedureReference("가상상품은 어떻게 신청하나요?", title, document, "다른상품만 신청할 수 있습니다. 3개월 이내에 신청합니다.")).isFalse();
        assertThat(DocumentClaimRelevance.generalProcedureReference("가상상품은 어떻게 신청하나요?", title, document, "다른상품만 신청할 수 있습니다.")).isFalse();
        assertThat(DocumentClaimRelevance.generalProcedureReference("가상상품은 어떻게 신청하나요?", title, document, "다른상품 신청은 앱에서 합니다.")).isFalse();
        assertThat(DocumentClaimRelevance.generalProcedureReference("가상상품 서류는 무엇인가요?", title, document, "가상 서류, 다른상품 서류 등을 제출합니다.")).isFalse();
        assertThat(DocumentClaimRelevance.generalProcedureReference("가상상품은 얼마 후 가능한가요?", title, document, "앱에서 신청합니다.")).isFalse();
    }
    @Test void singleSubjectSectionCanIdentifyClaimWithinMultiSubjectDocument() {
        assertThat(DocumentClaimRelevance.matchedPurposesInSection("가상상품 신청 방법은?", "가상상품·다른상품 안내", "앱에서 신청합니다.", "가상상품"))
                .containsExactly("신청");
        assertThat(DocumentClaimRelevance.matchedPurposesInSection("가상상품 신청 방법은?", "가상상품·다른상품 안내", "앱에서 신청합니다.", "다른상품"))
                .isEmpty();
        assertThat(DocumentClaimRelevance.matchedPurposesInSection("가상상품 신청 방법은?", "가상상품·다른상품 안내", "앱에서 신청합니다.", null))
                .isEmpty();
    }
    @Test void effectAndTransactionQuestionsRequireRelatedApprovedEvidence() {
        assertThat(DocumentClaimRelevance.matchedPurposes("가상거래 안심서비스에 가입하면 점수가 떨어지나요?", "가상거래 안심서비스 안내", "신용평점에는 영향을 미치지 않습니다."))
                .containsExactly("영향");
        assertThat(DocumentClaimRelevance.matchedPurposes("가상거래 안심서비스에서 어떤 금융거래가 차단되나요?", "가상거래 안심서비스 안내", "신규 거래가 차단됩니다."))
                .containsExactly("이용 제한");
        assertThat(DocumentClaimRelevance.matchedPurposes("가상거래 안심서비스에서 어떤 금융거래가 차단되나요?", "다른거래 안심서비스 안내", "가상거래 안심서비스와 함께 거래가 차단됩니다."))
                .isEmpty();
    }
    @Test void oneUnmatchedSubjectWordDoesNotHideApprovedProcedure() {
        assertThat(DocumentClaimRelevance.matchedPurposes("가상사업자 전용대출 갈아타기 신청은 어떻게 하나요?", "가상사업자 대출 갈아타기", "은행 앱에서 신청합니다."))
                .containsExactly("신청");
    }
    @Test void titleProvidesScopeButClaimMustAnswerPurpose() {
        assertThat(DocumentClaimRelevance.supports("가상상품 수수료가 있나요?", "가상상품 안내", "기존 수수료를 비교하세요.")).isTrue();
        assertThat(DocumentClaimRelevance.supports("가상상품 수수료가 있나요?", "가상상품 안내", "가입 안내입니다.")).isFalse();
    }
    @Test void unknownOrMixedSubjectAndUnsupportedNumbersFailClosed() {
        assertThat(DocumentClaimRelevance.supports("다른상품 수수료가 있나요?", "가상상품 안내", "기존 수수료를 비교하세요.")).isFalse();
        assertThat(DocumentClaimRelevance.supports("가상상품 10억 한도는 얼마인가요?", "가상상품 안내", "한도는 1억 원입니다.")).isFalse();
    }
    @Test void amountQuestionsRequireNumbersAndAllRequestedPurposes() {
        assertThat(DocumentClaimRelevance.supports("가상상품 한도는 얼마인가요?", "가상상품 안내", "한도 변경 이유를 설명합니다.")).isFalse();
        assertThat(DocumentClaimRelevance.supports("가상상품 신청하고 해제하나요?", "가상상품 안내", "신청할 수 있습니다.")).isFalse();
        assertThat(DocumentClaimRelevance.supports("가상상품 신청하고 해제하나요?", "가상상품 안내", "신청 및 해제할 수 있습니다.")).isTrue();
    }
    @Test void separateClaimsCanCoverDifferentRequestedPurposes() {
        String question = "가상상품에 필요한 서류와 이용 시간은 어떻게 되나요?";
        assertThat(DocumentClaimRelevance.requestedPurposes(question)).containsExactly("필요 서류", "이용 시간");
        assertThat(DocumentClaimRelevance.matchedPurposes(question, "가상상품 안내", "신청 서류를 제출합니다."))
                .containsExactly("필요 서류");
        assertThat(DocumentClaimRelevance.matchedPurposes(question, "가상상품 안내", "이용 시간은 오전 9시부터 오후 4시까지입니다."))
                .containsExactly("이용 시간");
        assertThat(DocumentClaimRelevance.matchedPurposes(question, "다른상품 안내", "이용 시간은 오전 9시부터 오후 4시까지입니다."))
                .isEmpty();
    }
    @Test void conditionalEnrollmentIsNotARequestForEnrollmentInstructions() {
        String question = "가상상품에 가입하면 어떤 기능이 제한되나요?";
        assertThat(DocumentClaimRelevance.requestedPurposes(question)).containsExactly("이용 제한");
        assertThat(DocumentClaimRelevance.matchedPurposes(question, "가상상품 안내", "영업점에서 가입할 수 있습니다."))
                .isEmpty();
        assertThat(DocumentClaimRelevance.matchedPurposes(question, "가상상품 안내", "가입하면 거래가 중단됩니다."))
                .containsExactly("이용 제한");
    }
    @Test void enumeratedTitleCannotTransferAnotherProductToAClaim() {
        assertThat(DocumentClaimRelevance.matchedPurposes("가상상품 수수료가 있나요?", "가상상품과 다른상품 안내", "다른상품의 수수료를 확인하세요."))
                .isEmpty();
    }
}
