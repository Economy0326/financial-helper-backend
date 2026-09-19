package com.financialhelper.procedure;

import com.financialhelper.retrieval.ConfirmedCaseSnapshotData;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CardCaseFactExtractorTest {
    @Test
    void extractsMultipleExplicitFactsFromOneUserStatement() {
        ConfirmedCaseSnapshotData snapshot = new ConfirmedCaseSnapshotData(
                null, null, 1, 0,
                List.of(new ConfirmedCaseSnapshotData.Fact(
                        "SITUATION", "situationText",
                        "KB국민카드 개인 본인 신용카드를 잃어버렸고 모르는 국내 신용판매 결제가 있어요. 아직 신고 안 했어요. 2026-09-01 사고예요.",
                        null, "USER_STATED", null)),
                List.of(), null);

        CardCaseFacts facts = CardCaseFactExtractor.fromSnapshot(snapshot);

        assertThat(facts.values()).containsEntry("institution", "㈜KB국민카드")
                .containsEntry("productType", "PERSONAL_CREDIT_CARD")
                .containsEntry("cardLost", "TRUE")
                .containsEntry("unauthorizedPayment", "TRUE")
                .containsEntry("transactionType", "CREDIT_SALE")
                .containsEntry("domestic", "TRUE")
                .containsEntry("reported", "FALSE")
                .containsEntry("incidentDate", "2026-09-01");
    }

    @Test
    void doesNotInferPersonalProductOrCreditSaleFromGenericPaymentText() {
        ConfirmedCaseSnapshotData snapshot = new ConfirmedCaseSnapshotData(
                null, null, 1, 0,
                List.of(new ConfirmedCaseSnapshotData.Fact(
                        "SITUATION", "situationText",
                        "카드를 잃어버렸고 모르는 결제가 있어요.",
                        null, "USER_STATED", null)),
                List.of(), null);

        CardCaseFacts facts = CardCaseFactExtractor.fromSnapshot(snapshot);

        assertThat(facts.value("productType")).isNull();
        assertThat(facts.value("transactionType")).isNull();
        assertThat(facts.value("unauthorizedPayment")).isEqualTo("TRUE");
    }

    @Test
    void typedFactOverridesEarlierTextWithinTheCurrentRevision() {
        ConfirmedCaseSnapshotData snapshot = new ConfirmedCaseSnapshotData(
                null, null, 2, 1,
                List.of(
                        new ConfirmedCaseSnapshotData.Fact(
                                "SITUATION", "situationText", "아직 신고 안 했어요.",
                                null, "USER_STATED", null),
                        new ConfirmedCaseSnapshotData.Fact(
                                "YES_NO_UNKNOWN", "reported", "TRUE",
                                "네", "USER_ANSWERED", null)
                ), List.of(), null);

        assertThat(CardCaseFactExtractor.fromSnapshot(snapshot).value("reported"))
                .isEqualTo("TRUE");
    }

    @Test
    void acceptsTheStructuredKookminIssuerValueAsTheApprovedCardIssuer() {
        assertThat(ProcedureVersionService.canonicalInstitution("KB_KOOKMIN_CARD"))
                .isEqualTo(ProcedureVersionService.KB_INSTITUTION);
    }

    @Test
    void normalizesCompensationLifecycleValuesWithoutInferringThem() {
        CardCaseFacts facts = CardCaseFactExtractor.fromValues(Map.of(
                "compensationStatus", "result received",
                "resultDisputed", "네"));

        assertThat(facts.value("compensationStatus")).isEqualTo("RESULT_RECEIVED");
        assertThat(facts.value("resultDisputed")).isEqualTo("TRUE");
        assertThat(CardCaseFactExtractor.fromValues(Map.of("compensationStatus", "보상 완료"))
                .value("compensationStatus")).isEqualTo("UNKNOWN");
    }
}
