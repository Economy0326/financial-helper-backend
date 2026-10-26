package com.financialhelper.procedure;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ProcedureVersionServiceTest {
    private final ProcedureVersionService service = new ProcedureVersionService(
            mock(ProcedureVersionRepository.class), JsonMapper.builder().build());

    @Test
    void parsesActionScopedReferencesAndExplicitMultipleActions() {
        ProcedureVersionData procedure = parse("[" + reference("ref-1", "[\"action-a\",\"action-b\"]", "PROCEDURE") + "]");

        assertThat(procedure.evidenceContract()).isEqualTo(ProcedureVersionData.EvidenceContract.ACTION_SCOPED);
        assertThat(procedure.evidenceReferences()).singleElement().satisfies(reference -> {
            assertThat(reference.referenceId()).isEqualTo("ref-1");
            assertThat(reference.actionIds()).containsExactly("action-a", "action-b");
            assertThat(reference.role()).isEqualTo("PROCEDURE");
        });
    }

    @Test
    void allowsMultipleReferencesWithTheSameRequiredRole() {
        ProcedureVersionData procedure = parse("["
                + reference("ref-1", "[\"action-a\",\"action-b\"]", "PROCEDURE") + ","
                + reference("ref-2", "[\"action-b\"]", "PROCEDURE") + "]");
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
        assertInvalid("[" + reference("ref-1", "[\"action-a\",\"action-b\"]", "PROCEDURE") + ","
                + reference("ref-1", "[\"action-b\"]", "PROCEDURE") + "]", "referenceId");
    }

    @Test
    void rejectsMissingOrPartialNewFields() {
        assertInvalid("[" + reference(null, "[\"action-a\"]", "PROCEDURE") + "]", "referenceId");
        assertInvalid("[" + reference("ref-1", null, "PROCEDURE") + "]", "actionIds");
    }

    @Test
    void rejectsUnknownAndDuplicateActionIds() {
        assertInvalid("[" + reference("ref-1", "[\"missing-action\"]", "PROCEDURE") + "]", "actionIds");
        assertInvalid("[" + reference("ref-1", "[\"action-a\",\"action-a\"]", "PROCEDURE") + "]", "actionIds");
    }

    @Test
    void rejectsNullEmptyWildcardAndWrongTypeActionIds() {
        for (String invalid : List.of("null", "[]", "[\"*\"]", "[null]", "\"action-a\"")) {
            assertInvalid("[" + reference("ref-1", invalid, "PROCEDURE") + "]", "actionIds");
        }
    }

    @Test
    void rejectsRoleMismatchAndMissingRoleCoverage() {
        assertInvalid("[" + reference("ref-1", "[\"action-a\",\"action-b\"]", "UNKNOWN_ROLE")
                + "]", "role");
        assertInvalid("[" + reference("ref-1", "[\"action-a\",\"action-b\"]", "SAFE_GUIDANCE")
                + "]", "role");
        assertInvalid("[" + reference("ref-1", "[\"action-a\"]", "PROCEDURE")
                + "]", "required evidence role");
    }

    @Test
    void rejectsMixedLegacyAndActionScopedReferences() {
        assertInvalid("[{\"sourceKey\":\"legacy\",\"documentVersion\":1,"
                + "\"expectedRawSha256\":\"hash\",\"role\":\"PROCEDURE\"},"
                + reference("ref-1", "[\"action-a\",\"action-b\"]", "PROCEDURE")
                + "]", "legacy");
    }

    private void assertInvalid(String references, String messagePart) {
        assertThatThrownBy(() -> parse(references))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(messagePart);
    }

    private ProcedureVersionData parse(String references) {
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
        when(entity.getActionStepsJson()).thenReturn("["
                + "{\"actionId\":\"action-a\",\"order\":1,\"title\":\"A\",\"description\":\"A\","
                + "\"evidenceRoles\":[\"PROCEDURE\"]},"
                + "{\"actionId\":\"action-b\",\"order\":2,\"title\":\"B\",\"description\":\"B\","
                + "\"evidenceRoles\":[\"PROCEDURE\"]}]");
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
}
