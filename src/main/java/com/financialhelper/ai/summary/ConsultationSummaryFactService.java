package com.financialhelper.ai.summary;

import com.financialhelper.procedure.ProcedureVersionService;
import com.financialhelper.retrieval.ConfirmedCaseSnapshotData;
import com.financialhelper.retrieval.ConfirmedCaseSnapshotService;

import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class ConsultationSummaryFactService {

    private static final Map<String, String> LABELS = Map.ofEntries(
            Map.entry("institution", "카드사"),
            Map.entry("productType", "카드 종류"),
            Map.entry("cardLost", "카드 상태"),
            Map.entry("unauthorizedPayment", "본인이 하지 않은 결제"),
            Map.entry("domestic", "거래 지역"),
            Map.entry("transactionType", "결제 유형"),
            Map.entry("reported", "신고 상태"),
            Map.entry("incidentDate", "발생일"),
            Map.entry("transferCompleted", "송금 완료 여부"),
            Map.entry("userInitiatedTransfer", "본인 직접 송금 여부"),
            Map.entry("suspiciousTransfer", "사기·보이스피싱 의심 여부"),
            Map.entry("unauthorizedTransaction", "본인 미실행 계좌 거래 여부"),
            Map.entry("moneyMoved", "금전 이동 여부"),
            Map.entry("suspiciousLinkClicked", "의심 링크 클릭 여부"),
            Map.entry("maliciousAppInstalled", "의심 앱 설치 여부"),
            Map.entry("personalInfoExposed", "개인정보 노출 여부"),
            Map.entry("authenticationInfoExposed", "인증정보 노출 여부")
    );

    private static final DateTimeFormatter KOREAN_DATE =
            DateTimeFormatter.ofPattern("yyyy년 M월 d일", Locale.KOREAN);

    private final ConfirmedCaseSnapshotService snapshotService;

    public ConsultationSummaryFactService(ConfirmedCaseSnapshotService snapshotService) {
        this.snapshotService = snapshotService;
    }

    public List<ConsultationSummaryStateResponse.Fact> currentFacts(
            UUID consultationId,
            long caseInputRevision,
            long followUpAnswerRevision
    ) {
        return snapshotService.findCurrent(
                        consultationId, caseInputRevision, followUpAnswerRevision)
                .map(this::toSummaryFacts)
                .orElseGet(List::of);
    }

    private List<ConsultationSummaryStateResponse.Fact> toSummaryFacts(
            ConfirmedCaseSnapshotData snapshot
    ) {
        Map<String, ConfirmedCaseSnapshotData.Fact> currentByKey = new LinkedHashMap<>();
        for (ConfirmedCaseSnapshotData.Fact fact : snapshot.facts()) {
            if (fact != null && isUserFacingFact(fact.key())) {
                currentByKey.put(fact.key(), fact);
            }
        }

        return currentByKey.values().stream()
                .map(this::toSummaryFact)
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    private ConsultationSummaryStateResponse.Fact toSummaryFact(
            ConfirmedCaseSnapshotData.Fact fact
    ) {
        String displayValue = displayValue(fact.key(), fact.value());
        if (displayValue == null) {
            return null;
        }
        return new ConsultationSummaryStateResponse.Fact(
                fact.key(), LABELS.getOrDefault(fact.key(), fact.key()), fact.value(), displayValue);
    }

    private String displayValue(String key, String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        if ("UNKNOWN".equalsIgnoreCase(value)) {
            return "잘 모르겠음";
        }
        return switch (key) {
            case "institution" -> ProcedureVersionService.KB_INSTITUTION.equals(
                    ProcedureVersionService.canonicalInstitution(value))
                    ? "KB국민카드" : value;
            case "productType" -> ProcedureVersionService.PERSONAL_CREDIT_CARD.equals(
                    ProcedureVersionService.canonicalProduct(value))
                    ? "개인 본인 신용카드" : value;
            case "cardLost" -> booleanLabel(value, "분실함", "가지고 있음");
            case "unauthorizedPayment" -> booleanLabel(value, "있음", "없음");
            case "domestic" -> booleanLabel(value, "국내", "해외");
            case "transactionType" -> "CREDIT_SALE".equalsIgnoreCase(value)
                    ? "일반 카드 결제" : value;
            case "reported" -> booleanLabel(value, "이미 신고함", "아직 신고하지 않음");
            case "incidentDate" -> koreanDate(value);
            default -> booleanLabel(value, "예", "아니요");
        };
    }

    private boolean isUserFacingFact(String key) {
        return key != null && !Set.of("scenario", "situationText", "internalProcedure").contains(key);
    }

    private String booleanLabel(String value, String trueLabel, String falseLabel) {
        if ("TRUE".equalsIgnoreCase(value)) {
            return trueLabel;
        }
        if ("FALSE".equalsIgnoreCase(value)) {
            return falseLabel;
        }
        return null;
    }

    private String koreanDate(String value) {
        try {
            return LocalDate.parse(value).format(KOREAN_DATE);
        } catch (DateTimeParseException exception) {
            return null;
        }
    }
}
