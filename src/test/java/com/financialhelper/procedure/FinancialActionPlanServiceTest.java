package com.financialhelper.procedure;

import com.financialhelper.guest.GuestSessionService;
import com.financialhelper.guest.GuestSession;
import com.financialhelper.consultation.Consultation;
import com.financialhelper.consultation.ConsultationRepository;
import com.financialhelper.retrieval.ConfirmedCaseSnapshotData;
import com.financialhelper.ai.grounded.AnalysisEvidenceSnapshotService;
import com.financialhelper.consultation.ConsultationScenario;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Nested;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;

import com.financialhelper.source.ActiveRetrievalGenerationPersistenceService;
import com.financialhelper.source.RetrievalGeneration;
import com.financialhelper.source.RetrievalGenerationStatus;
import com.financialhelper.source.SourceChunk;
import com.financialhelper.source.SourceChunkIndexing;
import com.financialhelper.source.SourceChunkIndexingRepository;
import com.financialhelper.source.SourceChunkIndexingStatus;
import com.financialhelper.source.SourceChunkRepository;
import com.financialhelper.source.SourceChunkReviewStatus;
import com.financialhelper.source.SourceDocument;
import com.financialhelper.source.SourceDocumentRepository;
import com.financialhelper.source.SourceDocumentStatus;
import com.financialhelper.source.SourceRegistry;
import com.financialhelper.source.SourceRegistryRepository;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.nullable;
import org.mockito.ArgumentCaptor;

class FinancialActionPlanServiceTest {
    @Test
    void actionScopedDoesNotBorrowAnotherActionsReferenceWithTheSameRole() {
        ProcedureVersionData procedure = scopedProcedure(
                List.of(scopedReference("source-a", "ref-a", "PROCEDURE", "action-a"),
                        scopedReference("source-b", "ref-b", "PROCEDURE", "action-b")),
                List.of("PROCEDURE"));
        EvidenceBindingResolver resolver = scopedResolver(Set.of("ref-a"));
        FinancialActionPlanData plan = service(scopedApplicability(), resolver)
                .buildFromSnapshot(snapshot(), procedure, scopedFacts());

        assertThat(plan.status()).isEqualTo(PlanStatus.FAILED);
        assertThat(plan.actions()).isEmpty();
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ProcedureVersionData.EvidenceReference>> references =
                ArgumentCaptor.forClass(List.class);
        verify(resolver).resolve(references.capture(), nullable(LocalDate.class));
        assertThat(references.getValue()).extracting(ProcedureVersionData.EvidenceReference::referenceId)
                .containsExactly("ref-b");
    }

    @Test
    void actionScopedExplicitlySharedReferenceAllowsSelectedAction() {
        ProcedureVersionData procedure = scopedProcedure(
                List.of(scopedReference("shared", "ref-shared", "PROCEDURE",
                        "action-a", "action-b")), List.of("PROCEDURE"));
        FinancialActionPlanData plan = service(scopedApplicability(), scopedResolver(Set.of("ref-shared")))
                .buildFromSnapshot(snapshot(), procedure, scopedFacts());

        assertThat(plan.status()).isEqualTo(PlanStatus.READY);
        assertThat(plan.actions()).extracting(FinancialActionPlanData.Action::actionId)
                .containsExactly("action-b");
        assertThat(plan.evidence()).extracting(FinancialActionPlanData.EvidenceBinding::sourceKey)
                .containsExactly("shared");
    }

    @Test
    void actionScopedMissingOneOfMultipleRolesFailsClosed() {
        ProcedureVersionData procedure = scopedProcedure(
                List.of(scopedReference("source-a", "ref-a", "PROCEDURE", "action-a"),
                        scopedReference("source-b", "ref-b", "PROCEDURE", "action-b"),
                        scopedReference("guidance-b", "ref-guidance-b", "SAFE_GUIDANCE", "action-b")),
                List.of("PROCEDURE", "SAFE_GUIDANCE"));
        FinancialActionPlanData plan = service(scopedApplicability(),
                scopedResolver(Set.of("ref-b")))
                .buildFromSnapshot(snapshot(), procedure, scopedFacts());

        assertThat(plan.status()).isEqualTo(PlanStatus.FAILED);
        assertThat(plan.actions()).isEmpty();
    }

    @Test
    void actionScopedMissingUnselectedActionsSourceDoesNotBlock() {
        ProcedureVersionData procedure = scopedProcedure(
                List.of(scopedReference("source-a", "ref-a", "PROCEDURE", "action-a"),
                        scopedReference("source-b", "ref-b", "PROCEDURE", "action-b")),
                List.of("PROCEDURE"));
        FinancialActionPlanData plan = service(scopedApplicability(), scopedResolver(Set.of("ref-b")))
                .buildFromSnapshot(snapshot(), procedure, scopedFacts());

        assertThat(plan.status()).isEqualTo(PlanStatus.READY);
        assertThat(plan.evidence()).extracting(FinancialActionPlanData.EvidenceBinding::sourceKey)
                .containsExactly("source-b");
    }

    @Test
    void actionScopedEmptyRequiredRolesCannotProduceReadyPlan() {
        ProcedureVersionData procedure = scopedProcedure(
                List.of(scopedReference("source-a", "ref-a", "PROCEDURE", "action-a")),
                List.of());
        FinancialActionPlanData plan = service(scopedApplicability(), scopedResolver(Set.of("ref-a")))
                .buildFromSnapshot(snapshot(), procedure, scopedFacts());

        assertThat(plan.status()).isEqualTo(PlanStatus.FAILED);
        assertThat(plan.actions()).isEmpty();
    }

    @Test
    void actionScopedSelectedActionWithoutReferenceCannotProduceReadyPlan() {
        ProcedureVersionData procedure = scopedProcedure(
                List.of(scopedReference("source-a", "ref-a", "PROCEDURE", "action-a")),
                List.of("PROCEDURE"));
        FinancialActionPlanData plan = service(scopedApplicability(), scopedResolver(Set.of("ref-a")))
                .buildFromSnapshot(snapshot(), procedure, scopedFacts());

        assertThat(plan.status()).isEqualTo(PlanStatus.FAILED);
        assertThat(plan.actions()).isEmpty();
    }

    @Test
    void actionScopedRejectsResolverBindingWithoutMatchingReferenceIdentity() {
        ProcedureVersionData procedure = scopedProcedure(
                List.of(scopedReference("source-b", "ref-b", "PROCEDURE", "action-b")),
                List.of("PROCEDURE"));
        EvidenceBindingResolver resolver = mock(EvidenceBindingResolver.class);
        FinancialActionPlanData.EvidenceBinding unrelated = new FinancialActionPlanData.EvidenceBinding(
                "source-a", UUID.randomUUID(), 1, List.of(UUID.randomUUID()),
                null, null, "PROCEDURE");
        when(resolver.resolve(anyList(), nullable(LocalDate.class)))
                .thenReturn(new EvidenceBindingResolver.Resolution(List.of(unrelated), List.of(),
                        List.of(new EvidenceBindingResolver.ResolvedReference("ref-a", unrelated))));
        FinancialActionPlanData plan = service(scopedApplicability(), resolver)
                .buildFromSnapshot(snapshot(), procedure, scopedFacts());

        assertThat(plan.status()).isEqualTo(PlanStatus.FAILED);
    }

    private ProcedureVersionService scopedApplicability() {
        ProcedureVersionService service = mock(ProcedureVersionService.class);
        when(service.isApplicable(any(), nullable(LocalDate.class))).thenReturn(true);
        return service;
    }

    private CardCaseFacts scopedFacts() {
        return new CardCaseFacts(Map.of("maliciousAppInstalled", "FALSE",
                "suspiciousLinkClicked", "TRUE"));
    }

    private ProcedureVersionData.EvidenceReference scopedReference(
            String sourceKey, String referenceId, String role, String... actionIds
    ) {
        return new ProcedureVersionData.EvidenceReference(sourceKey, 1, "0".repeat(64),
                null, null, role, false, referenceId, List.of(actionIds));
    }

    private ProcedureVersionData scopedProcedure(
            List<ProcedureVersionData.EvidenceReference> references,
            List<String> actionBRoles
    ) {
        return new ProcedureVersionData(
                UUID.randomUUID(), "PERSONAL_INFO_SMISHING_MALICIOUS_APP",
                "GENERIC_FINANCIAL_INSTITUTION", "DIGITAL_FINANCIAL_SERVICE", 2,
                ProcedureStatus.APPROVED, null, null, List.of(),
                List.of(new ProcedureVersionData.ConditionRule("action-a",
                                new ConditionExpression(null, "maliciousAppInstalled",
                                        ConditionOperator.EQ, "TRUE", List.of())),
                        new ProcedureVersionData.ConditionRule("action-b",
                                new ConditionExpression(null, "suspiciousLinkClicked",
                                        ConditionOperator.EQ, "TRUE", List.of()))),
                List.of(new ProcedureVersionData.ActionStep("action-a", 1, "A", "A", null,
                                List.of("PROCEDURE")),
                        new ProcedureVersionData.ActionStep("action-b", 2, "B", "B", null,
                                actionBRoles)),
                List.of(), references, List.of(), List.of(), List.of(), "test", "test", null);
    }

    private EvidenceBindingResolver scopedResolver(Set<String> availableReferenceIds) {
        EvidenceBindingResolver resolver = mock(EvidenceBindingResolver.class);
        when(resolver.resolve(anyList(), nullable(LocalDate.class))).thenAnswer(invocation -> {
            List<ProcedureVersionData.EvidenceReference> references = invocation.getArgument(0);
            List<FinancialActionPlanData.EvidenceBinding> bindings = new ArrayList<>();
            List<EvidenceBindingResolver.ResolvedReference> resolved = new ArrayList<>();
            List<String> gaps = new ArrayList<>();
            for (ProcedureVersionData.EvidenceReference reference : references) {
                if (!availableReferenceIds.contains(reference.referenceId())) {
                    gaps.add("EVIDENCE_SOURCE_UNAVAILABLE:" + reference.sourceKey());
                    continue;
                }
                FinancialActionPlanData.EvidenceBinding binding =
                        new FinancialActionPlanData.EvidenceBinding(reference.sourceKey(),
                                UUID.randomUUID(), reference.documentVersion(), List.of(UUID.randomUUID()),
                                reference.articleReference(), reference.pageReference(), reference.role());
                bindings.add(binding);
                resolved.add(new EvidenceBindingResolver.ResolvedReference(reference.referenceId(), binding));
            }
            return new EvidenceBindingResolver.Resolution(bindings, gaps, resolved);
        });
        return resolver;
    }

    @Test
    void actionScopedStoredReadyPlanRevalidatesItsAssignedReference() throws Exception {
        StoredScopedFixture fixture = storedScopedFixture();
        String storedJson = fixture.mapper().writeValueAsString(Map.of(
                "plan", fixture.plan(), "referenceIds", List.of("ref-b")));

        FinancialActionPlanData result = readStoredScopedPlan(fixture, storedJson);

        assertThat(result.status()).isEqualTo(PlanStatus.READY);
        assertThat(result.actions()).extracting(FinancialActionPlanData.Action::actionId)
                .containsExactly("action-b");
        assertThat(fixture.mapper().writeValueAsString(result)).doesNotContain("referenceIds");
    }

