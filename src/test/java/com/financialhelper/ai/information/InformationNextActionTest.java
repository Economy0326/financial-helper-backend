package com.financialhelper.ai.information;

import com.financialhelper.source.VerifiedPdfMetadata;
import com.financialhelper.source.OfficialInformationClaimSelector;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class InformationNextActionTest {
    @Test void onlyReviewedDepositQuestionTwoOffersProductCheck() {
        assertThat(InformationNextAction.from(List.of(evidence(2, VerifiedPdfMetadata.DEPOSIT_RAW_SHA256))))
                .extracting(InformationNextAction::title)
                .containsExactly("공식 안내 원문 확인", "가입 상품의 운용 조건 문의");
        assertThat(InformationNextAction.from(List.of(evidence(4, VerifiedPdfMetadata.DEPOSIT_RAW_SHA256),
                evidence(2, "0".repeat(64))))).isEmpty();
    }

    @Test void sixTopicActionsStayBoundToSelectedOfficialSourceAndInsuranceCondition() {
        for (String key : OfficialInformationClaimSelector.sourceKeys()) {
            var withoutCondition = informationEvidence(key, List.of());
            var selected = withoutCondition;
            if ("fsc-silsong24-guide".equals(key)) {
                assertThat(InformationNextAction.from(List.of(withoutCondition))).isEmpty();
                selected = informationEvidence(key, List.of(
                        new InformationEvidenceSnapshot.RelatedText(UUID.randomUUID(), "loc",
                                "연계 완료 : 실손24 연계가 완료되어 청구전산화 서비스를 이용할 수 있는 요양기관")));
            }
            var actions = InformationNextAction.from(List.of(selected));
            assertThat(actions).hasSize(1);
            assertThat(actions.getFirst().sourceUrl()).isEqualTo(OfficialInformationClaimSelector.officialUrl(key));
            assertThat(actions.getFirst().supportChunkId()).isEqualTo(selected.supportChunkId());
        }
    }

    private static InformationEvidenceSnapshot.Evidence informationEvidence(String key,
            List<InformationEvidenceSnapshot.RelatedText> conditions) {
        return new InformationEvidenceSnapshot.Evidence(UUID.randomUUID(), 1, "1".repeat(64), UUID.randomUUID(),
                UUID.randomUUID(), "COMMON", "공식기관", "검토된 문서",
                OfficialInformationClaimSelector.officialUrl(key), null, null, "loc", "검토 범위",
                null, null, null, null, conditions, List.of(), null, null);
    }

    private static InformationEvidenceSnapshot.Evidence evidence(int question, String hash) {
        return new InformationEvidenceSnapshot.Evidence(UUID.randomUUID(), 1, hash, UUID.randomUUID(),
                UUID.randomUUID(), "COMMON", "금융위원회", "예금보호한도 상향 관련 주요 QA",
                VerifiedPdfMetadata.DEPOSIT_PDF_URL, null, "3", "p3", "검토 범위",
                null, null, null, null, List.of(), List.of(), question,
                VerifiedPdfMetadata.DEPOSIT_POST_URL);
    }
}
