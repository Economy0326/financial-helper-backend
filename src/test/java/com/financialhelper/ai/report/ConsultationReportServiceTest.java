package com.financialhelper.ai.report;

import com.financialhelper.procedure.ConditionResult;
import com.financialhelper.procedure.FinancialActionPlanData;
import com.financialhelper.procedure.PlanStatus;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ConsultationReportServiceTest {
    @Test
    void procedureFieldsAreComposedFromTheBackendActionPlan() {
        UUID consultationId = UUID.randomUUID();
        FinancialActionPlanData plan = new FinancialActionPlanData(
                UUID.randomUUID(), consultationId, UUID.randomUUID(), UUID.randomUUID(),
                "CARD_COMPENSATION_PROCESS", 1, 2, 3, PlanStatus.READY,
                List.of(
                        new FinancialActionPlanData.Action(
                                "confirm-compensation-application", 9, "보상 신청 절차 확인",
                                "공식 채널에서 신청 절차를 확인합니다.", ConditionResult.TRUE,
                                "kb-card-compensation-process"),
                        new FinancialActionPlanData.Action(
                                "track-compensation-process", 9, "사고 접수·조사 진행 확인",
                                "공식 채널에서 진행 상태를 확인합니다.", ConditionResult.TRUE,
                                "kb-card-compensation-process")),
                List.of(new FinancialActionPlanData.Document(
                        "case-record", "사건 관련 확인 자료", "ON_REQUEST",
                        ConditionResult.TRUE, "kb-card-compensation-process")),
                List.of(), List.of(), List.of(), List.of());

        ConsultationReportAiResult modelResult = new ConsultationReportAiResult();
        ConsultationReportAiResult.FirstAction wrongFirst =
                new ConsultationReportAiResult.FirstAction();
        wrongFirst.actionId = "invented-action";
        wrongFirst.title = "다른 행동";
        wrongFirst.description = "모델이 임의로 만든 행동";
        modelResult.firstAction = wrongFirst;
        ConsultationReportAiResult.ActionStep wrongStep =
                new ConsultationReportAiResult.ActionStep();
        wrongStep.actionId = "invented-action";
        wrongStep.order = 2;
        wrongStep.title = "다른 행동";
        wrongStep.description = "모델이 임의로 만든 행동";
        ConsultationReportAiResult.ActionStep duplicateOrderStep =
                new ConsultationReportAiResult.ActionStep();
        duplicateOrderStep.actionId = "invented-action-2";
        duplicateOrderStep.order = 2;
        duplicateOrderStep.title = "또 다른 행동";
        duplicateOrderStep.description = "모델이 임의로 만든 두 번째 행동";
        modelResult.actionSteps = List.of(wrongStep, duplicateOrderStep);
        ConsultationReportAiResult.RequiredDocument wrongDocument =
                new ConsultationReportAiResult.RequiredDocument();
        wrongDocument.documentId = "invented-document";
        wrongDocument.name = "임의 자료";
        wrongDocument.reason = "임의 사유";
        modelResult.requiredDocuments = List.of(wrongDocument);

        ConsultationReportAiResult composed =
                ConsultationReportService.composeProcedureFields(modelResult, plan);

        assertThat(composed.firstAction.actionId).isEqualTo("confirm-compensation-application");
        assertThat(composed.firstAction.title).isEqualTo("보상 신청 절차 확인");
        assertThat(composed.actionSteps).extracting(step -> step.order)
                .containsExactly(1, 2);
        assertThat(composed.actionSteps).extracting(step -> step.actionId)
                .containsExactly("confirm-compensation-application", "track-compensation-process");
        assertThat(composed.actionSteps.get(1).description)
                .isEqualTo("공식 채널에서 진행 상태를 확인합니다.");
        assertThat(composed.requiredDocuments).singleElement().satisfies(document -> {
            assertThat(document.documentId).isEqualTo("case-record");
            assertThat(document.name).isEqualTo("사건 관련 확인 자료");
        });
    }
}