    @Test
    void actionScopedStoredReadyPlanWithoutRelationshipFailsClosed() throws Exception {
        StoredScopedFixture fixture = storedScopedFixture();
        FinancialActionPlanData result = readStoredScopedPlan(fixture,
                fixture.mapper().writeValueAsString(fixture.plan()));

        assertThat(result.status()).isEqualTo(PlanStatus.FAILED);
        assertThat(result.actions()).isEmpty();
    }

    @Test
    void actionScopedStoredReadyPlanCannotUseAnotherActionsReferenceId() throws Exception {
        StoredScopedFixture fixture = storedScopedFixture();
        String storedJson = fixture.mapper().writeValueAsString(Map.of(
                "plan", fixture.plan(), "referenceIds", List.of("ref-a")));
        FinancialActionPlanData result = readStoredScopedPlan(fixture, storedJson);

        assertThat(result.status()).isEqualTo(PlanStatus.FAILED);
        assertThat(result.actions()).isEmpty();
    }

    @Test
    void actionScopedSaveIfAbsentDoesNotReuseReadyPlanWithoutRelationship() throws Exception {
        StoredScopedFixture fixture = storedScopedFixture();
        FinancialActionPlanData result = readStoredScopedPlan(fixture,
                fixture.mapper().writeValueAsString(fixture.plan()), true);

        assertThat(result.status()).isEqualTo(PlanStatus.FAILED);
        assertThat(result.actions()).isEmpty();
    }

    @Test
    void saveIfAbsentDoesNotReusePreviousProcedureVersionReadyPlan() throws Exception {
        StoredScopedFixture fixture = storedScopedFixture();
        String storedJson = fixture.mapper().writeValueAsString(Map.of(
                "plan", fixture.plan(), "referenceIds", List.of("ref-b")));

        FinancialActionPlanData result = readStoredScopedPlan(fixture, storedJson, true, true);
        assertThat(result.status()).isEqualTo(PlanStatus.FAILED);
        assertThat(result.actions()).isEmpty();
        assertThat(result.coverageGaps()).contains("PROCEDURE_VERSION_MISMATCH");
    }

    @Test
    void actionScopedStoredReadyPlanRejectsDuplicateReferenceIds() throws Exception {
        StoredScopedFixture fixture = storedScopedFixture();
        String storedJson = fixture.mapper().writeValueAsString(Map.of(
                "plan", fixture.plan(), "referenceIds", List.of("ref-b", "ref-b")));

        assertThat(readStoredScopedPlan(fixture, storedJson).status()).isEqualTo(PlanStatus.FAILED);
    }

    @Test
    void actionScopedStoredReadyPlanRejectsSameCountReferenceOrderSwap() throws Exception {
        StoredScopedFixture fixture = storedScopedTwoReferenceFixture();
        String swapped = fixture.mapper().writeValueAsString(Map.of(
                "plan", fixture.plan(), "referenceIds", List.of("ref-b-2", "ref-b")));

        FinancialActionPlanData result = readStoredScopedPlan(fixture, swapped);
        assertThat(result.status()).isEqualTo(PlanStatus.FAILED);
        assertThat(result.actions()).isEmpty();
    }

    @Test
    void actionScopedStoredReadyPlanRejectsSameRoleEvidenceReplacement() throws Exception {
        StoredScopedFixture fixture = storedScopedFixture();
        FinancialActionPlanData.EvidenceBinding replacement =
                new FinancialActionPlanData.EvidenceBinding("other-source", UUID.randomUUID(), 1,
                        List.of(UUID.randomUUID()), null, null, "PROCEDURE");
        FinancialActionPlanData original = fixture.plan();
        FinancialActionPlanData tampered = new FinancialActionPlanData(original.id(),
                original.consultationId(), original.confirmedCaseSnapshotId(),
                original.procedureVersionId(), original.scenario(), original.procedureVersion(),
                original.caseInputRevision(), original.followUpAnswerRevision(), original.status(),
                original.actions(), original.requiredDocuments(), List.of(replacement),
                original.unresolvedFacts(), original.coverageGaps(), original.warnings());
        String storedJson = fixture.mapper().writeValueAsString(Map.of(
                "plan", tampered, "referenceIds", List.of("ref-b")));

        FinancialActionPlanData result = readStoredScopedPlan(fixture, storedJson);
        assertThat(result.status()).isEqualTo(PlanStatus.FAILED);
        assertThat(result.actions()).isEmpty();
    }

    @Test
    void actionScopedBuildStoresRelationshipOutsidePublicPlanJsonShape() throws Exception {
        StoredScopedFixture fixture = storedScopedFixture();
        ConsultationRepository consultations = mock(ConsultationRepository.class);
        com.financialhelper.retrieval.ConfirmedCaseSnapshotService snapshots =
                mock(com.financialhelper.retrieval.ConfirmedCaseSnapshotService.class);
        com.financialhelper.retrieval.ConfirmedCaseSnapshotRepository snapshotRows =
                mock(com.financialhelper.retrieval.ConfirmedCaseSnapshotRepository.class);
        ProcedureVersionRepository versions = mock(ProcedureVersionRepository.class);
        ProcedureVersionService procedures = scopedApplicability();
        FinancialActionPlanRepository plans = mock(FinancialActionPlanRepository.class);
        EvidenceBindingResolver resolver = mock(EvidenceBindingResolver.class);
        Consultation consultation = mock(Consultation.class);
        ProcedureVersion version = mock(ProcedureVersion.class);
        com.financialhelper.retrieval.ConfirmedCaseSnapshot snapshotRow =
                mock(com.financialhelper.retrieval.ConfirmedCaseSnapshot.class);
        UUID consultationId = fixture.plan().consultationId();
        UUID snapshotId = fixture.plan().confirmedCaseSnapshotId();
        List<ConfirmedCaseSnapshotData.Fact> facts = List.of(
                new ConfirmedCaseSnapshotData.Fact("FOLLOW_UP", "maliciousAppInstalled",
                        "FALSE", "A", "USER", null),
                new ConfirmedCaseSnapshotData.Fact("FOLLOW_UP", "suspiciousLinkClicked",
                        "TRUE", "B", "USER", null));
        ConfirmedCaseSnapshotData snapshotData = new ConfirmedCaseSnapshotData(
                snapshotId, consultationId, 2, 1, facts, List.of(), null);
        when(consultations.findById(consultationId)).thenReturn(Optional.of(consultation));
        when(consultation.getId()).thenReturn(consultationId);
        when(consultation.getScenario()).thenReturn(
                ConsultationScenario.PERSONAL_INFO_SMISHING_MALICIOUS_APP);
        when(consultation.getCaseInputRevision()).thenReturn(2L);
        when(consultation.getFollowUpAnswerRevision()).thenReturn(1L);
        when(snapshots.capture(consultationId)).thenReturn(snapshotData);
        when(snapshotRows.findById(snapshotId)).thenReturn(Optional.of(snapshotRow));
        when(snapshotRow.getCaseInputRevision()).thenReturn(2L);
        when(snapshotRow.getFollowUpAnswerRevision()).thenReturn(1L);
        when(snapshotRow.getFactsJson()).thenReturn(fixture.mapper().writeValueAsString(facts));
        when(procedures.requireApproved(fixture.procedure().scenario(),
                fixture.procedure().institution(), fixture.procedure().productType()))
                .thenReturn(fixture.procedure());
        when(procedures.selectApplicable(fixture.procedure().scenario(),
                fixture.procedure().institution(), fixture.procedure().productType(), null, false))
                .thenReturn(new ProcedureVersionService.Selection(fixture.procedure(), null));
        when(version.getId()).thenReturn(fixture.procedure().id());
        when(versions.findById(fixture.procedure().id())).thenReturn(Optional.of(version));
        when(plans.findByConsultation_IdAndProcedureVersion_IdAndCaseInputRevisionAndFollowUpAnswerRevision(
                consultationId, fixture.procedure().id(), 2, 1)).thenReturn(Optional.empty());
        when(plans.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(resolver.resolve(anyList(), nullable(LocalDate.class)))
                .thenReturn(new EvidenceBindingResolver.Resolution(
                        List.of(fixture.binding()), List.of(),
                        List.of(new EvidenceBindingResolver.ResolvedReference(
                                "ref-b", fixture.binding()))));
        FinancialActionPlanService service = new FinancialActionPlanService(
                consultations, mock(GuestSessionService.class), snapshots, snapshotRows,
                versions, procedures, resolver, plans, fixture.mapper());

        FinancialActionPlanData result = service.buildForCurrent(consultationId);
        org.mockito.ArgumentCaptor<FinancialActionPlan> saved =
                org.mockito.ArgumentCaptor.forClass(FinancialActionPlan.class);
        verify(plans, times(1)).saveAndFlush(saved.capture());
        assertThat(result.status()).isEqualTo(PlanStatus.READY);
        assertThat(saved.getValue().getPlanJson()).contains("\"referenceIds\":[\"ref-b\"]");
        assertThat(fixture.mapper().writeValueAsString(result)).doesNotContain("referenceIds");
    }

    private StoredScopedFixture storedScopedFixture() {
        ProcedureVersionData procedure = scopedProcedure(
                List.of(scopedReference("source-a", "ref-a", "PROCEDURE", "action-a"),
                        scopedReference("source-b", "ref-b", "PROCEDURE", "action-b")),
                List.of("PROCEDURE"));
        FinancialActionPlanData.EvidenceBinding binding = new FinancialActionPlanData.EvidenceBinding(
                "source-b", UUID.randomUUID(), 1, List.of(UUID.randomUUID()),
                null, null, "PROCEDURE");
        FinancialActionPlanData stored = new FinancialActionPlanData(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), procedure.id(),
                procedure.scenario(), procedure.version(), 2, 1, PlanStatus.READY,
                List.of(new FinancialActionPlanData.Action("action-b", 2, "B", "B",
                        ConditionResult.TRUE, null)),
                List.of(), List.of(binding), List.of(), List.of(), List.of());
        return new StoredScopedFixture(procedure, stored, binding, null,
                tools.jackson.databind.json.JsonMapper.builder().build());
    }

    private StoredScopedFixture storedScopedTwoReferenceFixture() {
        StoredScopedFixture single = storedScopedFixture();
        ProcedureVersionData p = single.procedure();
        ProcedureVersionData.EvidenceReference second = scopedReference(
                "source-b-2", "ref-b-2", "PROCEDURE", "action-b");
        ProcedureVersionData procedure = new ProcedureVersionData(p.id(), p.scenario(),
                p.institution(), p.productType(), p.version(), p.status(),
                p.applicabilityStartDate(), p.applicabilityEndDate(), p.requiredFacts(),
                p.conditionRules(), p.actionSteps(), p.documentRequirements(),
                List.of(p.evidenceReferences().get(0), p.evidenceReferences().get(1), second),
                p.reviewedContacts(), p.reviewedValues(), p.conditionsExceptions(),
                p.reviewNotes(), p.reviewedBy(), p.reviewedAt(), p.evidenceContract());
        FinancialActionPlanData.EvidenceBinding additional = new FinancialActionPlanData.EvidenceBinding(
                "source-b-2", UUID.randomUUID(), 1, List.of(UUID.randomUUID()),
                null, null, "PROCEDURE");
        FinancialActionPlanData old = single.plan();
        FinancialActionPlanData plan = new FinancialActionPlanData(old.id(), old.consultationId(),
                old.confirmedCaseSnapshotId(), old.procedureVersionId(), old.scenario(),
                old.procedureVersion(), old.caseInputRevision(), old.followUpAnswerRevision(),
                old.status(), old.actions(), old.requiredDocuments(),
                List.of(single.binding(), additional), old.unresolvedFacts(),
                old.coverageGaps(), old.warnings());
        return new StoredScopedFixture(procedure, plan, single.binding(), additional, single.mapper());
    }

