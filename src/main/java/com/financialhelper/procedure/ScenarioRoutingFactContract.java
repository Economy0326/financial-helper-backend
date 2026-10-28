package com.financialhelper.procedure;

import com.financialhelper.ai.followup.FollowUpOptionsPayload;
import com.financialhelper.ai.followup.FollowUpQuestion;
import com.financialhelper.consultation.ConsultationScenario;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Reviewed routing/date fact vocabulary. This does not approve financial evidence or actions. */
public final class ScenarioRoutingFactContract {
    public record Contract(String id, ConsultationScenario scenario, String key,
                           String factType, FollowUpInputType inputType,
                           Set<String> values, String question, String intent,
                           String purpose) {
        public boolean accepts(String value) {
            if (value == null) return true;
            if (values.contains(value)) return true;
            if (inputType != FollowUpInputType.DATE) return false;
            try { LocalDate.parse(value); return true; }
            catch (DateTimeParseException exception) { return false; }
        }

        public boolean matches(FollowUpQuestion stored, JsonMapper mapper) {
            if (!stored.isActive() || !id.equals(stored.getFactContractId())
                    || !key.equals(stored.getFactKey())
                    || !inputType.name().equals(stored.getInputType())
                    || !matchesQuestion(stored)
                    || !accepts(stored.getAnswerValue())) return false;
            try {
                FollowUpOptionsPayload options = mapper.readValue(
                        stored.getOptionsJson(), FollowUpOptionsPayload.class);
                List<String> optionValues = options.options().stream()
                        .map(FollowUpOptionsPayload.Option::value).toList();
                return optionValues.size() == values.size()
                        && Set.copyOf(optionValues).equals(values);
            } catch (JacksonException | NullPointerException | IllegalArgumentException exception) {
                return false;
            }
        }

        private boolean matchesQuestion(FollowUpQuestion stored) {
            if (question.equals(stored.getQuestionText())
                    && intent.equals(stored.getQuestionIntent())) return true;
            String[] clarification = switch (key) {
                case "cardLost" -> new String[]{"지금 실제 카드를 가지고 있는지 확인해 주세요.", "CLARIFY_CARD_LOSS"};
                case "unauthorizedPayment" -> new String[]{"최근 카드 이용내역에서 기억나지 않는 결제가 있는지 확인해 주세요.", "CLARIFY_UNAUTHORIZED_PAYMENT"};
                case "reported" -> new String[]{"KB국민카드에 카드 분실 사실을 이미 접수했는지 확인해 주세요.", "CLARIFY_LOSS_REPORT"};
                case "compensationStatus" -> new String[]{"KB국민카드에서 보상 신청이 어느 단계인지 확인해 주세요.", "CLARIFY_COMPENSATION_STATUS"};
                case "incidentDate" -> new String[]{"사고가 발생한 날짜를 확인해 주세요.", "CLARIFY_INCIDENT_DATE"};
                case "transactionDate" -> new String[]{"계좌 거래 내역에서 해당 거래가 발생한 날짜를 확인해 주세요.", "CLARIFY_TRANSACTION_DATE"};
                default -> null;
            };
            return clarification != null && clarification[0].equals(stored.getQuestionText())
                    && clarification[1].equals(stored.getQuestionIntent());
        }
    }

    private static final Set<String> YES_NO = Set.of("TRUE", "FALSE", "UNKNOWN");
    private static final Set<String> COMPENSATION = Set.of(
            "NOT_SUBMITTED", "SUBMITTED", "INVESTIGATING", "RESULT_RECEIVED", "UNKNOWN");
    private static final Set<String> UNKNOWN = Set.of("UNKNOWN");
    private static final ConsultationScenario CARD = ConsultationScenario.CARD_LOSS_UNAUTHORIZED_USE;
    private static final Map<String, Contract> CARD_ROUTING = Map.of(
            "cardLost", new Contract("CARD_ROUTING_CARD_LOST_V1", CARD, "cardLost", "BOOLEAN",
                    FollowUpInputType.YES_NO_UNKNOWN, YES_NO,
                    "카드를 잃어버렸거나 도난당했나요?", "CARD_LOSS", "CARD procedure branch"),
            "unauthorizedPayment", new Contract("CARD_ROUTING_UNAUTHORIZED_PAYMENT_V1", CARD,
                    "unauthorizedPayment", "BOOLEAN", FollowUpInputType.YES_NO_UNKNOWN, YES_NO,
                    "본인이 하지 않은 카드 결제가 있나요?", "UNAUTHORIZED_PAYMENT", "CARD procedure branch"),
            "reported", new Contract("CARD_ROUTING_LOSS_REPORTED_V1", CARD, "reported", "BOOLEAN",
                    FollowUpInputType.YES_NO_UNKNOWN, YES_NO,
                    "분실·도난 신고를 했나요?", "LOSS_REPORT_STATUS", "CARD procedure branch"),
            "compensationStatus", new Contract("CARD_ROUTING_COMPENSATION_STATUS_V1", CARD,
                    "compensationStatus", "ENUM", FollowUpInputType.ENUM_SELECT, COMPENSATION,
                    "보상 처리는 어느 단계인가요?", "COMPENSATION_STATUS", "CARD procedure branch"));
    private static final Map<ConsultationScenario, Contract> DATES = Map.of(
            CARD, new Contract("CARD_INCIDENT_DATE_V1", CARD, "incidentDate", "DATE",
                    FollowUpInputType.DATE, UNKNOWN,
                    "사고가 발생한 날짜를 알려주세요.", "IDENTIFY_INCIDENT_DATE", "version applicability"),
            ConsultationScenario.VOICE_PHISHING_SUSPICIOUS_TRANSFER,
            new Contract("VOICE_INCIDENT_DATE_V1", ConsultationScenario.VOICE_PHISHING_SUSPICIOUS_TRANSFER,
                    "incidentDate", "DATE", FollowUpInputType.DATE, UNKNOWN,
                    "사고가 발생한 날짜를 알려주세요.", "IDENTIFY_INCIDENT_DATE", "version applicability"),
            ConsultationScenario.UNAUTHORIZED_ACCOUNT_TRANSFER,
            new Contract("ACCOUNT_TRANSACTION_DATE_V1", ConsultationScenario.UNAUTHORIZED_ACCOUNT_TRANSFER,
                    "transactionDate", "DATE", FollowUpInputType.DATE, UNKNOWN,
                    "거래가 발생한 날짜를 알려주세요.", "IDENTIFY_TRANSACTION_DATE", "version applicability"));

    private ScenarioRoutingFactContract() {}
    public static Contract routing(String key) { return CARD_ROUTING.get(key); }
    public static Contract date(ConsultationScenario scenario) { return DATES.get(scenario); }
    public static Contract byId(String id) {
        if (id == null) return null;
        return java.util.stream.Stream.concat(CARD_ROUTING.values().stream(), DATES.values().stream())
                .filter(contract -> id.equals(contract.id())).findFirst().orElse(null);
    }
}
