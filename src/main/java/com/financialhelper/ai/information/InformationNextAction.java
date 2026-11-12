package com.financialhelper.ai.information;

import com.financialhelper.source.VerifiedPdfMetadata;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Optional user checks derived only from selected, revalidated deposit Q&A evidence. */
public record InformationNextAction(String title, String description, String sourceUrl, UUID supportChunkId) {
    public static List<InformationNextAction> from(List<InformationEvidenceSnapshot.Evidence> selected) {
        List<InformationNextAction> actions = new ArrayList<>();
        for (var evidence : selected) {
            if (!VerifiedPdfMetadata.DEPOSIT_PDF_URL.equals(evidence.canonicalUrl())
                    || !VerifiedPdfMetadata.DEPOSIT_RAW_SHA256.equals(evidence.rawSha256())
                    || !VerifiedPdfMetadata.DEPOSIT_POST_URL.equals(evidence.metadataSourceUrl())
                    || !"금융위원회".equals(evidence.organizationName())) continue;
            Integer question = evidence.questionNumber();
            if (question == null) continue;
            if (List.of(1, 2, 3, 5, 6, 7, 8, 9, 10).contains(question)) {
                actions.add(new InformationNextAction("공식 안내 원문 확인",
                        "금융위원회 원문에서 이 문답의 조건과 적용 범위를 직접 확인해 주세요.",
                        VerifiedPdfMetadata.DEPOSIT_POST_URL, evidence.supportChunkId()));
            }
            if (question == 2) {
                actions.add(new InformationNextAction("가입 상품의 운용 조건 문의",
                        "ISA·DC·IRP는 보호상품으로 운용되는 부분인지 가입 금융기관의 공식 상담 채널에 직접 문의해 주세요.",
                        VerifiedPdfMetadata.DEPOSIT_POST_URL, evidence.supportChunkId()));
            }
        }
        return List.copyOf(actions);
    }
}