    private FinancialActionPlanData readStoredScopedPlan(
            StoredScopedFixture fixture, String storedJson
    ) {
        return readStoredScopedPlan(fixture, storedJson, false);
    }

    private FinancialActionPlanData readStoredScopedPlan(
            StoredScopedFixture fixture, String storedJson, boolean throughSaveIfAbsent
    ) {
        return readStoredScopedPlan(fixture, storedJson, throughSaveIfAbsent, false);
    }

    private FinancialActionPlanData readStoredScopedPlan(
            StoredScopedFixture fixture, String storedJson, boolean throughSaveIfAbsent,
            boolean differentCurrentVersion
    ) {
        ConsultationRepository consultations = mock(ConsultationRepository.class);
        GuestSessionService sessions = mock(GuestSessionService.class);
        FinancialActionPlanRepository plans = mock(FinancialActionPlanRepository.class);
        ProcedureVersionService procedures = mock(ProcedureVersionService.class);
        EvidenceBindingResolver resolver = mock(EvidenceBindingResolver.class);
        Consultation consultation = mock(Consultation.class);
        GuestSession guest = mock(GuestSession.class);
        FinancialActionPlan saved = mock(FinancialActionPlan.class);
        ProcedureVersion version = mock(ProcedureVersion.class);
        com.financialhelper.retrieval.ConfirmedCaseSnapshot snapshot =
                mock(com.financialhelper.retrieval.ConfirmedCaseSnapshot.class);
        UUID guestId = UUID.randomUUID();
        when(sessions.requireValidSession("token")).thenReturn(guest);
        when(guest.getId()).thenReturn(guestId);
        when(consultations.findByIdAndGuestSession_Id(fixture.plan().consultationId(), guestId))
                .thenReturn(Optional.of(consultation));
        when(consultation.getId()).thenReturn(fixture.plan().consultationId());
        when(consultation.getScenario()).thenReturn(
                ConsultationScenario.PERSONAL_INFO_SMISHING_MALICIOUS_APP);
        when(consultation.getCaseInputRevision()).thenReturn(2L);
        when(consultation.getFollowUpAnswerRevision()).thenReturn(1L);
        when(plans.findTopByConsultation_IdOrderByCaseInputRevisionDescFollowUpAnswerRevisionDesc(
                fixture.plan().consultationId())).thenReturn(Optional.of(saved));
        when(saved.getId()).thenReturn(fixture.plan().id());
        when(saved.getCaseInputRevision()).thenReturn(2L);
        when(saved.getFollowUpAnswerRevision()).thenReturn(1L);
        when(saved.getPlanJson()).thenReturn(storedJson);
        when(saved.getPlanStatus()).thenReturn(PlanStatus.READY);
        when(saved.getProcedureVersion()).thenReturn(version);
        when(saved.getConfirmedCaseSnapshot()).thenReturn(snapshot);
        when(snapshot.getId()).thenReturn(fixture.plan().confirmedCaseSnapshotId());
        when(snapshot.getConsultation()).thenReturn(consultation);
        when(snapshot.getFactsJson()).thenReturn(fixture.mapper().writeValueAsString(List.of(
                new ConfirmedCaseSnapshotData.Fact("FOLLOW_UP", "maliciousAppInstalled",
                        "FALSE", "A", "USER", null),
                new ConfirmedCaseSnapshotData.Fact("FOLLOW_UP", "suspiciousLinkClicked",
                        "TRUE", "B", "USER", null))));
        when(snapshot.getCaseInputRevision()).thenReturn(2L);
        when(snapshot.getFollowUpAnswerRevision()).thenReturn(1L);
        when(version.getId()).thenReturn(fixture.procedure().id());
        when(procedures.toData(version)).thenReturn(fixture.procedure());
        ProcedureVersionData currentProcedure = differentCurrentVersion
                ? new ProcedureVersionData(UUID.randomUUID(), fixture.procedure().scenario(),
                        fixture.procedure().institution(), fixture.procedure().productType(), 3,
                        fixture.procedure().status(), fixture.procedure().applicabilityStartDate(),
                        fixture.procedure().applicabilityEndDate(), fixture.procedure().requiredFacts(),
                        fixture.procedure().conditionRules(), fixture.procedure().actionSteps(),
                        fixture.procedure().documentRequirements(), fixture.procedure().evidenceReferences(),
                        fixture.procedure().reviewedContacts(), fixture.procedure().reviewedValues(),
                        fixture.procedure().conditionsExceptions(), fixture.procedure().reviewNotes(),
                        fixture.procedure().reviewedBy(), fixture.procedure().reviewedAt(),
                        fixture.procedure().evidenceContract()) : fixture.procedure();
        when(procedures.selectApplicable(fixture.procedure().scenario(),
                fixture.procedure().institution(), fixture.procedure().productType(), null, false))
                .thenReturn(new ProcedureVersionService.Selection(currentProcedure, null));
        List<FinancialActionPlanData.EvidenceBinding> currentBindings = fixture.additionalBinding() == null
                ? List.of(fixture.binding()) : List.of(fixture.binding(), fixture.additionalBinding());
        List<EvidenceBindingResolver.ResolvedReference> currentReferences = fixture.additionalBinding() == null
                ? List.of(new EvidenceBindingResolver.ResolvedReference("ref-b", fixture.binding()))
                : List.of(new EvidenceBindingResolver.ResolvedReference("ref-b", fixture.binding()),
                        new EvidenceBindingResolver.ResolvedReference("ref-b-2", fixture.additionalBinding()));
        when(resolver.resolve(anyList(), nullable(LocalDate.class)))
                .thenReturn(new EvidenceBindingResolver.Resolution(
                        currentBindings, List.of(), currentReferences));
        FinancialActionPlanService service = new FinancialActionPlanService(
                consultations, sessions,
                mock(com.financialhelper.retrieval.ConfirmedCaseSnapshotService.class),
                mock(com.financialhelper.retrieval.ConfirmedCaseSnapshotRepository.class),
                mock(ProcedureVersionRepository.class), procedures, resolver, plans, fixture.mapper());
        if (throughSaveIfAbsent) {
            when(plans.findByConsultation_IdAndProcedureVersion_IdAndCaseInputRevisionAndFollowUpAnswerRevision(
                    fixture.plan().consultationId(), fixture.procedure().id(), 2, 1))
                    .thenReturn(Optional.of(saved));
            return ReflectionTestUtils.invokeMethod(service, "saveIfAbsent",
                    consultation, version, fixture.procedure(), snapshot, fixture.plan(), List.of());
        }
        return service.getLatest(fixture.plan().consultationId(), "token");
    }

    private record StoredScopedFixture(
            ProcedureVersionData procedure,
            FinancialActionPlanData plan,
            FinancialActionPlanData.EvidenceBinding binding,
            FinancialActionPlanData.EvidenceBinding additionalBinding,
            tools.jackson.databind.json.JsonMapper mapper
    ) {
    }

    @Test
    void characterizationProcedureRoleFromActionASourceAlsoAllowsActionB() {
        // Characterization only: the current model has no Action-to-Evidence assignment.
        // The source name describes intended use for A, but the runtime cannot enforce it.
        ProcedureVersionData.EvidenceReference sourceForActionA =
                new ProcedureVersionData.EvidenceReference(
                        "synthetic-source-intended-for-action-a", 1, "0".repeat(64),
                        null, null, "PROCEDURE");
        ProcedureVersionData procedure = new ProcedureVersionData(
                UUID.randomUUID(), "PERSONAL_INFO_SMISHING_MALICIOUS_APP",
                "GENERIC_FINANCIAL_INSTITUTION", "DIGITAL_FINANCIAL_SERVICE", 1,
                ProcedureStatus.APPROVED, null, null, List.of(),
                List.of(
                        new ProcedureVersionData.ConditionRule("action-a",
                                new ConditionExpression(null, "actionAEligible",
                                        ConditionOperator.EQ, "TRUE", List.of())),
                        new ProcedureVersionData.ConditionRule("action-b",
                                new ConditionExpression(null, "actionBEligible",
                                        ConditionOperator.EQ, "TRUE", List.of()))),
                List.of(
                        new ProcedureVersionData.ActionStep("action-a", 1, "A", "A", null,
                                List.of("PROCEDURE")),
                        new ProcedureVersionData.ActionStep("action-b", 2, "B", "B", null,
                                List.of("PROCEDURE"))),
                List.of(), List.of(sourceForActionA), List.of(), List.of(), List.of(),
                "synthetic characterization", "test", null);
        FinancialActionPlanData.EvidenceBinding bindingForActionA =
                new FinancialActionPlanData.EvidenceBinding(
                        sourceForActionA.sourceKey(), UUID.randomUUID(), 1,
                        List.of(UUID.randomUUID()), null, null, "PROCEDURE");
        ProcedureVersionService procedureService = mock(ProcedureVersionService.class);
        EvidenceBindingResolver resolver = mock(EvidenceBindingResolver.class);
        when(procedureService.isApplicable(any(), nullable(LocalDate.class))).thenReturn(true);
        when(resolver.resolve(anyList(), nullable(LocalDate.class)))
                .thenReturn(new EvidenceBindingResolver.Resolution(List.of(bindingForActionA), List.of()));

        FinancialActionPlanData plan = service(procedureService, resolver).buildFromSnapshot(
                snapshot(), procedure, new CardCaseFacts(Map.of(
                        "actionAEligible", "FALSE", "actionBEligible", "TRUE")));

        assertThat(plan.status()).isEqualTo(PlanStatus.READY);
        assertThat(plan.actions()).extracting(FinancialActionPlanData.Action::actionId)
                .containsExactly("action-b");
        assertThat(plan.evidence()).extracting(FinancialActionPlanData.EvidenceBinding::sourceKey)
                .containsExactly(sourceForActionA.sourceKey());
    }

