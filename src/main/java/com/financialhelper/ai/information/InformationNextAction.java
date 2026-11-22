package com.financialhelper.ai.information;

import com.financialhelper.source.VerifiedPdfMetadata;
import com.financialhelper.source.OfficialInformationClaimSelector;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** User-run checks derived only from selected, revalidated official evidence. */
public record InformationNextAction(String title, String description, String sourceUrl, UUID supportChunkId) {
    public static List<InformationNextAction> from(List<InformationEvidenceSnapshot.Evidence> selected) {
        List<InformationNextAction> actions = new ArrayList<>();
        for (var evidence : selected) {
            for (String key : OfficialInformationClaimSelector.sourceKeys()) {
                if (evidence.questionNumber() == null
                        && OfficialInformationClaimSelector.officialUrl(key).equals(evidence.canonicalUrl())) {
                    InformationNextAction next = informationAction(key, evidence);
                    if (next != null) actions.add(next);
                }
            }
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

    private static InformationNextAction informationAction(String key,
            InformationEvidenceSnapshot.Evidence evidence) {
        String title;
        String description;
        switch (key) {
            case "fsc-card-use-qa" -> {
                title = "카드사 분실신고 안내 확인";
                description = "공식 안내를 확인하고 가입 카드사의 공식 신고 경로를 직접 찾아보세요.";
            }
            case "police-campaign" -> {
                title = "112 피해 신고 안내 확인";
                description = "금전 피해가 발생한 경우 경찰청의 112 신고 안내를 직접 확인하세요.";
            }
            case "fsc-early-repayment-qa" -> {
                title = "계약일과 적용 범위 확인";
                description = "공식 문답과 본인의 계약서를 대조해 적용 시점을 직접 확인하세요.";
            }
            case "fsc-silsong24-guide" -> {
                if (evidence.conditions().stream().noneMatch(condition ->
                        condition.text().contains("연계 완료")
                                && condition.text().contains("이용할 수 있는 요양기관"))) return null;
                title = "실손24 참여 의료기관 확인";
                description = "공식 안내에서 연계 의료기관 조건을 확인하고 이용 가능 여부를 직접 확인하세요.";
            }
            case "law-financial-consumer-protection-19" -> {
                title = "투자상품 위험·수수료 설명 요청";
                description = "공식 조문을 확인하고 상품별 위험과 수수료 설명을 판매자에게 직접 요청하세요.";
            }
            case "fsc-consumer-complaints" -> {
                title = "민원·분쟁조정 신청 경로 확인";
                description = "금융위원회 공식 안내에서 본인에게 맞는 접수 경로를 직접 확인하세요.";
            }
            default -> { return null; }
        }
        return new InformationNextAction(title, description,
                evidence.canonicalUrl(), evidence.supportChunkId());
    }
}
