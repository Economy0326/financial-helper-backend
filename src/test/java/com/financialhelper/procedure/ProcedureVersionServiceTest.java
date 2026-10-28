package com.financialhelper.procedure;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ProcedureVersionServiceTest {
    private final ProcedureVersionRepository repository = mock(ProcedureVersionRepository.class);
    private final ProcedureVersionService service = new ProcedureVersionService(
            repository, JsonMapper.builder().build());

    @Test
    void cardBranchRequiresOnlyFactsThatCanChangeItsDecision() {
        assertThat(service.selectCardBranch(new CardCaseFacts(Map.of(
                "cardLost", "UNKNOWN", "unauthorizedPayment", "TRUE"))).missingFact())
                .isEqualTo("cardLost");
        assertThat(service.selectCardBranch(new CardCaseFacts(Map.of(
                "cardLost", "FALSE", "unauthorizedPayment", "UNKNOWN"))).missingFact())
                .isEqualTo("unauthorizedPayment");
        assertThat(service.selectCardBranch(new CardCaseFacts(Map.of(
                "cardLost", "TRUE", "unauthorizedPayment", "FALSE",
                "reported", "UNKNOWN"))).scenario())
                .isEqualTo(ProcedureVersionService.CARD_LOSS_ONLY_SCENARIO);
        assertThat(service.selectCardBranch(new CardCaseFacts(Map.of(
                "cardLost", "FALSE", "unauthorizedPayment", "TRUE",
                "compensationStatus", "UNKNOWN"))).scenario())
                .isEqualTo(ProcedureVersionService.CARD_HELD_UNAUTHORIZED_SCENARIO);
        assertThat(service.selectCardBranch(new CardCaseFacts(Map.of(
                "cardLost", "TRUE", "unauthorizedPayment", "TRUE",
                "reported", "FALSE", "compensationStatus", "UNKNOWN"))).scenario())
                .isEqualTo(ProcedureVersionService.CARD_SCENARIO);
        assertThat(service.selectCardBranch(new CardCaseFacts(Map.of(
                "cardLost", "TRUE", "unauthorizedPayment", "TRUE",
                "reported", "TRUE"))).missingFact())
                .isEqualTo("compensationStatus");
        assertThat(service.selectCardBranch(new CardCaseFacts(Map.of(
                "cardLost", "TRUE", "unauthorizedPayment", "TRUE", "reported", "TRUE",
                "compensationStatus", "INVESTIGATING"))).scenario())
                .isEqualTo(ProcedureVersionService.CARD_COMPENSATION_PROCESS_SCENARIO);
        assertThat(service.selectCardBranch(new CardCaseFacts(Map.of(
                "cardLost", "TRUE", "unauthorizedPayment", "TRUE", "reported", "TRUE",
                "compensationStatus", "RESULT_RECEIVED"))).scenario())
                .isEqualTo(ProcedureVersionService.CARD_COMPENSATION_RESULT_SCENARIO);
    }

    @Test
    void selectsOnlyApplicableApprovedVersionAcrossVersionNumbers() {
        ProcedureVersion previous = candidate(1, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 6, 30));
        ProcedureVersion newer = candidate(2, LocalDate.of(2026, 7, 1), null);
        when(repository.findByScenarioAndInstitutionAndProductTypeAndStatus(
                "PERSONAL_INFO_SMISHING_MALICIOUS_APP", "GENERIC_FINANCIAL_INSTITUTION",
                "DIGITAL_FINANCIAL_SERVICE", ProcedureStatus.APPROVED))
                .thenReturn(List.of(newer, previous));

        assertThat(service.selectApplicable("PERSONAL_INFO_SMISHING_MALICIOUS_APP",
                "GENERIC_FINANCIAL_INSTITUTION", "DIGITAL_FINANCIAL_SERVICE",
                LocalDate.of(2026, 5, 1), false).procedure().id()).isEqualTo(previous.getId());
        assertThat(service.selectApplicable("PERSONAL_INFO_SMISHING_MALICIOUS_APP",
                "GENERIC_FINANCIAL_INSTITUTION", "DIGITAL_FINANCIAL_SERVICE",
                LocalDate.of(2026, 8, 1), false).procedure().id()).isEqualTo(newer.getId());
    }

    @Test
    void rejectsMissingDateUnlessSingleUnboundedOptionalCandidate() {
        ProcedureVersion unbounded = candidate(1, null, null);
        when(repository.findByScenarioAndInstitutionAndProductTypeAndStatus(
                "PERSONAL_INFO_SMISHING_MALICIOUS_APP", "GENERIC_FINANCIAL_INSTITUTION",
                "DIGITAL_FINANCIAL_SERVICE", ProcedureStatus.APPROVED))
                .thenReturn(List.of(unbounded));
        assertThat(service.selectApplicable("PERSONAL_INFO_SMISHING_MALICIOUS_APP",
                "GENERIC_FINANCIAL_INSTITUTION", "DIGITAL_FINANCIAL_SERVICE", null, false)
                .procedure().id()).isEqualTo(unbounded.getId());
        assertThat(service.selectApplicable("PERSONAL_INFO_SMISHING_MALICIOUS_APP",
                "GENERIC_FINANCIAL_INSTITUTION", "DIGITAL_FINANCIAL_SERVICE", null, true)
                .failure()).isEqualTo(ProcedureVersionService.SelectionFailure.DATE_REQUIRED);
    }

    @Test
    void rejectsOverlapNoCandidateAndUndatedBoundedCandidate() {
        ProcedureVersion first = candidate(1, null, null);
        ProcedureVersion second = candidate(2, LocalDate.of(2026, 1, 1), null);
        when(repository.findByScenarioAndInstitutionAndProductTypeAndStatus(
                "PERSONAL_INFO_SMISHING_MALICIOUS_APP", "GENERIC_FINANCIAL_INSTITUTION",
                "DIGITAL_FINANCIAL_SERVICE", ProcedureStatus.APPROVED))
                .thenReturn(List.of(first, second));
        assertThat(service.selectApplicable("PERSONAL_INFO_SMISHING_MALICIOUS_APP",
                "GENERIC_FINANCIAL_INSTITUTION", "DIGITAL_FINANCIAL_SERVICE",
                LocalDate.of(2026, 5, 1), false).failure())
                .isEqualTo(ProcedureVersionService.SelectionFailure.OVERLAPPING_APPROVED_VERSIONS);
        assertThat(service.selectApplicable("PERSONAL_INFO_SMISHING_MALICIOUS_APP",
                "GENERIC_FINANCIAL_INSTITUTION", "DIGITAL_FINANCIAL_SERVICE", null, false).failure())
                .isEqualTo(ProcedureVersionService.SelectionFailure.OVERLAPPING_APPROVED_VERSIONS);
        when(repository.findByScenarioAndInstitutionAndProductTypeAndStatus(
                "PERSONAL_INFO_SMISHING_MALICIOUS_APP", "GENERIC_FINANCIAL_INSTITUTION",
                "DIGITAL_FINANCIAL_SERVICE", ProcedureStatus.APPROVED))
                .thenReturn(List.of(second));
        assertThat(service.selectApplicable("PERSONAL_INFO_SMISHING_MALICIOUS_APP",
                "GENERIC_FINANCIAL_INSTITUTION", "DIGITAL_FINANCIAL_SERVICE", null, false).failure())
                .isEqualTo(ProcedureVersionService.SelectionFailure.NO_APPLICABLE_VERSION);
        assertThat(service.selectApplicable("PERSONAL_INFO_SMISHING_MALICIOUS_APP",
                "GENERIC_FINANCIAL_INSTITUTION", "DIGITAL_FINANCIAL_SERVICE",
                LocalDate.of(2025, 1, 1), false).failure())
                .isEqualTo(ProcedureVersionService.SelectionFailure.NO_APPLICABLE_VERSION);
    }

    private ProcedureVersion candidate(int version, LocalDate start, LocalDate end) {
        ProcedureVersion entity = mock(ProcedureVersion.class);
        when(entity.isApproved()).thenReturn(true);
        when(entity.getId()).thenReturn(UUID.randomUUID());
        when(entity.getScenario()).thenReturn("PERSONAL_INFO_SMISHING_MALICIOUS_APP");
        when(entity.getInstitution()).thenReturn("GENERIC_FINANCIAL_INSTITUTION");
        when(entity.getProductType()).thenReturn("DIGITAL_FINANCIAL_SERVICE");
        when(entity.getVersion()).thenReturn(version);
        when(entity.getStatus()).thenReturn(ProcedureStatus.APPROVED);
        when(entity.getApplicabilityStartDate()).thenReturn(start);
        when(entity.getApplicabilityEndDate()).thenReturn(end);
        when(entity.getRequiredFactsJson()).thenReturn("[]");
        when(entity.getConditionRulesJson()).thenReturn("[]");
        when(entity.getActionStepsJson()).thenReturn("[]");
        when(entity.getDocumentRequirementsJson()).thenReturn("[]");
        when(entity.getEvidenceReferencesJson()).thenReturn("[]");
        when(entity.getReviewedContactsJson()).thenReturn("[]");
        when(entity.getReviewedValuesJson()).thenReturn("[]");
        when(entity.getConditionsExceptionsJson()).thenReturn("[]");
        return entity;
    }

    @Test
    void parsesActionScopedReferencesAndExplicitMultipleActions() {
        ProcedureVersionData procedure = parse(scoped(reference("ref-1", "[\"action-a\",\"action-b\"]", "PROCEDURE")));

        assertThat(procedure.evidenceContract()).isEqualTo(ProcedureVersionData.EvidenceContract.ACTION_SCOPED);
        assertThat(procedure.evidenceReferences()).singleElement().satisfies(reference -> {
            assertThat(reference.referenceId()).isEqualTo("ref-1");
            assertThat(reference.actionIds()).containsExactly("action-a", "action-b");
            assertThat(reference.role()).isEqualTo("PROCEDURE");
        });
    }

    @Test
    void allowsMultipleReferencesWithTheSameRequiredRole() {
        ProcedureVersionData procedure = parse(scoped(
                reference("ref-1", "[\"action-a\",\"action-b\"]", "PROCEDURE") + ","
                + reference("ref-2", "[\"action-b\"]", "PROCEDURE")));
        assertThat(procedure.evidenceReferences()).hasSize(2);
    }

    @Test
    void keepsLegacyReferencesExplicitlyUnassigned() {
        ProcedureVersionData procedure = parse("[{\"sourceKey\":\"source\",\"documentVersion\":1,"
                + "\"expectedRawSha256\":\"hash\",\"role\":\"PROCEDURE\"}]");

        assertThat(procedure.evidenceContract()).isEqualTo(ProcedureVersionData.EvidenceContract.LEGACY);
        assertThat(procedure.evidenceReferences()).singleElement().satisfies(reference -> {
            assertThat(reference.isLegacy()).isTrue();
            assertThat(reference.referenceId()).isNull();
            assertThat(reference.actionIds()).isNull();
        });
    }

    @Test
    void rejectsDuplicateReferenceId() {
        assertInvalid(scoped(reference("ref-1", "[\"action-a\",\"action-b\"]", "PROCEDURE") + ","
                + reference("ref-1", "[\"action-b\"]", "PROCEDURE")), "referenceId");
    }

    @Test
    void rejectsMissingOrPartialNewFields() {
        assertInvalid(scoped(reference(null, "[\"action-a\"]", "PROCEDURE")), "referenceId");
        assertInvalid(scoped(reference("ref-1", null, "PROCEDURE")), "actionIds");
    }

    @Test
    void rejectsUnknownAndDuplicateActionIds() {
        assertInvalid(scoped(reference("ref-1", "[\"missing-action\"]", "PROCEDURE")), "actionIds");
        assertInvalid(scoped(reference("ref-1", "[\"action-a\",\"action-a\"]", "PROCEDURE")), "actionIds");
    }

    @Test
    void rejectsNullEmptyWildcardAndWrongTypeActionIds() {
        for (String invalid : List.of("null", "[]", "[\"*\"]", "[null]", "\"action-a\"")) {
            assertInvalid(scoped(reference("ref-1", invalid, "PROCEDURE")), "actionIds");
        }
    }

    @Test
    void rejectsRoleMismatchAndMissingRoleCoverage() {
        assertInvalid(scoped(reference("ref-1", "[\"action-a\",\"action-b\"]", "UNKNOWN_ROLE")), "role");
        assertInvalid(scoped(reference("ref-1", "[\"action-a\",\"action-b\"]", "SAFE_GUIDANCE")), "role");
        assertInvalid(scoped(reference("ref-1", "[\"action-a\"]", "PROCEDURE")), "required evidence role");
    }

    @Test
    void rejectsMixedLegacyAndActionScopedReferences() {
        assertInvalid(scoped("{\"sourceKey\":\"legacy\",\"documentVersion\":1,"
                + "\"expectedRawSha256\":\"hash\",\"role\":\"PROCEDURE\"},"
                + reference("ref-1", "[\"action-a\",\"action-b\"]", "PROCEDURE")
                ), "legacy");
    }

    @Test
    void distinguishesEmptyLegacyAndActionScopedReferences() {
        assertThat(parse("[]").evidenceContract()).isEqualTo(ProcedureVersionData.EvidenceContract.LEGACY);
        ProcedureVersionData scopedEmpty = parse(scoped(""), "[]");
        assertThat(scopedEmpty.evidenceContract()).isEqualTo(ProcedureVersionData.EvidenceContract.ACTION_SCOPED);
        assertThat(scopedEmpty.evidenceReferences()).isEmpty();
        assertInvalid(scoped(""), "required evidence role");
    }

    @Test
    void rejectsInvalidContractEnvelopesWithoutLegacyFallback() {
        assertInvalid("{\"references\":[]}", "contract");
        assertInvalid("{\"contract\":\"LEGACY\",\"references\":[]}", "contract");
        assertInvalid("{\"contract\":\"ACTION_SCOPED\"}", "references");
        assertInvalid("{\"contract\":\"ACTION_SCOPED\",\"references\":null}", "references");
        assertInvalid("{\"contract\":\"ACTION_SCOPED\",\"references\":{}}", "references");
    }

    @Test
    void rejectsActionScopedFieldsInsideLegacyArray() {
        assertInvalid("[" + reference("ref-1", "[\"action-a\",\"action-b\"]", "PROCEDURE") + "]",
                "legacy array");
    }

    private void assertInvalid(String references, String messagePart) {
        assertThatThrownBy(() -> parse(references))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(messagePart);
    }

    private ProcedureVersionData parse(String references) {
        return parse(references, "["
                + "{\"actionId\":\"action-a\",\"order\":1,\"title\":\"A\",\"description\":\"A\","
                + "\"evidenceRoles\":[\"PROCEDURE\"]},"
                + "{\"actionId\":\"action-b\",\"order\":2,\"title\":\"B\",\"description\":\"B\","
                + "\"evidenceRoles\":[\"PROCEDURE\"]}]");
    }

    private ProcedureVersionData parse(String references, String actionSteps) {
        ProcedureVersion entity = mock(ProcedureVersion.class);
        when(entity.isApproved()).thenReturn(true);
        when(entity.getId()).thenReturn(UUID.randomUUID());
        when(entity.getScenario()).thenReturn("PERSONAL_INFO_SMISHING_MALICIOUS_APP");
        when(entity.getInstitution()).thenReturn("GENERIC_FINANCIAL_INSTITUTION");
        when(entity.getProductType()).thenReturn("DIGITAL_FINANCIAL_SERVICE");
        when(entity.getVersion()).thenReturn(2);
        when(entity.getStatus()).thenReturn(ProcedureStatus.APPROVED);
        when(entity.getRequiredFactsJson()).thenReturn("[]");
        when(entity.getConditionRulesJson()).thenReturn("[]");
        when(entity.getActionStepsJson()).thenReturn(actionSteps);
        when(entity.getDocumentRequirementsJson()).thenReturn("[]");
        when(entity.getEvidenceReferencesJson()).thenReturn(references);
        when(entity.getReviewedContactsJson()).thenReturn("[]");
        when(entity.getReviewedValuesJson()).thenReturn("[]");
        when(entity.getConditionsExceptionsJson()).thenReturn("[]");
        return service.toData(entity);
    }

    private String reference(String id, String actionIds, String role) {
        return "{\"sourceKey\":\"source\",\"documentVersion\":1,"
                + "\"expectedRawSha256\":\"hash\",\"role\":\"" + role + "\""
                + (id == null ? "" : ",\"referenceId\":\"" + id + "\"")
                + (actionIds == null ? "" : ",\"actionIds\":" + actionIds) + "}";
    }

    private String scoped(String references) {
        return "{\"contract\":\"ACTION_SCOPED\",\"references\":[" + references + "]}";
    }
}