    @Test
    void accountLawDateSelectorUsesTransactionDateRegardlessOfFactOrder() {
        AnalysisEvidenceSnapshotService snapshots = new AnalysisEvidenceSnapshotService(
                null, null, null, null, null, null, null, null, null, null, null, null,
                mock(org.springframework.transaction.PlatformTransactionManager.class));
        ConfirmedCaseSnapshotData.Fact transaction = new ConfirmedCaseSnapshotData.Fact(
                "FOLLOW_UP", "transactionDate", "2026-04-12", "거래일", "USER", null);
        ConfirmedCaseSnapshotData.Fact incident = new ConfirmedCaseSnapshotData.Fact(
                "FOLLOW_UP", "incidentDate", "2026-05-20", "발생일", "USER", null);

        LocalDate transactionOnly = ReflectionTestUtils.invokeMethod(snapshots, "incidentDate",
                new ConfirmedCaseSnapshotData(UUID.randomUUID(), UUID.randomUUID(), 2, 1,
                        List.of(transaction), List.of(), null),
                ConsultationScenario.UNAUTHORIZED_ACCOUNT_TRANSFER);
        LocalDate incidentFirst = ReflectionTestUtils.invokeMethod(snapshots, "incidentDate",
                new ConfirmedCaseSnapshotData(UUID.randomUUID(), UUID.randomUUID(), 2, 1,
                        List.of(incident, transaction), List.of(), null),
                ConsultationScenario.UNAUTHORIZED_ACCOUNT_TRANSFER);
        LocalDate transactionFirst = ReflectionTestUtils.invokeMethod(snapshots, "incidentDate",
                new ConfirmedCaseSnapshotData(UUID.randomUUID(), UUID.randomUUID(), 2, 1,
                        List.of(transaction, incident), List.of(), null),
                ConsultationScenario.UNAUTHORIZED_ACCOUNT_TRANSFER);
        LocalDate incidentOnly = ReflectionTestUtils.invokeMethod(snapshots, "incidentDate",
                new ConfirmedCaseSnapshotData(UUID.randomUUID(), UUID.randomUUID(), 2, 1,
                        List.of(incident), List.of(), null),
                ConsultationScenario.UNAUTHORIZED_ACCOUNT_TRANSFER);

        assertThat(transactionOnly).isEqualTo(LocalDate.parse("2026-04-12"));
        assertThat(incidentFirst).isEqualTo(LocalDate.parse("2026-04-12"));
        assertThat(transactionFirst).isEqualTo(LocalDate.parse("2026-04-12"));
        assertThat(incidentOnly).isNull();
        LocalDate voiceDate = ReflectionTestUtils.invokeMethod(snapshots, "incidentDate",
                new ConfirmedCaseSnapshotData(UUID.randomUUID(), UUID.randomUUID(), 2, 1,
                        List.of(transaction, incident), List.of(), null),
                ConsultationScenario.VOICE_PHISHING_SUSPICIOUS_TRANSFER);
        assertThat(voiceDate).isEqualTo(LocalDate.parse("2026-05-20"));
    }

    @Test
    void sourceOutsideTransactionDateIsRejectedOnlyWhenResolverReceivesTheDate() {
        LocalDate transactionDate = LocalDate.parse("2026-04-12");
        ProcedureVersionData.EvidenceReference reference = new ProcedureVersionData.EvidenceReference(
                "synthetic-account-source", 1, "synthetic-hash", null, null, "PROCEDURE", true);
        SourceRegistryRepository registries = mock(SourceRegistryRepository.class);
        SourceDocumentRepository documents = mock(SourceDocumentRepository.class);
        SourceChunkRepository chunks = mock(SourceChunkRepository.class);
        SourceChunkIndexingRepository indexings = mock(SourceChunkIndexingRepository.class);
        ActiveRetrievalGenerationPersistenceService generations =
                mock(ActiveRetrievalGenerationPersistenceService.class);
        RetrievalGeneration generation = mock(RetrievalGeneration.class);
        SourceRegistry registry = mock(SourceRegistry.class);
        SourceDocument document = mock(SourceDocument.class);
        SourceChunk chunk = mock(SourceChunk.class);
        SourceChunkIndexing indexing = mock(SourceChunkIndexing.class);
        UUID generationId = UUID.randomUUID();
        UUID registryId = UUID.randomUUID();
        UUID documentId = UUID.randomUUID();
        UUID chunkId = UUID.randomUUID();
        when(generations.current()).thenReturn(Optional.of(generation));
        when(generation.getStatus()).thenReturn(RetrievalGenerationStatus.READY);
        when(generation.getId()).thenReturn(generationId);
        when(registries.findBySourceKeyAndEnabledTrue(reference.sourceKey()))
                .thenReturn(Optional.of(registry));
        when(registry.getId()).thenReturn(registryId);
        when(documents.findBySourceRegistry_IdAndStatus(registryId, SourceDocumentStatus.ACTIVE))
                .thenReturn(Optional.of(document));
        when(document.getId()).thenReturn(documentId);
        when(document.getDocumentVersion()).thenReturn(1);
        when(document.getRawSha256()).thenReturn("synthetic-hash");
        when(document.getApplicabilityStartDate()).thenReturn(LocalDate.parse("2026-01-01"));
        when(document.getApplicabilityEndDate()).thenReturn(LocalDate.parse("2026-03-31"));
        when(chunks.findAllBySourceDocument_IdOrderBySequenceAsc(documentId)).thenReturn(List.of(chunk));
        when(chunk.getId()).thenReturn(chunkId);
        when(chunk.getReviewStatus()).thenReturn(SourceChunkReviewStatus.APPROVED);
        when(indexings.findBySourceChunk_IdAndRetrievalGeneration_Id(chunkId, generationId))
                .thenReturn(Optional.of(indexing));
        when(indexing.getIndexingStatus()).thenReturn(SourceChunkIndexingStatus.READY);
        EvidenceBindingResolver resolver = new EvidenceBindingResolver(
                registries, documents, chunks, indexings, generations);

        EvidenceBindingResolver.Resolution withDate = resolver.resolve(List.of(reference), transactionDate);
        EvidenceBindingResolver.Resolution withoutDate = resolver.resolve(List.of(reference), null);

        assertThat(withDate.bindings()).isEmpty();
        assertThat(withDate.coverageGaps()).contains("EVIDENCE_NOT_APPLICABLE:synthetic-account-source");
        assertThat(withoutDate.bindings())
                .as("the resolver alone cannot enforce a date omitted by its caller")
                .hasSize(1);
    }

    @Test
    void previouslySavedReadyPlanWithMissingActionRoleIsNotReturnedAsReady() throws Exception {
        ConsultationRepository consultations = mock(ConsultationRepository.class);
        GuestSessionService sessions = mock(GuestSessionService.class);
        FinancialActionPlanRepository plans = mock(FinancialActionPlanRepository.class);
        ProcedureVersionService procedures = mock(ProcedureVersionService.class);
        Consultation consultation = mock(Consultation.class);
        GuestSession guest = mock(GuestSession.class);
        FinancialActionPlan saved = mock(FinancialActionPlan.class);
        ProcedureVersion version = mock(ProcedureVersion.class);
        com.financialhelper.retrieval.ConfirmedCaseSnapshot savedSnapshot =
                mock(com.financialhelper.retrieval.ConfirmedCaseSnapshot.class);
        UUID consultationId = UUID.randomUUID();
        UUID guestId = UUID.randomUUID();
        UUID planId = UUID.randomUUID();
        tools.jackson.databind.json.JsonMapper mapper = tools.jackson.databind.json.JsonMapper.builder().build();
        ProcedureVersionData procedure = cardBranchProcedure(ProcedureVersionService.CARD_HELD_UNAUTHORIZED_SCENARIO);
        FinancialActionPlanData oldReady = new FinancialActionPlanData(
                planId, consultationId, UUID.randomUUID(), procedure.id(), procedure.scenario(),
                procedure.version(), 2, 1, PlanStatus.READY,
                List.of(new FinancialActionPlanData.Action("confirm-held-card-payment", 1,
                        "미인지 신용판매 확인", "공식 접수 대상인지 확인합니다.",
                        ConditionResult.TRUE, "kb-card-compensation-process")),
                List.of(), List.of(), List.of(), List.of(), List.of());
        when(sessions.requireValidSession("token")).thenReturn(guest);
        when(guest.getId()).thenReturn(guestId);
        when(consultations.findByIdAndGuestSession_Id(consultationId, guestId))
                .thenReturn(Optional.of(consultation));
        when(consultation.getId()).thenReturn(consultationId);
        when(consultation.getScenario()).thenReturn(ConsultationScenario.CARD_LOSS_UNAUTHORIZED_USE);
        when(consultation.getCaseInputRevision()).thenReturn(2L);
        when(consultation.getFollowUpAnswerRevision()).thenReturn(1L);
        when(plans.findTopByConsultation_IdOrderByCaseInputRevisionDescFollowUpAnswerRevisionDesc(consultationId))
                .thenReturn(Optional.of(saved));
        when(saved.getCaseInputRevision()).thenReturn(2L);
        when(saved.getFollowUpAnswerRevision()).thenReturn(1L);
        when(saved.getPlanJson()).thenReturn(mapper.writeValueAsString(oldReady));
        when(saved.getId()).thenReturn(planId);
        when(saved.getProcedureVersion()).thenReturn(version);
        when(saved.getPlanStatus()).thenReturn(PlanStatus.READY);
        when(saved.getConfirmedCaseSnapshot()).thenReturn(savedSnapshot);
        when(savedSnapshot.getId()).thenReturn(oldReady.confirmedCaseSnapshotId());
        when(savedSnapshot.getConsultation()).thenReturn(consultation);
        when(savedSnapshot.getCaseInputRevision()).thenReturn(2L);
        when(savedSnapshot.getFollowUpAnswerRevision()).thenReturn(1L);
        when(savedSnapshot.getFactsJson()).thenReturn(mapper.writeValueAsString(List.of(
                new ConfirmedCaseSnapshotData.Fact("FOLLOW_UP", "institution",
                        ProcedureVersionService.KB_INSTITUTION, "I", "USER", null),
                new ConfirmedCaseSnapshotData.Fact("FOLLOW_UP", "productType",
                        ProcedureVersionService.PERSONAL_CREDIT_CARD, "P", "USER", null),
                new ConfirmedCaseSnapshotData.Fact("FOLLOW_UP", "cardLost", "FALSE", "A", "USER", null),
                new ConfirmedCaseSnapshotData.Fact("FOLLOW_UP", "unauthorizedPayment", "TRUE", "B", "USER", null),
                new ConfirmedCaseSnapshotData.Fact("FOLLOW_UP", "transactionType",
                        "CREDIT_SALE", "T", "USER", null),
                new ConfirmedCaseSnapshotData.Fact("FOLLOW_UP", "domestic", "TRUE", "D", "USER", null),
                new ConfirmedCaseSnapshotData.Fact("FOLLOW_UP", "incidentDate", "2026-05-20", "C", "USER", null))));
        when(version.getId()).thenReturn(procedure.id());
        when(procedures.toData(version)).thenReturn(procedure);
        when(procedures.selectApplicableCard(any(),
                org.mockito.ArgumentMatchers.eq(LocalDate.of(2026, 5, 20))))
                .thenReturn(new ProcedureVersionService.Selection(procedure, null));

        FinancialActionPlanService service = new FinancialActionPlanService(
                consultations, sessions, mock(com.financialhelper.retrieval.ConfirmedCaseSnapshotService.class),
                mock(com.financialhelper.retrieval.ConfirmedCaseSnapshotRepository.class),
                mock(ProcedureVersionRepository.class), procedures, mock(EvidenceBindingResolver.class),
                plans, mapper);
        FinancialActionPlanData result = service.getLatest(consultationId, "token");

        assertThat(result.status()).isEqualTo(PlanStatus.FAILED);
        assertThat(result.actions()).isEmpty();
        assertThat(result.coverageGaps()).contains("ACTION_EVIDENCE_ROLE_UNAVAILABLE");
    }

