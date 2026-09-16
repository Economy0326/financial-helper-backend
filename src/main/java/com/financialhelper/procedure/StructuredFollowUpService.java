package com.financialhelper.procedure;

import com.financialhelper.retrieval.ConfirmedCaseSnapshotData;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Determines WHAT must be asked; a language model may only render HOW later. */
@Service
public class StructuredFollowUpService {
    private final ProcedureVersionService procedureVersionService;

    public StructuredFollowUpService(ProcedureVersionService procedureVersionService) {
        this.procedureVersionService = procedureVersionService;
    }

    public StructuredFollowUpData specify(ConfirmedCaseSnapshotData snapshot) {
        ProcedureVersionData procedure = procedureVersionService.requireApprovedCard();
        CardCaseFacts facts = CardCaseFactExtractor.fromSnapshot(snapshot);
        return specify(procedure, facts, snapshot == null ? 0 : snapshot.caseInputRevision());
    }

    public StructuredFollowUpData specify(
            ConfirmedCaseSnapshotData snapshot,
            Set<String> clarificationAskedKeys
    ) {
        ProcedureVersionData procedure = procedureVersionService.requireApprovedCard();
        CardCaseFacts facts = CardCaseFactExtractor.fromSnapshot(snapshot);
        return specify(procedure, facts, snapshot == null ? 0 : snapshot.caseInputRevision(),
                clarificationAskedKeys);
    }

    public StructuredFollowUpData specify(
            ProcedureVersionData procedure,
            CardCaseFacts facts,
            long caseInputRevision
    ) {
        return specify(procedure, facts, caseInputRevision, Set.of());
    }

    /**
     * Selects the next question from the current facts. Clarification keys are
     * supplied by persistence so an UNKNOWN answer cannot create a loop.
     */
    public StructuredFollowUpData specify(
            ProcedureVersionData procedure,
            CardCaseFacts facts,
            long caseInputRevision,
            Set<String> clarificationAskedKeys
    ) {
        if (procedure == null || !procedure.status().equals(ProcedureStatus.APPROVED)) {
            throw new IllegalStateException("approved procedure is required");
        }
        Set<String> clarificationAsked = clarificationAskedKeys == null
                ? Set.of() : Set.copyOf(clarificationAskedKeys);
        List<String> missing = new ArrayList<>();
        List<FollowUpQuestionSpec> questions = new ArrayList<>();
        for (ProcedureVersionData.RequiredFact required : procedure.requiredFacts()) {
            if (!isRelevant(required.key(), facts)) {
                // The approved procedure may list a fact for the positive CARD
                // branch. Once the user explicitly says there is no
                // unauthorized payment, transaction details cannot affect any
                // supported action and must not be asked as a dead-end question.
                continue;
            }
            if (!required.requiredForDecision() || facts.hasKnownValue(required.key())) {
                continue;
            }
            missing.add(required.key());
            // UNKNOWN is an explicit user answer. Keep it as a missing fact for
            // deterministic planning. Only blocking facts get one useful,
            // deterministic clarification and never the original question again.
            if (facts.value(required.key()) == null) {
                questions.add(specFor(required));
            } else if ("UNKNOWN".equalsIgnoreCase(facts.value(required.key()))
                    && !clarificationAsked.contains(required.key())
                    && clarificationSupported(required.key())) {
                questions.add(clarificationSpecFor(required));
            }
        }
        return new StructuredFollowUpData(
                procedure.scenario(), procedure.institution(), procedure.productType(),
                caseInputRevision, missing, questions);
    }

    private boolean isRelevant(String factKey, CardCaseFacts facts) {
        if ("transactionType".equals(factKey)
                && "FALSE".equalsIgnoreCase(facts.value("unauthorizedPayment"))) {
            return false;
        }
        return true;
    }

    public StructuredFollowUpData specifyFromValues(
            Map<String, String> explicitValues,
            long caseInputRevision
    ) {
        return specify(procedureVersionService.requireApprovedCard(),
                CardCaseFactExtractor.fromValues(explicitValues), caseInputRevision);
    }

    public StructuredFollowUpData specifyFromValues(
            Map<String, String> explicitValues,
            long caseInputRevision,
            Set<String> clarificationAskedKeys
    ) {
        return specify(procedureVersionService.requireApprovedCard(),
                CardCaseFactExtractor.fromValues(explicitValues), caseInputRevision,
                clarificationAskedKeys);
    }

    private boolean clarificationSupported(String factKey) {
        return "institution".equals(factKey) || "productType".equals(factKey);
    }

