package com.financialhelper.procedure;

import com.financialhelper.retrieval.ConfirmedCaseSnapshotData;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ScenarioCaseFactExtractorTest {
    @Test
    void keepsExplicitFactsAndDoesNotInferFromUnknownText() {
        ConfirmedCaseSnapshotData snapshot = new ConfirmedCaseSnapshotData(
                UUID.randomUUID(), UUID.randomUUID(), 1, 1,
                List.of(new ConfirmedCaseSnapshotData.Fact("SITUATION", "situationText",
                        "상대방 지시에 속아서 송금했어요.", null, "USER_STATED", null)), List.of(), null);

        CardCaseFacts facts = ScenarioCaseFactExtractor.fromSnapshot(snapshot);

        assertThat(facts.value("transferCompleted")).isEqualTo("TRUE");
        assertThat(facts.value("suspiciousTransfer")).isEqualTo("TRUE");
        assertThat(facts.value("unauthorizedTransaction")).isNull();
    }

    @Test
    void normalizesEquivalentStructuredFactKeysWithoutLosingExplicitValues() {
        CardCaseFacts facts = ScenarioCaseFactExtractor.fromValues(Map.of(
                "transferMade", "FALSE",
                "financialInstitutionReported", "FALSE"));

        assertThat(facts.value("transferCompleted")).isEqualTo("FALSE");
        assertThat(facts.value("reportedToFinancialInstitution")).isEqualTo("FALSE");
        assertThat(facts.value("reported")).isEqualTo("FALSE");
    }
}
