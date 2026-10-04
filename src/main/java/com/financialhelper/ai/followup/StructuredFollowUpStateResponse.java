package com.financialhelper.ai.followup;

import com.financialhelper.procedure.FollowUpInputType;
import com.financialhelper.procedure.FollowUpQuestionSpec;

import java.util.List;
import java.util.UUID;

public record StructuredFollowUpStateResponse(
        String kind,
        long caseInputRevision,
        List<String> missingFacts,
        Question question,
        Integer currentQuestionNumber,
        Integer totalQuestions,
        String savedAnswer,
        String unsupportedReason,
        BlockingFact blockingFact,
        String message
) {
    public StructuredFollowUpStateResponse {
        missingFacts = missingFacts == null ? List.of() : List.copyOf(missingFacts);
    }

    public static StructuredFollowUpStateResponse complete(long revision) {
        return new StructuredFollowUpStateResponse("complete", revision, List.of(), null, null, null, null, null, null, null);
    }

    public static StructuredFollowUpStateResponse question(
            FollowUpQuestion entity,
            List<FollowUpQuestionSpec.Option> options,
            int total
    ) {
        return question(entity, options, total, List.of(entity.getFactKey()));
    }

    public static StructuredFollowUpStateResponse question(
            FollowUpQuestion entity,
            List<FollowUpQuestionSpec.Option> options,
            int total,
            List<String> missingFacts
    ) {
        return new StructuredFollowUpStateResponse(
                "question",
                entity.getCaseInputRevision(),
                missingFacts,
                new Question(entity.getId(), entity.getFactKey(),
                        parseInputType(entity.getInputType()), options,
                        entity.getQuestionText(), entity.getDescription(),
                        entity.isRequiredForDecision(), entity.getQuestionIntent()),
                entity.getSequenceNo(), total, entity.getAnswerValue(), null, null, null);
    }

    public static StructuredFollowUpStateResponse unsupported(
            FollowUpQuestion entity, List<FollowUpQuestionSpec.Option> options,
            int total, String reason
    ) {
        StructuredFollowUpStateResponse question = question(entity, options, total, List.of());
        return new StructuredFollowUpStateResponse("unsupported", question.caseInputRevision(),
                List.of(), question.question(), question.currentQuestionNumber(),
                question.totalQuestions(), question.savedAnswer(), reason, null, null);
    }

    public static StructuredFollowUpStateResponse insufficient(FollowUpQuestion entity,
            List<FollowUpQuestionSpec.Option> options, int total) {
        StructuredFollowUpStateResponse question = question(entity, options, total, List.of(entity.getFactKey()));
        return new StructuredFollowUpStateResponse("insufficient_information", question.caseInputRevision(),
                List.of(entity.getFactKey()), question.question(), question.currentQuestionNumber(),
                question.totalQuestions(), question.savedAnswer(), null,
                new BlockingFact(entity.getFactKey(), canonicalFactLabel(entity.getFactKey())),
                "현재 정보만으로는 구체적인 안내를 만들기 어려워요.");
    }

    public record BlockingFact(String key, String label) {}

    public record Question(
            UUID id,
            String factKey,
            FollowUpInputType inputType,
            List<FollowUpQuestionSpec.Option> options,
            String question,
            String description,
            boolean requiredForDecision,
            String questionIntent
    ) {
        public Question {
            options = options == null ? List.of() : List.copyOf(options);
        }
    }

    private static FollowUpInputType parseInputType(String value) {
        if (value == null) {
            return FollowUpInputType.SHORT_TEXT;
        }
        try {
            return FollowUpInputType.valueOf(value);
        } catch (IllegalArgumentException ignored) {
            return FollowUpInputType.SHORT_TEXT;
        }
    }

    /** User-facing label supplied by the backend; clients must not infer fact semantics. */
    private static String canonicalFactLabel(String factKey) {
        if (factKey == null) return "확인할 정보";
        return switch (factKey) {
            case "institution" -> "카드사 또는 금융회사";
            case "productType" -> "카드 종류";
            case "cardLost" -> "카드 분실·도난 여부";
            case "unauthorizedPayment", "unauthorizedTransaction" -> "본인 아닌 거래 여부";
            case "transactionType" -> "거래 유형";
            case "domestic" -> "거래 지역";
            case "reported", "reportedToFinancialInstitution", "policeReported" -> "신고 여부";
            case "transferCompleted" -> "송금 여부";
            case "userInitiatedTransfer" -> "본인 송금 여부";
            case "suspiciousTransfer" -> "사기 의심 여부";
            case "suspiciousLinkClicked" -> "의심 링크 클릭 여부";
            case "maliciousAppInstalled" -> "의심 앱 설치 여부";
            case "remoteControlUsed" -> "원격 제어 사용 여부";
            case "personalInfoExposed" -> "개인정보 노출 여부";
            case "authenticationInfoExposed", "accessCredentialExposed" -> "인증정보 노출 여부";
            case "incidentDate", "transactionDate" -> "발생일";
            default -> "확인이 필요한 정보";
        };
    }
}
