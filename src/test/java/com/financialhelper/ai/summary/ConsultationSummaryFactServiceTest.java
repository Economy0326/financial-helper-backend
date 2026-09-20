package com.financialhelper.ai.summary;

import com.financialhelper.procedure.ProcedureVersionService;
import com.financialhelper.retrieval.ConfirmedCaseSnapshotData;
import com.financialhelper.retrieval.ConfirmedCaseSnapshotService;

import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ConsultationSummaryFactServiceTest {

    @Test
    void mapsOnlyStoredAllowlistedFactsFromTheExactCurrentRevision() {
        ConfirmedCaseSnapshotService snapshotService = mock(ConfirmedCaseSnapshotService.class);
        ConsultationSummaryFactService service = new ConsultationSummaryFactService(snapshotService);
        UUID consultationId = UUID.randomUUID();

        ConfirmedCaseSnapshotData snapshot = new ConfirmedCaseSnapshotData(
                UUID.randomUUID(),
                consultationId,
                7L,
                3L,
                List.of(
                        fact("institution", ProcedureVersionService.KB_INSTITUTION),
                        fact("productType", ProcedureVersionService.PERSONAL_CREDIT_CARD),
                        fact("cardLost", "TRUE"),
                        fact("unauthorizedPayment", "UNKNOWN"),
                        fact("domestic", "TRUE"),
                        fact("transactionType", "CREDIT_SALE"),
                        fact("reported", "FALSE"),
                        fact("incidentDate", "2026-09-20"),
                        fact("situationText", "문장형 사용자 입력"),
                        fact("internalProcedure", "CARD_INTERNAL")),
                List.of("otherMissingFact"),
                OffsetDateTime.now());

        when(snapshotService.findCurrent(consultationId, 7L, 3L))
                .thenReturn(Optional.of(snapshot));

        List<ConsultationSummaryStateResponse.Fact> result =
                service.currentFacts(consultationId, 7L, 3L);

        assertThat(result).extracting(ConsultationSummaryStateResponse.Fact::key)
                .containsExactly(
                        "institution", "productType", "cardLost", "unauthorizedPayment",
                        "domestic", "transactionType", "reported", "incidentDate");
        assertThat(result).extracting(ConsultationSummaryStateResponse.Fact::displayValue)
                .containsExactly(
                        "KB국민카드", "개인 본인 신용카드", "분실함", "잘 모르겠음",
                        "국내", "일반 카드 결제", "아직 신고하지 않음", "2026년 9월 20일");
        verify(snapshotService).findCurrent(consultationId, 7L, 3L);
    }

    @Test
    void omitsMissingAndUnmappedValuesWithoutGuessing() {
        ConfirmedCaseSnapshotService snapshotService = mock(ConfirmedCaseSnapshotService.class);
        ConsultationSummaryFactService service = new ConsultationSummaryFactService(snapshotService);
        UUID consultationId = UUID.randomUUID();
        ConfirmedCaseSnapshotData snapshot = new ConfirmedCaseSnapshotData(
                UUID.randomUUID(), consultationId, 2L, 1L,
                List.of(
                        fact("institution", "OTHER_CARD_COMPANY"),
                        fact("transactionType", "CASH_ADVANCE"),
                        fact("cardLost", "FALSE")),
                List.of("reported"), OffsetDateTime.now());
        when(snapshotService.findCurrent(consultationId, 2L, 1L))
                .thenReturn(Optional.of(snapshot));

        assertThat(service.currentFacts(consultationId, 2L, 1L))
                .containsExactly(new ConsultationSummaryStateResponse.Fact(
                        "cardLost", "카드 상태", "FALSE", "가지고 있음"));
    }

    private ConfirmedCaseSnapshotData.Fact fact(String key, String value) {
        return new ConfirmedCaseSnapshotData.Fact(
                "FOLLOW_UP", key, value, null, "USER_ANSWERED", null);
    }
}