    private FollowUpQuestionSpec clarificationSpecFor(ProcedureVersionData.RequiredFact fact) {
        return switch (fact.key()) {
            case "institution" -> new FollowUpQuestionSpec(
                    fact.key(), FollowUpInputType.INSTITUTION_SELECT,
                    List.of(
                            option("KB_KOOKMIN_CARD", "KB국민카드", "KB국민카드에서 발급한 카드예요."),
                            option("OTHER", "다른 카드사", "다른 카드사라면 이 절차를 적용하지 않아요."),
                            option("UNKNOWN", "그래도 모르겠어요", "카드사를 확인하기 어려워요.")
                    ), "카드 앞면이나 앱에서 카드사를 확인할 수 있나요?",
                    "확인할 수 있으면 해당 카드사를 선택해 주세요.", true, "CLARIFY_INSTITUTION", false);
            case "productType" -> new FollowUpQuestionSpec(
                    fact.key(), FollowUpInputType.ENUM_SELECT,
                    List.of(
                            option("PERSONAL_CREDIT_CARD", "신용카드", "개인 본인 명의의 신용카드예요."),
                            option("CHECK_CARD", "체크카드", "체크카드는 이 절차의 범위 밖이에요."),
                            option("UNKNOWN", "그래도 모르겠어요", "카드 종류를 확인하기 어려워요.")
                    ), "카드 앞면이나 앱에서 '신용' 또는 '체크' 표시를 확인할 수 있나요?",
                    "확인할 수 있으면 해당 카드 종류를 선택해 주세요.", true, "CLARIFY_PRODUCT", false);
            default -> throw new IllegalArgumentException("unsupported clarification fact: " + fact.key());
        };
    }

    private FollowUpQuestionSpec specFor(ProcedureVersionData.RequiredFact fact) {
        return switch (fact.key()) {
            case "institution" -> new FollowUpQuestionSpec(
                    fact.key(), FollowUpInputType.INSTITUTION_SELECT,
                    List.of(
                            option("KB_KOOKMIN_CARD", "KB국민카드", "KB국민카드에서 발급한 카드예요."),
                            option("OTHER", "다른 카드사", "다른 카드사라면 이 절차를 적용하지 않아요."),
                            option("UNKNOWN", "모르겠어요", "카드사를 확인하기 어려워요.")
                    ), "카드를 발급한 카드사가 어디인가요?", "카드 앞면이나 앱에서 확인할 수 있어요.",
                    true, "IDENTIFY_INSTITUTION", false);
            case "productType" -> new FollowUpQuestionSpec(
                    fact.key(), FollowUpInputType.ENUM_SELECT,
                    List.of(
                            option("PERSONAL_CREDIT_CARD", "개인 본인 신용카드", "본인 명의의 신용카드예요."),
                            option("CHECK_CARD", "체크카드", "체크카드는 이 절차의 범위 밖이에요."),
                            option("PREPAID_CARD", "선불카드", "선불카드는 이 절차의 범위 밖이에요."),
                            option("UNKNOWN", "모르겠어요", "카드 종류를 확인하기 어려워요.")
                    ), "어떤 종류의 카드인가요?", "카드 종류를 선택해 주세요.", true, "IDENTIFY_PRODUCT", false);
            case "cardLost" -> yesNoUnknown(fact.key(), "카드를 잃어버렸거나 도난당했나요?", "분실 또는 도난 여부를 선택해 주세요.", "CARD_LOSS");
            case "unauthorizedPayment" -> yesNoUnknown(fact.key(), "본인이 하지 않은 결제가 있나요?", "모르는 신용판매 결제가 있는지 선택해 주세요.", "UNAUTHORIZED_PAYMENT");
            case "domestic" -> yesNoUnknown(fact.key(), "그 결제는 국내에서 발생했나요?", "해외 결제라면 이 절차의 범위 밖이에요.", "DOMESTIC_SCOPE");
            case "reported" -> yesNoUnknown(fact.key(), "분실·도난 신고를 했나요?", "아직 신고하지 않았다면 '아니요'를 선택해 주세요.", "LOSS_REPORT_STATUS");
            case "transactionType" -> new FollowUpQuestionSpec(
                    fact.key(), FollowUpInputType.ENUM_SELECT,
                    List.of(
                            option("CREDIT_SALE", "신용판매 결제", "일반적인 카드 결제예요."),
                            option("CASH_ADVANCE", "현금서비스", "현금서비스는 이 절차의 범위 밖이에요."),
                            option("CARD_LOAN", "카드대출", "카드대출은 이 절차의 범위 밖이에요."),
                            option("TRANSFER", "계좌이체·송금", "송금은 이 절차의 범위 밖이에요."),
                            option("UNKNOWN", "모르겠어요", "거래 유형을 확인하기 어려워요.")
                    ), "어떤 거래 유형인가요?", "거래 내역에서 유형을 확인해 주세요.", true, "IDENTIFY_TRANSACTION", false);
            case "incidentDate" -> new FollowUpQuestionSpec(
                    fact.key(), FollowUpInputType.DATE,
                    List.of(option("UNKNOWN", "정확히 기억나지 않아요", "날짜를 확인하기 어려워요.")),
                    "사고가 발생한 날짜를 알려주세요.", "정확한 날짜를 모르면 모르겠어요를 선택해 주세요.", true, "IDENTIFY_INCIDENT_DATE", true);
            default -> throw new IllegalArgumentException("unsupported CARD follow-up fact: " + fact.key());
        };
    }

    private FollowUpQuestionSpec yesNoUnknown(
            String factKey,
            String question,
            String description,
            String intent
    ) {
        return new FollowUpQuestionSpec(
                factKey, FollowUpInputType.YES_NO_UNKNOWN,
                List.of(
                        option("TRUE", "네", "해당돼요."),
                        option("FALSE", "아니요", "해당되지 않아요."),
                        option("UNKNOWN", "잘 모르겠어요", "정확히 알기 어려워요.")
                ), question, description, true, intent, false);
    }

    private static FollowUpQuestionSpec.Option option(
            String value,
            String label,
            String description
    ) {
        return new FollowUpQuestionSpec.Option(value, label, description);
    }
}
