package com.financialhelper.ai.information;

import com.financialhelper.source.VerifiedPdfMetadata;
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

    private static InformationEvidenceSnapshot.Evidence evidence(int question, String hash) {
        return new InformationEvidenceSnapshot.Evidence(UUID.randomUUID(), 1, hash, UUID.randomUUID(),
                UUID.randomUUID(), "COMMON", "금융위원회", "예금보호한도 상향 관련 주요 QA",
                VerifiedPdfMetadata.DEPOSIT_PDF_URL, null, "3", "p3", "검토 범위",
                null, null, null, null, List.of(), List.of(), question,
                VerifiedPdfMetadata.DEPOSIT_POST_URL);
    }
}