    @Test
    void previouslySavedReadyAccountPlanWithoutTransactionDateIsNotReturnedAsReady() throws Exception {
        ConsultationRepository consultations = mock(ConsultationRepository.class);
        GuestSessionService sessions = mock(GuestSessionService.class);
        FinancialActionPlanRepository plans = mock(FinancialActionPlanRepository.class);
        Consultation consultation = mock(Consultation.class);
        GuestSession guest = mock(GuestSession.class);
        FinancialActionPlan saved = mock(FinancialActionPlan.class);
        com.financialhelper.retrieval.ConfirmedCaseSnapshot savedSnapshot =
                mock(com.financialhelper.retrieval.ConfirmedCaseSnapshot.class);
        UUID consultationId = UUID.randomUUID();
        UUID guestId = UUID.randomUUID();
        UUID planId = UUID.randomUUID();
        UUID snapshotId = UUID.randomUUID();
        UUID procedureId = UUID.randomUUID();
        tools.jackson.databind.json.JsonMapper mapper = tools.jackson.databind.json.JsonMapper.builder().build();
        FinancialActionPlanData oldReady = new FinancialActionPlanData(
                planId, consultationId, snapshotId, procedureId,
                "UNAUTHORIZED_ACCOUNT_TRANSFER", 1, 2, 1, PlanStatus.READY,
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
        when(sessions.requireValidSession("token")).thenReturn(guest);
        when(guest.getId()).thenReturn(guestId);
        when(consultations.findByIdAndGuestSession_Id(consultationId, guestId))
                .thenReturn(Optional.of(consultation));
        when(consultation.getId()).thenReturn(consultationId);
        when(consultation.getScenario()).thenReturn(
                ConsultationScenario.UNAUTHORIZED_ACCOUNT_TRANSFER);
        when(consultation.getCaseInputRevision()).thenReturn(2L);
        when(consultation.getFollowUpAnswerRevision()).thenReturn(1L);
        when(plans.findTopByConsultation_IdOrderByCaseInputRevisionDescFollowUpAnswerRevisionDesc(consultationId))
                .thenReturn(Optional.of(saved));
        when(saved.getCaseInputRevision()).thenReturn(2L);
        when(saved.getFollowUpAnswerRevision()).thenReturn(1L);
        when(saved.getPlanJson()).thenReturn(mapper.writeValueAsString(oldReady));
        when(saved.getId()).thenReturn(planId);
        when(saved.getPlanStatus()).thenReturn(PlanStatus.READY);
        when(saved.getConfirmedCaseSnapshot()).thenReturn(savedSnapshot);
        when(savedSnapshot.getId()).thenReturn(snapshotId);
        when(savedSnapshot.getConsultation()).thenReturn(consultation);
        when(savedSnapshot.getCaseInputRevision()).thenReturn(2L);
        when(savedSnapshot.getFollowUpAnswerRevision()).thenReturn(1L);
        when(savedSnapshot.getFactsJson()).thenReturn(mapper.writeValueAsString(List.of(
                new ConfirmedCaseSnapshotData.Fact("FOLLOW_UP", "incidentDate", "2026-05-20",
                        "발생일", "USER", null))));

        ProcedureVersionService procedures = mock(ProcedureVersionService.class);
        when(procedures.selectApplicable("UNAUTHORIZED_ACCOUNT_TRANSFER",
                "GENERIC_FINANCIAL_INSTITUTION", "BANK_ACCOUNT", null, true))
                .thenReturn(new ProcedureVersionService.Selection(null,
                        ProcedureVersionService.SelectionFailure.DATE_REQUIRED));
        FinancialActionPlanService service = new FinancialActionPlanService(
                consultations, sessions, mock(com.financialhelper.retrieval.ConfirmedCaseSnapshotService.class),
                mock(com.financialhelper.retrieval.ConfirmedCaseSnapshotRepository.class),
                mock(ProcedureVersionRepository.class), procedures,
                mock(EvidenceBindingResolver.class), plans, mapper);
        FinancialActionPlanData result = service.getLatest(consultationId, "token");

        assertThat(result.status()).isEqualTo(PlanStatus.NEEDS_CLARIFICATION);
        assertThat(result.actions()).isEmpty();
        assertThat(result.unresolvedFacts()).contains("transactionDate");
    }

    /** Real migrated Procedure declarations; only the approved-source resolution is simulated. */
    @Nested
    @SpringBootTest
    @ActiveProfiles("test")
    class SeededActionEvidenceRoleTests {
        @Autowired private ProcedureVersionService seededProcedures;

        @Test
        void accountTransactionDateMustReachSourceResolverWithoutIncidentDate() {
            LocalDate transactionDate = LocalDate.parse("2026-04-12");
            List<LocalDate> passedDates = new ArrayList<>();
            FinancialActionPlanData plan = evaluateAccountDate(
                    Map.of("unauthorizedTransaction", "TRUE", "accessCredentialExposed", "FALSE",
                            "transactionDate", transactionDate.toString()), passedDates);

            assertThat(plan.status()).isEqualTo(PlanStatus.READY);
            assertThat(passedDates).as("ACCOUNT source applicability must use the confirmed transaction date")
                    .containsExactly(transactionDate);
        }

        @Test
        void accountDifferentDatesRecordsWhichSourceDateWasChosen() {
            LocalDate incidentDate = LocalDate.parse("2026-05-20");
            List<LocalDate> passedDates = new ArrayList<>();
            FinancialActionPlanData plan = evaluateAccountDate(
                    Map.of("unauthorizedTransaction", "TRUE", "accessCredentialExposed", "FALSE",
                            "transactionDate", "2026-04-12", "incidentDate", incidentDate.toString()),
                    passedDates);

            assertThat(plan.status()).isEqualTo(PlanStatus.READY);
            assertThat(passedDates).containsExactly(LocalDate.parse("2026-04-12"));
        }

        @Test
        void unknownAccountTransactionDateMustNotProduceReadyPlanWithUncheckedSourceDate() {
            List<LocalDate> passedDates = new ArrayList<>();
            FinancialActionPlanData plan = evaluateAccountDate(
                    Map.of("unauthorizedTransaction", "TRUE", "accessCredentialExposed", "FALSE",
                            "transactionDate", "UNKNOWN"), passedDates);

            assertThat(passedDates).isEmpty();
            assertThat(plan.status()).as("a missing applicability date must not confirm a READY plan")
                    .isNotEqualTo(PlanStatus.READY);
            assertThat(plan.unresolvedFacts()).contains("transactionDate");
        }

        @Test
        void missingOrInvalidAccountTransactionDateCannotUseIncidentDate() {
            for (Map<String, String> values : List.of(
                    Map.of("unauthorizedTransaction", "TRUE", "accessCredentialExposed", "FALSE"),
                    Map.of("unauthorizedTransaction", "TRUE", "accessCredentialExposed", "FALSE",
                            "incidentDate", "2026-05-20"),
                    Map.of("unauthorizedTransaction", "TRUE", "accessCredentialExposed", "FALSE",
                            "transactionDate", "not-a-date", "incidentDate", "2026-05-20"))) {
                List<LocalDate> passedDates = new ArrayList<>();
                FinancialActionPlanData plan = evaluateAccountDate(values, passedDates);

                assertThat(plan.status()).isEqualTo(PlanStatus.NEEDS_CLARIFICATION);
                assertThat(plan.unresolvedFacts()).contains("transactionDate");
                assertThat(plan.actions()).isEmpty();
                assertThat(passedDates).isEmpty();
            }
        }

        @Test
        void accountSourceApplicabilityUsesTransactionDateAtPeriodBoundary() {
            Map<String, String> inPeriod = Map.of("unauthorizedTransaction", "TRUE",
                    "accessCredentialExposed", "FALSE", "transactionDate", "2026-03-31");
            Map<String, String> outsidePeriod = Map.of("unauthorizedTransaction", "TRUE",
                    "accessCredentialExposed", "FALSE", "transactionDate", "2026-04-12");
            List<LocalDate> inPeriodDates = new ArrayList<>();
            List<LocalDate> outsidePeriodDates = new ArrayList<>();

            FinancialActionPlanData allowed = evaluateAccountDate(inPeriod, inPeriodDates,
                    LocalDate.parse("2026-03-31"));
            FinancialActionPlanData blocked = evaluateAccountDate(outsidePeriod, outsidePeriodDates,
                    LocalDate.parse("2026-03-31"));

            assertThat(allowed.status()).isEqualTo(PlanStatus.READY);
            assertThat(inPeriodDates).containsExactly(LocalDate.parse("2026-03-31"));
            assertThat(blocked.status()).isNotEqualTo(PlanStatus.READY);
            assertThat(blocked.coverageGaps()).contains("EVIDENCE_NOT_APPLICABLE:synthetic-account-source");
            assertThat(outsidePeriodDates).containsExactly(LocalDate.parse("2026-04-12"));
        }

        private FinancialActionPlanData evaluateAccountDate(Map<String, String> values,
                                                             List<LocalDate> passedDates) {
            return evaluateAccountDate(values, passedDates, null);
        }

        private FinancialActionPlanData evaluateAccountDate(Map<String, String> values,
                                                             List<LocalDate> passedDates,
                                                             LocalDate sourceEndDate) {
            ProcedureVersionData procedure = breadth("UNAUTHORIZED_ACCOUNT_TRANSFER");
            assertThat(procedure.requiredFacts().stream()
                    .filter(fact -> "transactionDate".equals(fact.key()))
                    .map(ProcedureVersionData.RequiredFact::requiredForDecision))
                    .containsExactly(true);
            ProcedureVersionService applicability = mock(ProcedureVersionService.class);
            EvidenceBindingResolver resolver = mock(EvidenceBindingResolver.class);
            when(applicability.isApplicable(any(), nullable(LocalDate.class))).thenReturn(true);
            when(resolver.resolve(anyList(), nullable(LocalDate.class))).thenAnswer(invocation -> {
                LocalDate date = invocation.getArgument(1, LocalDate.class);
                passedDates.add(date);
                if (sourceEndDate != null && date != null && date.isAfter(sourceEndDate)) {
                    return new EvidenceBindingResolver.Resolution(List.of(),
                            List.of("EVIDENCE_NOT_APPLICABLE:synthetic-account-source"));
                }
                List<ProcedureVersionData.EvidenceReference> references = invocation.getArgument(0);
                List<FinancialActionPlanData.EvidenceBinding> bindings = references.stream()
                        .filter(reference -> "PROCEDURE".equals(reference.role()))
                        .map(reference -> new FinancialActionPlanData.EvidenceBinding(
                                reference.sourceKey(), UUID.randomUUID(), reference.documentVersion(),
                                List.of(UUID.randomUUID()), reference.articleReference(),
                                reference.pageReference(), reference.role()))
                        .toList();
                return new EvidenceBindingResolver.Resolution(bindings, List.of());
            });
            return service(applicability, resolver).buildFromSnapshot(
                    snapshot(), procedure, ScenarioCaseFactExtractor.fromValues(values));
        }

        @Test
        void cardInvestigationActionNeedsMissingInvestigationRole() {
            assertMissingRole(seededProcedures.requireApprovedCard(), cardFacts("RESULT_RECEIVED"),
                    "review-investigation-result", "INVESTIGATION");
        }

        @Test
        void cardResultReviewActionNeedsMissingFollowUpRole() {
            assertMissingRole(seededProcedures.requireApprovedCard(), cardFacts("RESULT_RECEIVED"),
                    "request-result-review", "FOLLOW_UP");
        }

        @Test
        void cardCompensationResultReviewNeedsMissingFollowUpRole() {
            ProcedureVersionData procedure = seededProcedures.requireApprovedCard(
                    cardFacts("RESULT_RECEIVED"));
            assertThat(procedure.scenario()).isEqualTo(ProcedureVersionService.CARD_COMPENSATION_RESULT_SCENARIO);
            assertMissingRole(procedure, cardFacts("RESULT_RECEIVED"),
                    "request-result-review", "FOLLOW_UP");
        }

        @Test
        void voiceFinancialReportNeedsProcedureRatherThanSafeGuidance() {
            assertMissingRole(breadth("VOICE_PHISHING_SUSPICIOUS_TRANSFER"), voiceFacts(),
                    "voice-report-financial", "PROCEDURE");
        }

        @Test
        void voicePoliceReportNeedsProcedureRatherThanSafeGuidance() {
            assertMissingRole(breadth("VOICE_PHISHING_SUSPICIOUS_TRANSFER"), voiceFacts(),
                    "voice-report-police", "PROCEDURE");
        }

        @Test
        void accountProtectionNeedsSafeGuidanceRatherThanProcedure() {
            assertMissingRole(breadth("UNAUTHORIZED_ACCOUNT_TRANSFER"), accountFacts(),
                    "account-transfer-protect", "SAFE_GUIDANCE");
        }

        @Test
        void matchingRoleStillAllowsTheVoiceSafeContactAction() {
            ProcedureVersionData procedure = breadth("VOICE_PHISHING_SUSPICIOUS_TRANSFER");
            assertThat(action(procedure, "voice-secure-contact").evidenceRoles())
                    .containsExactly("SAFE_GUIDANCE");
            assertThat(procedure.evidenceReferences()).extracting(ProcedureVersionData.EvidenceReference::role)
                    .contains("SAFE_GUIDANCE");

            FinancialActionPlanData plan = evaluate(procedure, voiceFactsWithoutTransfer(), Set.of("SAFE_GUIDANCE"));
            assertThat(plan.status()).isEqualTo(PlanStatus.READY);
            assertThat(plan.actions()).extracting(FinancialActionPlanData.Action::actionId)
                    .containsExactly("voice-secure-contact");
        }

        @Test
        void allRequiredRolesAllowSelectedCardProcessAction() {
            CardCaseFacts facts = cardFacts("INVESTIGATING");
            ProcedureVersionData procedure = seededProcedures.requireApprovedCard(facts);
            FinancialActionPlanData plan = evaluate(procedure, facts, Set.of("PROCEDURE", "INVESTIGATION"));

            assertThat(plan.status()).isEqualTo(PlanStatus.READY);
            assertThat(plan.actions()).extracting(FinancialActionPlanData.Action::actionId)
                    .containsExactly("track-compensation-process");
        }

        @Test
        void unknownUnselectedActionsDoNotRequireTheirRoles() {
            ProcedureVersionData procedure = breadth("VOICE_PHISHING_SUSPICIOUS_TRANSFER");
            CardCaseFacts facts = new CardCaseFacts(Map.of(
                    "suspiciousTransfer", "TRUE", "transferCompleted", "UNKNOWN",
                    "incidentDate", "2026-09-20"));
            FinancialActionPlanData plan = evaluate(procedure, facts, Set.of("SAFE_GUIDANCE"));

            assertThat(plan.status()).isEqualTo(PlanStatus.NEEDS_CLARIFICATION);
            assertThat(plan.coverageGaps()).doesNotContain("ACTION_EVIDENCE_ROLE_UNAVAILABLE");
            assertThat(plan.actions()).extracting(FinancialActionPlanData.Action::actionId)
                    .containsExactly("voice-secure-contact");
        }

        @Test
        void actionRequiringTwoRolesIsNotGroundedByOnlyOneResolvedRole() {
            CardCaseFacts facts = cardFacts("INVESTIGATING");
            ProcedureVersionData procedure = seededProcedures.requireApprovedCard(facts);
            assertThat(procedure.scenario()).isEqualTo(ProcedureVersionService.CARD_COMPENSATION_PROCESS_SCENARIO);
            assertThat(action(procedure, "track-compensation-process").evidenceRoles())
                    .containsExactly("PROCEDURE", "INVESTIGATION");
            assertThat(procedure.evidenceReferences()).extracting(ProcedureVersionData.EvidenceReference::role)
                    .contains("PROCEDURE", "INVESTIGATION");

            FinancialActionPlanData plan = evaluate(procedure, facts, Set.of("PROCEDURE"));
            assertThat(plan.status()).isEqualTo(PlanStatus.FAILED);
            assertThat(plan.evidence()).extracting(FinancialActionPlanData.EvidenceBinding::role)
                    .contains("PROCEDURE").doesNotContain("INVESTIGATION");
            assertThat(plan.actions()).extracting(FinancialActionPlanData.Action::actionId)
                    .as("status=%s; a partially resolved role set must not ground the action", plan.status())
                    .doesNotContain("track-compensation-process");
        }

        private void assertMissingRole(ProcedureVersionData procedure, CardCaseFacts facts,
                                       String actionId, String requiredRole) {
            assertThat(action(procedure, actionId).evidenceRoles()).containsExactly(requiredRole);
            assertThat(procedure.evidenceReferences()).extracting(ProcedureVersionData.EvidenceReference::role)
                    .doesNotContain(requiredRole);
            ProcedureVersionData.ConditionRule rule = procedure.conditionRules().stream()
                    .filter(candidate -> candidate.actionId().equals(actionId))
                    .findFirst().orElseThrow();
            assertThat(ConditionEvaluator.evaluate(rule.expression(), facts)).isEqualTo(ConditionResult.TRUE);

            FinancialActionPlanData plan = evaluate(procedure, facts,
                    procedure.evidenceReferences().stream()
                            .map(ProcedureVersionData.EvidenceReference::role).collect(java.util.stream.Collectors.toSet()));
            assertThat(plan.status()).isEqualTo(PlanStatus.FAILED);
            assertThat(plan.coverageGaps()).contains("ACTION_EVIDENCE_ROLE_UNAVAILABLE");
            assertThat(plan.unresolvedFacts()).isEmpty();
            assertThat(plan.evidence()).extracting(FinancialActionPlanData.EvidenceBinding::role)
                    .doesNotContain(requiredRole);
            assertThat(plan.actions()).extracting(FinancialActionPlanData.Action::actionId)
                    .as("status=%s; %s requires absent role %s", plan.status(), actionId, requiredRole)
                    .doesNotContain(actionId);
        }

        private ProcedureVersionData.ActionStep action(ProcedureVersionData procedure, String actionId) {
            return procedure.actionSteps().stream()
                    .filter(step -> step.actionId().equals(actionId)).findFirst().orElseThrow();
        }

        private ProcedureVersionData breadth(String scenario) {
            return seededProcedures.requireApproved(
                    scenario, "GENERIC_FINANCIAL_INSTITUTION", "BANK_ACCOUNT");
        }

        private FinancialActionPlanData evaluate(ProcedureVersionData procedure, CardCaseFacts facts,
                                                 Set<String> resolvedRoles) {
            ProcedureVersionService applicability = mock(ProcedureVersionService.class);
            EvidenceBindingResolver resolver = mock(EvidenceBindingResolver.class);
            when(applicability.isApplicable(any(), any())).thenReturn(true);
            when(resolver.resolve(anyList(), any())).thenAnswer(invocation -> {
                List<ProcedureVersionData.EvidenceReference> references = invocation.getArgument(0);
                List<FinancialActionPlanData.EvidenceBinding> bindings = references.stream()
                        .filter(reference -> resolvedRoles.contains(reference.role()))
                        .map(reference -> new FinancialActionPlanData.EvidenceBinding(
                                reference.sourceKey(), UUID.randomUUID(), reference.documentVersion(),
                                List.of(UUID.randomUUID()), reference.articleReference(),
                                reference.pageReference(), reference.role()))
                        .toList();
                return new EvidenceBindingResolver.Resolution(bindings, List.of());
            });
            return service(applicability, resolver).buildFromSnapshot(snapshot(), procedure, facts);
        }

        private CardCaseFacts cardFacts(String compensationStatus) {
            return new CardCaseFacts(Map.of(
                    "institution", ProcedureVersionService.KB_INSTITUTION,
                    "productType", ProcedureVersionService.PERSONAL_CREDIT_CARD,
                    "cardLost", "TRUE", "unauthorizedPayment", "TRUE",
                    "transactionType", "CREDIT_SALE", "domestic", "TRUE",
                    "reported", "TRUE", "incidentDate", "2026-09-20",
                    "compensationStatus", compensationStatus, "resultDisputed", "TRUE"));
        }

        private CardCaseFacts voiceFacts() {
            return new CardCaseFacts(Map.of(
                    "suspiciousTransfer", "TRUE", "transferCompleted", "TRUE",
                    "reportedToFinancialInstitution", "FALSE", "policeReported", "FALSE",
                    "incidentDate", "2026-09-20"));
        }

        private CardCaseFacts voiceFactsWithoutTransfer() {
            return new CardCaseFacts(Map.of(
                    "suspiciousTransfer", "TRUE", "transferCompleted", "FALSE",
                    "reportedToFinancialInstitution", "TRUE", "policeReported", "TRUE",
                    "incidentDate", "2026-09-20"));
        }

        private CardCaseFacts accountFacts() {
            return new CardCaseFacts(Map.of(
                    "unauthorizedTransaction", "TRUE", "accessCredentialExposed", "TRUE",
                    "transactionDate", "2026-09-20"));
        }
    }

    @Test
    void unknownConditionDoesNotCreateAnAction() {
        ProcedureVersionService procedureService = mock(ProcedureVersionService.class);
        EvidenceBindingResolver resolver = mock(EvidenceBindingResolver.class);
        when(procedureService.isApplicable(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(true);
        when(resolver.resolve(org.mockito.ArgumentMatchers.anyList(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(new EvidenceBindingResolver.Resolution(List.of(), List.of()));
        FinancialActionPlanService service = service(procedureService, resolver);

        FinancialActionPlanData result = service.buildFromSnapshot(
                snapshot(), procedure(false), new CardCaseFacts(Map.of(
                        "institution", ProcedureVersionService.KB_INSTITUTION,
                        "productType", ProcedureVersionService.PERSONAL_CREDIT_CARD,
                        "cardLost", "TRUE",
                        "unauthorizedPayment", "TRUE",
                        "transactionType", "CREDIT_SALE",
                        "domestic", "TRUE",
                        "incidentDate", "2026-09-01"
                )));

        assertThat(result.status()).isEqualTo(PlanStatus.NEEDS_CLARIFICATION);
        assertThat(result.actions()).isEmpty();
        assertThat(result.unresolvedFacts()).contains("reported");
    }

    @Test
    void approvedProcedureProducesOrderedActionForExplicitFalseReport() {
        ProcedureVersionService procedureService = mock(ProcedureVersionService.class);
        EvidenceBindingResolver resolver = mock(EvidenceBindingResolver.class);
        when(procedureService.isApplicable(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(true);
        when(resolver.resolve(org.mockito.ArgumentMatchers.anyList(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(new EvidenceBindingResolver.Resolution(List.of(), List.of()));
        FinancialActionPlanService service = service(procedureService, resolver);

        FinancialActionPlanData result = service.buildFromSnapshot(
                snapshot(), procedure(true), new CardCaseFacts(Map.of(
                        "institution", ProcedureVersionService.KB_INSTITUTION,
                        "productType", ProcedureVersionService.PERSONAL_CREDIT_CARD,
                        "cardLost", "TRUE",
                        "unauthorizedPayment", "TRUE",
                        "transactionType", "CREDIT_SALE",
                        "domestic", "TRUE",
                        "reported", "FALSE",
                        "incidentDate", "2026-09-01"
                )));

        assertThat(result.status()).isEqualTo(PlanStatus.READY);
        assertThat(result.actions()).extracting(FinancialActionPlanData.Action::actionId)
                .containsExactly("report-loss");
    }

    @Test
    void unsupportedInstitutionFailsClosed() {
        ProcedureVersionService procedureService = mock(ProcedureVersionService.class);
        FinancialActionPlanService service = service(procedureService, mock(EvidenceBindingResolver.class));
        when(procedureService.isApplicable(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(true);

        FinancialActionPlanData result = service.buildFromSnapshot(
                snapshot(), procedure(true), new CardCaseFacts(Map.of(
                        "institution", "OTHER_CARD",
                        "productType", ProcedureVersionService.PERSONAL_CREDIT_CARD,
                        "incidentDate", "2026-09-01"
                )));

        assertThat(result.status()).isEqualTo(PlanStatus.UNSUPPORTED);
        assertThat(result.actions()).isEmpty();
        assertThat(result.coverageGaps()).contains("CARD_SCOPE_OUT_OF_SCOPE");
    }

    @Test
    void unapprovedProcedureCannotExecute() {
        ProcedureVersionService procedureService = mock(ProcedureVersionService.class);
        FinancialActionPlanService service = service(procedureService, mock(EvidenceBindingResolver.class));
        ProcedureVersionData draft = new ProcedureVersionData(
                UUID.randomUUID(), ProcedureVersionService.CARD_SCENARIO,
                ProcedureVersionService.KB_INSTITUTION, ProcedureVersionService.PERSONAL_CREDIT_CARD,
                2, ProcedureStatus.DRAFT, null, null, List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of(), List.of(), "draft", null, null);

        FinancialActionPlanData result = service.buildFromSnapshot(
                snapshot(), draft, new CardCaseFacts(Map.of()));

        assertThat(result.status()).isEqualTo(PlanStatus.FAILED);
        assertThat(result.coverageGaps()).containsExactly("PROCEDURE_NOT_APPROVED");
    }

    @Test
    void requiredFactMissingIsReturnedBeforeActionEvaluation() {
        ProcedureVersionService procedureService = mock(ProcedureVersionService.class);
        EvidenceBindingResolver resolver = mock(EvidenceBindingResolver.class);
        when(procedureService.isApplicable(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(true);
        FinancialActionPlanService service = service(procedureService, resolver);

        FinancialActionPlanData result = service.buildFromSnapshot(
                snapshot(), procedure(true), new CardCaseFacts(Map.of(
                        "institution", ProcedureVersionService.KB_INSTITUTION,
                        "productType", ProcedureVersionService.PERSONAL_CREDIT_CARD,
                        "incidentDate", "2026-09-01")));

        assertThat(result.status()).isEqualTo(PlanStatus.NEEDS_CLARIFICATION);
        assertThat(result.unresolvedFacts()).contains("reported");
        org.mockito.Mockito.verifyNoInteractions(resolver);
    }

    @Test
    void nonBlockingUnknownKeepsDefinitelyTrueActionAsPartialGuidance() {
        ProcedureVersionService procedureService = mock(ProcedureVersionService.class);
        EvidenceBindingResolver resolver = mock(EvidenceBindingResolver.class);
        when(procedureService.isApplicable(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(true);
        when(resolver.resolve(org.mockito.ArgumentMatchers.anyList(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(new EvidenceBindingResolver.Resolution(List.of(), List.of()));
        FinancialActionPlanService service = service(procedureService, resolver);

        FinancialActionPlanData result = service.buildFromSnapshot(
                snapshot(), procedure(true), new CardCaseFacts(Map.of(
                        "institution", ProcedureVersionService.KB_INSTITUTION,
                        "productType", ProcedureVersionService.PERSONAL_CREDIT_CARD,
                        "cardLost", "TRUE",
                        "unauthorizedPayment", "TRUE",
                        "transactionType", "CREDIT_SALE",
                        "domestic", "TRUE",
                        "reported", "FALSE")));

        assertThat(result.status()).isEqualTo(PlanStatus.NEEDS_CLARIFICATION);
        assertThat(result.actions()).extracting(FinancialActionPlanData.Action::actionId)
                .containsExactly("report-loss");
        assertThat(result.unresolvedFacts()).contains("incidentDate");
        assertThat(result.warnings()).contains("PARTIAL_GUIDANCE_ONLY");
    }

    @Test
    void breadthProcedureWithoutReviewedEvidenceFailsClosed() {
        ProcedureVersionService procedureService = mock(ProcedureVersionService.class);
        EvidenceBindingResolver resolver = mock(EvidenceBindingResolver.class);
        when(procedureService.isApplicable(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(true);
        FinancialActionPlanService service = service(procedureService, resolver);

        ProcedureVersionData breadth = new ProcedureVersionData(
                UUID.randomUUID(), "VOICE_PHISHING_SUSPICIOUS_TRANSFER",
                "GENERIC_FINANCIAL_INSTITUTION", "BANK_ACCOUNT", 1, ProcedureStatus.APPROVED,
                null, null, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of("source corpus pending"), "breadth", "test", null);

        FinancialActionPlanData result = service.buildFromSnapshot(
                snapshot(), breadth, new CardCaseFacts(Map.of("suspiciousTransfer", "TRUE")));

        assertThat(result.status()).isEqualTo(PlanStatus.NEEDS_CLARIFICATION);
        assertThat(result.actions()).isEmpty();
        assertThat(result.coverageGaps()).containsExactly("OFFICIAL_EVIDENCE_UNAVAILABLE");
    }

    @Test
    void breadthSafeActionIsFullWhenUnrelatedUnknownDoesNotAffectAction() {
        ProcedureVersionService procedureService = mock(ProcedureVersionService.class);
        EvidenceBindingResolver resolver = mock(EvidenceBindingResolver.class);
        when(procedureService.isApplicable(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(true);
        when(resolver.resolve(org.mockito.ArgumentMatchers.anyList(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(new EvidenceBindingResolver.Resolution(
                        List.of(new FinancialActionPlanData.EvidenceBinding(
                                "fsc-2026-alert", UUID.randomUUID(), 1, List.of(UUID.randomUUID()),
                                null, null, "SAFE_GUIDANCE")), List.of()));
        FinancialActionPlanService service = service(procedureService, resolver);

        ConditionExpression suspicious = new ConditionExpression(
                null, "suspiciousTransfer", ConditionOperator.EQ, "TRUE", List.of());
        ProcedureVersionData procedure = new ProcedureVersionData(
                UUID.randomUUID(), "VOICE_PHISHING_SUSPICIOUS_TRANSFER",
                "GENERIC_FINANCIAL_INSTITUTION", "BANK_ACCOUNT", 1, ProcedureStatus.APPROVED,
                null, null,
                List.of(new ProcedureVersionData.RequiredFact("transferCompleted", "BOOLEAN", true),
                        new ProcedureVersionData.RequiredFact("suspiciousTransfer", "BOOLEAN", true)),
                List.of(new ProcedureVersionData.ConditionRule("voice-secure-contact", suspicious)),
                List.of(new ProcedureVersionData.ActionStep("voice-secure-contact", 1,
                        "금융회사 공식 채널 확인", "안전한 공식 채널로 문의합니다.", null, List.of())),
                List.of(),
                List.of(new ProcedureVersionData.EvidenceReference(
                        "fsc-2026-alert", 1, "hash", null, null, "SAFE_GUIDANCE", true)),
                List.of(), List.of(), List.of("송금 여부가 확인되면 추가 안내가 달라질 수 있다."),
                "test", "test", null);

        FinancialActionPlanData result = service.buildFromSnapshot(
                snapshot(), procedure, new CardCaseFacts(Map.of(
                        "suspiciousTransfer", "TRUE", "transferCompleted", "UNKNOWN")));

        assertThat(result.status()).isEqualTo(PlanStatus.READY);
        assertThat(result.actions()).extracting(FinancialActionPlanData.Action::actionId)
                .containsExactly("voice-secure-contact");
        assertThat(result.unresolvedFacts()).isEmpty();
        assertThat(result.warnings()).isEmpty();
    }

    @Test
    void overseasTransactionIsOutsideTheInitialCardSlice() {
        ProcedureVersionService procedureService = mock(ProcedureVersionService.class);
        FinancialActionPlanService service = service(procedureService, mock(EvidenceBindingResolver.class));
        when(procedureService.isApplicable(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(true);

        FinancialActionPlanData result = service.buildFromSnapshot(
                snapshot(), procedure(true), new CardCaseFacts(Map.of(
                        "institution", ProcedureVersionService.KB_INSTITUTION,
                        "productType", ProcedureVersionService.PERSONAL_CREDIT_CARD,
                        "cardLost", "TRUE", "unauthorizedPayment", "TRUE",
                        "transactionType", "CREDIT_SALE", "domestic", "FALSE",
                        "reported", "FALSE", "incidentDate", "2026-09-01")));

        assertThat(result.status()).isEqualTo(PlanStatus.UNSUPPORTED);
        assertThat(result.coverageGaps()).contains("CARD_SCOPE_OUT_OF_SCOPE");
    }

    @Test
    void cardLoanTransactionIsOutsideTheInitialCardSlice() {
        ProcedureVersionService procedureService = mock(ProcedureVersionService.class);
        FinancialActionPlanService service = service(procedureService, mock(EvidenceBindingResolver.class));
        when(procedureService.isApplicable(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(true);

        FinancialActionPlanData result = service.buildFromSnapshot(
                snapshot(), procedure(true), new CardCaseFacts(Map.of(
                        "institution", ProcedureVersionService.KB_INSTITUTION,
                        "productType", ProcedureVersionService.PERSONAL_CREDIT_CARD,
                        "cardLost", "TRUE", "unauthorizedPayment", "TRUE",
                        "transactionType", "CARD_LOAN", "domestic", "TRUE",
                        "reported", "FALSE", "incidentDate", "2026-09-01")));

        assertThat(result.status()).isEqualTo(PlanStatus.UNSUPPORTED);
        assertThat(result.coverageGaps()).contains("CARD_SCOPE_OUT_OF_SCOPE");
    }

    @Test
    void heldCardBranchProducesOnlyItsApprovedConfirmationAction() {
        ProcedureVersionService procedureService = mock(ProcedureVersionService.class);
        EvidenceBindingResolver resolver = mock(EvidenceBindingResolver.class);
        when(procedureService.isApplicable(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(true);
        when(resolver.resolve(org.mockito.ArgumentMatchers.anyList(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(new EvidenceBindingResolver.Resolution(
                        List.of(new FinancialActionPlanData.EvidenceBinding(
                                "kb-card-compensation-process", UUID.randomUUID(), 1,
                                List.of(UUID.randomUUID()), null, null, "PROCEDURE")), List.of()));

        FinancialActionPlanData result = service(procedureService, resolver).buildFromSnapshot(
                snapshot(), cardBranchProcedure(ProcedureVersionService.CARD_HELD_UNAUTHORIZED_SCENARIO),
                new CardCaseFacts(Map.of(
                        "institution", ProcedureVersionService.KB_INSTITUTION,
                        "productType", ProcedureVersionService.PERSONAL_CREDIT_CARD,
                        "cardLost", "FALSE", "unauthorizedPayment", "TRUE",
                        "transactionType", "CREDIT_SALE", "domestic", "TRUE",
                        "incidentDate", "2026-09-01")));

        assertThat(result.status()).isEqualTo(PlanStatus.READY);
        assertThat(result.actions()).extracting(FinancialActionPlanData.Action::actionId)
                .containsExactly("confirm-held-card-payment");
    }

    @Test
    void independentLossActionSurvivesUnknownUnauthorizedPayment() {
        ProcedureVersionService procedureService = mock(ProcedureVersionService.class);
        EvidenceBindingResolver resolver = mock(EvidenceBindingResolver.class);
        when(procedureService.isApplicable(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(true);
        when(resolver.resolve(org.mockito.ArgumentMatchers.anyList(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(new EvidenceBindingResolver.Resolution(List.of(), List.of()));

        FinancialActionPlanData result = service(procedureService, resolver).buildFromSnapshot(
                snapshot(), procedure(true), new CardCaseFacts(Map.of(
                        "institution", ProcedureVersionService.KB_INSTITUTION,
                        "productType", ProcedureVersionService.PERSONAL_CREDIT_CARD,
                        "cardLost", "TRUE",
                        "unauthorizedPayment", "UNKNOWN",
                        "reported", "FALSE",
                        "incidentDate", "2026-09-01")));

        assertThat(result.status()).isEqualTo(PlanStatus.NEEDS_CLARIFICATION);
        assertThat(result.actions()).extracting(FinancialActionPlanData.Action::actionId)
                .containsExactly("report-loss");
        assertThat(result.unresolvedFacts()).contains("unauthorizedPayment");
        assertThat(result.warnings()).contains("PARTIAL_GUIDANCE_ONLY");
    }

    @Test
    void dependentHeldCardTransactionTypeUnknownBlocksOnlyHeldCardAction() {
        ProcedureVersionService procedureService = mock(ProcedureVersionService.class);
        EvidenceBindingResolver resolver = mock(EvidenceBindingResolver.class);
        when(procedureService.isApplicable(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(true);

        FinancialActionPlanData result = service(procedureService, resolver).buildFromSnapshot(
                snapshot(), cardBranchProcedure(ProcedureVersionService.CARD_HELD_UNAUTHORIZED_SCENARIO),
                new CardCaseFacts(Map.of(
                        "institution", ProcedureVersionService.KB_INSTITUTION,
                        "productType", ProcedureVersionService.PERSONAL_CREDIT_CARD,
                        "cardLost", "FALSE", "unauthorizedPayment", "TRUE",
                        "transactionType", "UNKNOWN", "domestic", "TRUE",
                        "incidentDate", "2026-09-01")));

        assertThat(result.status()).isEqualTo(PlanStatus.NEEDS_CLARIFICATION);
        assertThat(result.actions()).isEmpty();
        assertThat(result.unresolvedFacts()).contains("transactionType");
    }

    @Test
    void cardSpecificActionIsWithheldWhenIssuerIsUnknown() {
        ProcedureVersionService procedureService = mock(ProcedureVersionService.class);
        EvidenceBindingResolver resolver = mock(EvidenceBindingResolver.class);
        when(procedureService.isApplicable(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(true);
        when(resolver.resolve(org.mockito.ArgumentMatchers.anyList(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(new EvidenceBindingResolver.Resolution(List.of(), List.of()));

        FinancialActionPlanData result = service(procedureService, resolver).buildFromSnapshot(
                snapshot(), cardBranchProcedure(ProcedureVersionService.CARD_HELD_UNAUTHORIZED_SCENARIO),
                new CardCaseFacts(Map.of(
                        "institution", "UNKNOWN",
                        "productType", ProcedureVersionService.PERSONAL_CREDIT_CARD,
                        "cardLost", "FALSE", "unauthorizedPayment", "TRUE",
                        "transactionType", "CREDIT_SALE", "domestic", "TRUE",
                        "incidentDate", "2026-09-01")));

        assertThat(result.status()).isEqualTo(PlanStatus.NEEDS_CLARIFICATION);
        assertThat(result.actions()).isEmpty();
        assertThat(result.warnings()).contains("CARD_INSTITUTION_OR_PRODUCT_REQUIRED");
    }

    @Test
    void knownFactsWithoutAnApprovedActionBecomeExplicitPartialGuidance() {
        ProcedureVersionService procedureService = mock(ProcedureVersionService.class);
        EvidenceBindingResolver resolver = mock(EvidenceBindingResolver.class);
        when(procedureService.isApplicable(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(true);
        when(resolver.resolve(org.mockito.ArgumentMatchers.anyList(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(new EvidenceBindingResolver.Resolution(List.of(), List.of()));

        ProcedureVersionData procedure = new ProcedureVersionData(
                UUID.randomUUID(), ProcedureVersionService.CARD_LOSS_ONLY_SCENARIO,
                ProcedureVersionService.KB_INSTITUTION, ProcedureVersionService.PERSONAL_CREDIT_CARD,
                1, ProcedureStatus.APPROVED, null, null,
                List.of(
                        new ProcedureVersionData.RequiredFact("institution", "INSTITUTION", true),
                        new ProcedureVersionData.RequiredFact("productType", "ENUM", true),
                        new ProcedureVersionData.RequiredFact("cardLost", "BOOLEAN", true),
                        new ProcedureVersionData.RequiredFact("unauthorizedPayment", "BOOLEAN", true),
                        new ProcedureVersionData.RequiredFact("reported", "BOOLEAN", true),
                        new ProcedureVersionData.RequiredFact("incidentDate", "DATE", true)),
                List.of(new ProcedureVersionData.ConditionRule(
                        "report-loss",
                        new ConditionExpression(null, "reported", ConditionOperator.EQ, "FALSE", List.of()))),
                List.of(new ProcedureVersionData.ActionStep(
                        "report-loss", 1, "분실·도난 신고", "신고", "channel", List.of())),
                List.of(), List.of(), List.of(), List.of(), List.of(), "test", "test", null);

        FinancialActionPlanData result = service(procedureService, resolver).buildFromSnapshot(
                snapshot(), procedure, new CardCaseFacts(Map.of(
                        "institution", ProcedureVersionService.KB_INSTITUTION,
                        "productType", ProcedureVersionService.PERSONAL_CREDIT_CARD,
                        "cardLost", "TRUE",
                        "unauthorizedPayment", "FALSE",
                        "reported", "TRUE",
                        "incidentDate", "2026-09-01")));

        assertThat(result.status()).isEqualTo(PlanStatus.NEEDS_CLARIFICATION);
        assertThat(result.actions()).isEmpty();
        assertThat(result.coverageGaps()).isEmpty();
        assertThat(result.warnings()).containsExactly("NO_APPROVED_SAFE_ACTION");
    }

    private FinancialActionPlanService service(
            ProcedureVersionService procedureService,
            EvidenceBindingResolver resolver
    ) {
        return new FinancialActionPlanService(
                mock(ConsultationRepository.class), mock(GuestSessionService.class),
                mock(com.financialhelper.retrieval.ConfirmedCaseSnapshotService.class),
                mock(com.financialhelper.retrieval.ConfirmedCaseSnapshotRepository.class),
                mock(ProcedureVersionRepository.class), procedureService, resolver,
                mock(FinancialActionPlanRepository.class),
                tools.jackson.databind.json.JsonMapper.builder().build());
    }

    private ConfirmedCaseSnapshotData snapshot() {
        return new ConfirmedCaseSnapshotData(
                UUID.randomUUID(), UUID.randomUUID(), 1, 1, List.of(), List.of(), null);
    }

    private ProcedureVersionData procedure(boolean includeReported) {
        List<ProcedureVersionData.RequiredFact> required = new java.util.ArrayList<>(List.of(
                new ProcedureVersionData.RequiredFact("institution", "INSTITUTION", true),
                new ProcedureVersionData.RequiredFact("productType", "ENUM", true),
                new ProcedureVersionData.RequiredFact("incidentDate", "DATE", true)));
        if (includeReported) {
            required.add(new ProcedureVersionData.RequiredFact("reported", "BOOLEAN", true));
        }
        ConditionExpression report = new ConditionExpression("AND", null, null, null, List.of(
                new ConditionExpression(null, "cardLost", ConditionOperator.EQ, "TRUE", List.of()),
                new ConditionExpression(null, "reported", ConditionOperator.EQ, "FALSE", List.of())));
        return new ProcedureVersionData(
                UUID.randomUUID(), ProcedureVersionService.CARD_SCENARIO,
                ProcedureVersionService.KB_INSTITUTION, ProcedureVersionService.PERSONAL_CREDIT_CARD,
                1, ProcedureStatus.APPROVED, null, null, required,
                List.of(new ProcedureVersionData.ConditionRule("report-loss", report)),
                List.of(new ProcedureVersionData.ActionStep("report-loss", 1, "분실·도난 신고", "신고", "channel", List.of())),
                List.of(), List.of(), List.of(), List.of(), List.of(), "", "test", null);
    }

    private ProcedureVersionData cardBranchProcedure(String scenario) {
        ConditionExpression condition = new ConditionExpression("AND", null, null, null, List.of(
                new ConditionExpression(null, "cardLost", ConditionOperator.EQ, "FALSE", List.of()),
                new ConditionExpression(null, "unauthorizedPayment", ConditionOperator.EQ, "TRUE", List.of()),
                new ConditionExpression(null, "transactionType", ConditionOperator.EQ, "CREDIT_SALE", List.of()),
                new ConditionExpression(null, "domestic", ConditionOperator.EQ, "TRUE", List.of())));
        return new ProcedureVersionData(
                UUID.randomUUID(), scenario, ProcedureVersionService.KB_INSTITUTION,
                ProcedureVersionService.PERSONAL_CREDIT_CARD, 1, ProcedureStatus.APPROVED,
                null, null,
                List.of(
                        new ProcedureVersionData.RequiredFact("institution", "INSTITUTION", true),
                        new ProcedureVersionData.RequiredFact("productType", "ENUM", true),
                        new ProcedureVersionData.RequiredFact("cardLost", "BOOLEAN", true),
                        new ProcedureVersionData.RequiredFact("unauthorizedPayment", "BOOLEAN", true),
                        new ProcedureVersionData.RequiredFact("transactionType", "ENUM", true),
                        new ProcedureVersionData.RequiredFact("domestic", "BOOLEAN", true),
                        new ProcedureVersionData.RequiredFact("incidentDate", "DATE", true)),
                List.of(new ProcedureVersionData.ConditionRule("confirm-held-card-payment", condition)),
                List.of(new ProcedureVersionData.ActionStep("confirm-held-card-payment", 1,
                        "미인지 신용판매 확인", "공식 접수 대상인지 확인합니다.",
                        "kb-card-compensation-process", List.of("PROCEDURE"))),
                List.of(), List.of(), List.of(), List.of(), List.of(), "test", "test", null);
    }
}
