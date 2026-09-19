package com.financialhelper.procedure;

import com.financialhelper.retrieval.ConfirmedCaseSnapshotData;
import com.financialhelper.consultation.ConsultationScenario;

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
        ConsultationScenario scenario = scenario(snapshot);
        ProcedureVersionData procedure = scenario == ConsultationScenario.CARD_LOSS_UNAUTHORIZED_USE
                ? procedureVersionService.requireApprovedCard()
                : procedureVersionService.requireApproved(scenario.name(),
                        "GENERIC_FINANCIAL_INSTITUTION", genericProduct(scenario));
        CardCaseFacts facts = scenario == ConsultationScenario.CARD_LOSS_UNAUTHORIZED_USE
                ? CardCaseFactExtractor.fromSnapshot(snapshot)
                : ScenarioCaseFactExtractor.fromSnapshot(snapshot);
        return specify(procedure, facts, snapshot == null ? 0 : snapshot.caseInputRevision(),
                clarificationAskedKeys, scenario);
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
        return specify(procedure, facts, caseInputRevision, clarificationAskedKeys,
                scenarioName(procedure));
    }

    public StructuredFollowUpData specify(
            ProcedureVersionData procedure,
            CardCaseFacts facts,
            long caseInputRevision,
            Set<String> clarificationAskedKeys,
            ConsultationScenario scenario
    ) {
        if (procedure == null || !procedure.status().equals(ProcedureStatus.APPROVED)) {
            throw new IllegalStateException("approved procedure is required");
        }
        Set<String> clarificationAsked = clarificationAskedKeys == null
                ? Set.of() : Set.copyOf(clarificationAskedKeys);
        List<String> missing = new ArrayList<>();
        List<FollowUpQuestionSpec> questions = new ArrayList<>();
        for (ProcedureVersionData.RequiredFact required : procedure.requiredFacts()) {
            if (!isRelevant(required.key(), facts, scenario)) {
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
                questions.add(specFor(required, scenario));
            } else if ("UNKNOWN".equalsIgnoreCase(facts.value(required.key()))
                    && !clarificationAsked.contains(required.key())
                    && clarificationSupported(required.key())) {
                questions.add(clarificationSpecFor(required, scenario));
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

    private boolean isRelevant(String factKey, CardCaseFacts facts, ConsultationScenario scenario) {
        if (scenario == ConsultationScenario.VOICE_PHISHING_SUSPICIOUS_TRANSFER
                && "FALSE".equalsIgnoreCase(facts.value("transferCompleted"))
                && Set.of("userInitiatedTransfer", "reportedToFinancialInstitution", "policeReported")
                .contains(factKey)) return false;
        if (scenario == ConsultationScenario.UNAUTHORIZED_ACCOUNT_TRANSFER
                && "FALSE".equalsIgnoreCase(facts.value("unauthorizedTransaction"))
                && !"unauthorizedTransaction".equals(factKey)) return false;
        if (scenario == ConsultationScenario.PERSONAL_INFO_SMISHING_MALICIOUS_APP
                && "institution".equals(factKey)
                && "FALSE".equalsIgnoreCase(facts.value("moneyMoved"))) return false;
        return isRelevant(factKey, facts);
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

    /** Deterministic entry point for breadth scenarios and focused tests. */
    public StructuredFollowUpData specifyForScenario(
            ConsultationScenario scenario,
            Map<String, String> explicitValues,
            long caseInputRevision,
            Set<String> clarificationAskedKeys
    ) {
        if (scenario == null || scenario == ConsultationScenario.UNKNOWN) {
            throw new IllegalArgumentException("supported scenario is required");
        }
        if (scenario == ConsultationScenario.CARD_LOSS_UNAUTHORIZED_USE) {
            return specify(procedureVersionService.requireApprovedCard(),
                    CardCaseFactExtractor.fromValues(explicitValues), caseInputRevision,
                    clarificationAskedKeys, scenario);
        }
        ProcedureVersionData procedure = procedureVersionService.requireApproved(
                scenario.name(),
                "GENERIC_FINANCIAL_INSTITUTION", genericProduct(scenario));
        return specify(procedure, ScenarioCaseFactExtractor.fromValues(explicitValues),
                caseInputRevision, clarificationAskedKeys, scenario);
    }

    private boolean clarificationSupported(String factKey) {
        return "institution".equals(factKey) || "productType".equals(factKey);
    }

    private FollowUpQuestionSpec clarificationSpecFor(ProcedureVersionData.RequiredFact fact,
                                                      ConsultationScenario scenario) {
        return switch (fact.key()) {
            case "institution" -> scenario == ConsultationScenario.CARD_LOSS_UNAUTHORIZED_USE
                    ? new FollowUpQuestionSpec(
                    fact.key(), FollowUpInputType.INSTITUTION_SELECT,
                    List.of(
                            option("KB_KOOKMIN_CARD", "KB국민카드", "KB국민카드에서 발급한 카드예요."),
                            option("OTHER", "다른 카드사예요", "다른 카드사라면 이 절차를 적용하지 않아요."),
                            option("UNKNOWN", "잘 모르겠어요", "카드사를 확인하기 어려워요.")
                    ), "카드 앞면이나 앱에서 카드사를 확인할 수 있나요?",
                     "확인할 수 있으면 해당 카드사를 선택해 주세요.", true, "CLARIFY_INSTITUTION", false)
                    : new FollowUpQuestionSpec(fact.key(), FollowUpInputType.SHORT_TEXT,
                    List.of(option("UNKNOWN", "모르겠어요", "금융회사를 확인하기 어려워요.")),
                    "이용한 금융회사를 알고 있나요?", "모르면 모르겠어요를 선택해 주세요.",
                    fact.requiredForDecision(), "IDENTIFY_FINANCIAL_INSTITUTION", true);
            case "productType" -> new FollowUpQuestionSpec(
                    fact.key(), FollowUpInputType.ENUM_SELECT,
                    List.of(
                            option("PERSONAL_CREDIT_CARD", "신용카드", "개인 본인 명의의 신용카드예요."),
                            option("OTHER", "다른 카드 종류예요", "현재는 개인 본인 신용카드만 지원해요."),
                            option("UNKNOWN", "잘 모르겠어요", "카드 종류를 확인하기 어려워요.")
                    ), "카드 앞면이나 앱에서 '신용' 또는 '체크' 표시를 확인할 수 있나요?",
                    "확인할 수 있으면 해당 카드 종류를 선택해 주세요.", true, "CLARIFY_PRODUCT", false);
            default -> new FollowUpQuestionSpec(fact.key(), FollowUpInputType.SHORT_TEXT,
                    List.of(option("UNKNOWN", "정확히 모르겠어요", "확인하기 어려워요.")),
                    "이 내용을 확인할 수 있나요?", "확인하기 어렵다면 모르겠어요를 선택해 주세요.",
                    fact.requiredForDecision(), "CLARIFY_" + fact.key().toUpperCase(), false);
        };
    }

    private FollowUpQuestionSpec specFor(ProcedureVersionData.RequiredFact fact) {
        return specFor(fact, ConsultationScenario.CARD_LOSS_UNAUTHORIZED_USE);
    }

    private FollowUpQuestionSpec specFor(ProcedureVersionData.RequiredFact fact,
                                         ConsultationScenario scenario) {
        return switch (fact.key()) {
            case "institution" -> scenario == ConsultationScenario.CARD_LOSS_UNAUTHORIZED_USE
                    ? new FollowUpQuestionSpec(
                    fact.key(), FollowUpInputType.INSTITUTION_SELECT,
                    List.of(
                            option("KB_KOOKMIN_CARD", "KB국민카드", "KB국민카드에서 발급한 카드예요."),
                            option("OTHER", "다른 카드사예요", "다른 카드사라면 이 절차를 적용하지 않아요."),
                            option("UNKNOWN", "잘 모르겠어요", "카드사를 확인하기 어려워요.")
                    ), "카드를 발급한 카드사가 어디인가요?", "카드 앞면이나 앱에서 확인할 수 있어요.",
                    true, "IDENTIFY_INSTITUTION", false)
                    : new FollowUpQuestionSpec(fact.key(), FollowUpInputType.SHORT_TEXT,
                    List.of(option("UNKNOWN", "모르겠어요", "금융회사를 확인하기 어려워요.")),
                    "이용한 금융회사를 알고 있나요?", "모르면 모르겠어요를 선택해 주세요.",
                    fact.requiredForDecision(), "IDENTIFY_FINANCIAL_INSTITUTION", true);
            case "productType" -> new FollowUpQuestionSpec(
                    fact.key(), FollowUpInputType.ENUM_SELECT,
                    List.of(
                            option("PERSONAL_CREDIT_CARD", "개인 본인 신용카드", "본인 명의의 신용카드예요."),
                            option("OTHER", "다른 카드 종류예요", "현재는 개인 본인 신용카드만 지원해요."),
                            option("UNKNOWN", "잘 모르겠어요", "카드 종류를 확인하기 어려워요.")
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
            case "transactionDate" -> new FollowUpQuestionSpec(
                    fact.key(), FollowUpInputType.DATE,
                    List.of(option("UNKNOWN", "정확히 기억나지 않아요", "거래 날짜를 확인하기 어려워요.")),
                    "거래가 발생한 날짜를 알려주세요.", "정확한 날짜를 모르면 모르겠어요를 선택해 주세요.", true, "IDENTIFY_TRANSACTION_DATE", true);
            case "transferCompleted" -> yesNoUnknown(fact.key(), "돈을 이미 송금했나요?", "송금 여부를 선택해 주세요.", "TRANSFER_COMPLETED");
            case "userInitiatedTransfer" -> yesNoUnknown(fact.key(), "본인이 직접 송금했나요?", "직접 송금했는지 선택해 주세요.", "USER_INITIATED_TRANSFER");
            case "suspiciousTransfer" -> yesNoUnknown(fact.key(), "사기나 보이스피싱이 의심되나요?", "의심 여부를 선택해 주세요.", "SUSPICIOUS_TRANSFER");
            case "unauthorizedTransaction" -> yesNoUnknown(fact.key(), "본인이 하지 않은 계좌 거래인가요?", "본인 거래인지 선택해 주세요.", "UNAUTHORIZED_TRANSACTION");
            case "reportedToFinancialInstitution" -> yesNoUnknown(fact.key(), "금융회사에 신고했나요?", "신고 여부를 선택해 주세요.", "FINANCIAL_INSTITUTION_REPORT");
            case "policeReported" -> yesNoUnknown(fact.key(), "경찰에 신고했나요?", "경찰 신고 여부를 선택해 주세요.", "POLICE_REPORT");
            case "financialLossOccurred", "moneyMoved" -> yesNoUnknown(fact.key(), "금전 피해가 발생했나요?", "돈이 실제로 이동했는지 선택해 주세요.", "MONEY_MOVED");
            case "suspiciousLinkClicked" -> yesNoUnknown(fact.key(), "의심스러운 링크를 눌렀나요?", "링크 클릭 여부를 선택해 주세요.", "SUSPICIOUS_LINK");
            case "maliciousAppInstalled" -> yesNoUnknown(fact.key(), "의심스러운 앱을 설치했나요?", "앱 설치 여부를 선택해 주세요.", "MALICIOUS_APP");
            case "remoteControlUsed" -> yesNoUnknown(fact.key(), "원격제어 앱이 사용됐나요?", "원격제어 여부를 선택해 주세요.", "REMOTE_CONTROL");
            case "personalInfoExposed" -> yesNoUnknown(fact.key(), "개인정보를 전달했나요?", "개인정보 노출 여부를 선택해 주세요.", "PERSONAL_INFO");
            case "authenticationInfoExposed", "accessCredentialExposed" -> yesNoUnknown(fact.key(), "인증정보나 비밀번호가 노출됐나요?", "인증정보 노출 여부를 선택해 주세요.", "AUTH_EXPOSURE");
            case "transactionChannel" -> new FollowUpQuestionSpec(fact.key(), FollowUpInputType.ENUM_SELECT,
                    List.of(option("BANK_APP", "은행 앱", "은행 앱에서 거래했어요."), option("ATM", "ATM", "현금자동입출금기에서 거래했어요."), option("UNKNOWN", "모르겠어요", "수단을 확인하기 어려워요.")),
                    "어떤 수단으로 거래했나요?", "거래 수단을 선택해 주세요.", fact.requiredForDecision(), "TRANSACTION_CHANNEL", false);
            default -> throw new IllegalArgumentException("unsupported CARD follow-up fact: " + fact.key());
        };
    }

    private ConsultationScenario scenario(ConfirmedCaseSnapshotData snapshot) {
        String value = value(snapshot, "scenario", null);
        if (value == null) return ConsultationScenario.CARD_LOSS_UNAUTHORIZED_USE;
        try { return ConsultationScenario.valueOf(value); }
        catch (IllegalArgumentException ignored) { return ConsultationScenario.UNKNOWN; }
    }

    private ConsultationScenario scenarioName(ProcedureVersionData procedure) {
        try { return ConsultationScenario.valueOf(procedure.scenario()); }
        catch (Exception ignored) { return ConsultationScenario.CARD_LOSS_UNAUTHORIZED_USE; }
    }

    private String value(ConfirmedCaseSnapshotData snapshot, String key, String fallback) {
        if (snapshot != null) for (ConfirmedCaseSnapshotData.Fact fact : snapshot.facts()) {
            if (fact != null && key.equals(fact.key()) && fact.value() != null) return fact.value();
        }
        return fallback;
    }

    private String genericProduct(ConsultationScenario scenario) {
        return scenario == ConsultationScenario.PERSONAL_INFO_SMISHING_MALICIOUS_APP
                ? "DIGITAL_FINANCIAL_SERVICE" : "BANK_ACCOUNT";
    }

    private FollowUpQuestionSpec yesNoUnknown(
            String factKey,
            String question,
            String description,
            String intent
    ) {
        String trueLabel = switch (factKey) {
            case "domestic" -> "국내에서 발생한 거래예요";
            case "reported", "reportedToFinancialInstitution", "policeReported" -> "이미 신고했어요";
            case "cardLost" -> "카드를 잃어버렸어요";
            case "transferCompleted" -> "이미 송금했어요";
            case "userInitiatedTransfer" -> "제가 직접 송금했어요";
            case "unauthorizedTransaction" -> "제가 하지 않은 거래예요";
            case "suspiciousTransfer" -> "사기나 보이스피싱이 의심돼요";
            case "financialLossOccurred", "moneyMoved" -> "금전 피해가 발생했어요";
            case "suspiciousLinkClicked" -> "의심스러운 링크를 눌렀어요";
            case "maliciousAppInstalled" -> "의심스러운 앱을 설치했어요";
            case "remoteControlUsed" -> "원격제어 앱이 사용됐어요";
            case "personalInfoExposed" -> "개인정보를 전달했어요";
            case "authenticationInfoExposed", "accessCredentialExposed" -> "인증정보가 노출됐어요";
            default -> "해당돼요";
        };
        String falseLabel = switch (factKey) {
            case "domestic" -> "해외에서 발생한 거래예요";
            case "reported", "reportedToFinancialInstitution", "policeReported" -> "아직 신고하지 않았어요";
            case "cardLost" -> "카드는 가지고 있어요";
            case "transferCompleted" -> "아직 송금하지 않았어요";
            case "userInitiatedTransfer" -> "제가 송금하지 않았어요";
            case "unauthorizedTransaction" -> "제가 한 거래예요";
            case "suspiciousTransfer" -> "사기나 보이스피싱이 의심되지 않아요";
            case "financialLossOccurred", "moneyMoved" -> "금전 피해가 발생하지 않았어요";
            case "suspiciousLinkClicked" -> "의심스러운 링크를 누르지 않았어요";
            case "maliciousAppInstalled" -> "의심스러운 앱을 설치하지 않았어요";
            case "remoteControlUsed" -> "원격제어 앱이 사용되지 않았어요";
            case "personalInfoExposed" -> "개인정보를 전달하지 않았어요";
            case "authenticationInfoExposed", "accessCredentialExposed" -> "인증정보가 노출되지 않았어요";
            default -> "해당되지 않아요";
        };
        return new FollowUpQuestionSpec(
                factKey, FollowUpInputType.YES_NO_UNKNOWN,
                List.of(
                        option("TRUE", trueLabel, ""),
                        option("FALSE", falseLabel, ""),
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
