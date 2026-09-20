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
import java.util.UUID;

@Service
public class ConsultationSummaryFactService {

    private static final List<String> CARD_FACT_ORDER = List.of(
            "institution",
            "productType",
            "cardLost",
            "unauthorizedPayment",
            "domestic",
            "transactionType",
            "reported",
            "incidentDate"
    );

    private static final Map<String, String> LABELS = Map.of(
            "institution", "카드사",
            "productType", "카드 종류",
            "cardLost", "카드 상태",
            "unauthorizedPayment", "본인이 하지 않은 결제",
            "domestic", "거래 지역",
            "transactionType", "결제 유형",
            "reported", "신고 상태",
            "incidentDate", "발생일"
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
            if (fact != null && CARD_FACT_ORDER.contains(fact.key())) {
                currentByKey.put(fact.key(), fact);
            }
        }

        return CARD_FACT_ORDER.stream()
                .map(currentByKey::get)
                .filter(java.util.Objects::nonNull)
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
                fact.key(), LABELS.get(fact.key()), fact.value(), displayValue);
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
                    ? "KB국민카드" : null;
            case "productType" -> ProcedureVersionService.PERSONAL_CREDIT_CARD.equals(
                    ProcedureVersionService.canonicalProduct(value))
                    ? "개인 본인 신용카드" : null;
            case "cardLost" -> booleanLabel(value, "분실함", "가지고 있음");
            case "unauthorizedPayment" -> booleanLabel(value, "있음", "없음");
            case "domestic" -> booleanLabel(value, "국내", "해외");
            case "transactionType" -> "CREDIT_SALE".equalsIgnoreCase(value)
                    ? "일반 카드 결제" : null;
            case "reported" -> booleanLabel(value, "이미 신고함", "아직 신고하지 않음");
            case "incidentDate" -> koreanDate(value);
            default -> null;
        };
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
