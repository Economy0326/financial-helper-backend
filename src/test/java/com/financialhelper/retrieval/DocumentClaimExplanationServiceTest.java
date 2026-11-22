package com.financialhelper.retrieval;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DocumentClaimExplanationServiceTest {
    @Test void incompleteEvidenceNeverReachesProvider() {
        var snapshots = org.mockito.Mockito.mock(DocumentClaimSnapshotService.class);
        var accounts = org.mockito.Mockito.mock(com.financialhelper.account.AccountSessionService.class);
        @SuppressWarnings("unchecked") org.springframework.beans.factory.ObjectProvider<com.financialhelper.ai.OpenAiStructuredClient> clients = org.mockito.Mockito.mock(org.springframework.beans.factory.ObjectProvider.class);
        var id = java.util.UUID.randomUUID();
        var current = new DocumentClaimSnapshotService.Response(id, "READY_CANDIDATES", java.util.List.of(), java.util.List.of("확인되지 않은 범위"));
        org.mockito.Mockito.when(accounts.currentAccountId()).thenReturn(java.util.Optional.of(java.util.UUID.randomUUID()));
        org.mockito.Mockito.when(snapshots.get(id)).thenReturn(current);
        var service = new DocumentClaimExplanationService(snapshots, org.mockito.Mockito.mock(com.financialhelper.source.DocumentClaimReviewerService.class), accounts, org.mockito.Mockito.mock(org.springframework.jdbc.core.JdbcTemplate.class), clients, tools.jackson.databind.json.JsonMapper.builder().build(), true, true, allowedQuota(), budget());
        assertThat(service.explain(id, java.util.UUID.randomUUID()).reason()).isEqualTo("INSUFFICIENT_EVIDENCE");
        org.mockito.Mockito.verifyNoInteractions(clients);
        var client = org.mockito.Mockito.mock(com.financialhelper.ai.OpenAiStructuredClient.class);
        org.mockito.Mockito.when(clients.getIfAvailable()).thenReturn(client);
        assertThat(service.availability(id)).isEqualTo("INSUFFICIENT_EVIDENCE");
        org.mockito.Mockito.verifyNoInteractions(client);
    }
    private static DocumentAiExplanationQuota allowedQuota() {
        var quota = org.mockito.Mockito.mock(DocumentAiExplanationQuota.class);
        org.mockito.Mockito.lenient().when(quota.reserve(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any())).thenReturn(true);
        org.mockito.Mockito.lenient().when(quota.succeed(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any())).thenReturn(true);
        return quota;
    }
    private static com.financialhelper.account.GlobalAiBudgetGuard budget() { return org.mockito.Mockito.mock(com.financialhelper.account.GlobalAiBudgetGuard.class); }
    @Test void semanticRejectionFallsBackAndDoesNotConsumeQuota() {
        var snapshots = org.mockito.Mockito.mock(DocumentClaimSnapshotService.class);
        var reviewer = org.mockito.Mockito.mock(com.financialhelper.source.DocumentClaimReviewerService.class);
        var accounts = org.mockito.Mockito.mock(com.financialhelper.account.AccountSessionService.class);
        var jdbc = org.mockito.Mockito.mock(org.springframework.jdbc.core.JdbcTemplate.class);
        @SuppressWarnings("unchecked") org.springframework.beans.factory.ObjectProvider<com.financialhelper.ai.OpenAiStructuredClient> clients = org.mockito.Mockito.mock(org.springframework.beans.factory.ObjectProvider.class);
        var client = org.mockito.Mockito.mock(com.financialhelper.ai.OpenAiStructuredClient.class);
        var quota = allowedQuota(); var account = java.util.UUID.randomUUID(); var id = java.util.UUID.randomUUID(); var request = java.util.UUID.randomUUID();
        var item = new DocumentClaimSnapshotService.Evidence("a", java.util.UUID.randomUUID(), 1, "가상상품은 성인에게 제공됩니다.", "기관", "문서", "https://example.invalid", "p1", null, "NOT_VERIFIED", java.util.List.of());
        var current = new DocumentClaimSnapshotService.Response(id, "READY_CANDIDATES", java.util.List.of(item));
        org.mockito.Mockito.when(accounts.currentAccountId()).thenReturn(java.util.Optional.of(account));
        org.mockito.Mockito.when(snapshots.get(id)).thenReturn(current);
        org.mockito.Mockito.when(clients.getIfAvailable()).thenReturn(client);
        var service = org.mockito.Mockito.spy(new DocumentClaimExplanationService(snapshots, reviewer, accounts, jdbc, clients, tools.jackson.databind.json.JsonMapper.builder().build(), true, true, quota, budget()));
        org.mockito.Mockito.doReturn(new DocumentClaimExplanationService.Context("합성 질문", java.util.UUID.randomUUID())).when(service).context(id, account);
        org.mockito.Mockito.doReturn(true).when(service).eligible(id, current);
        assertThat(service.availability(id)).isEqualTo("AVAILABLE");
        org.mockito.Mockito.when(client.generateStructured(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.eq(DocumentClaimExplanationService.GeneratedBatch.class), org.mockito.ArgumentMatchers.eq(2048L)))
                .thenReturn(new DocumentClaimExplanationService.GeneratedBatch(java.util.List.of(new DocumentClaimExplanationService.Generated("a", "가상상품은 미성년자에게 제공됩니다."))));
        org.mockito.Mockito.when(client.generateStructured(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.eq(DocumentClaimExplanationService.SemanticBatch.class), org.mockito.ArgumentMatchers.eq(1024L)))
                .thenReturn(new DocumentClaimExplanationService.SemanticBatch(java.util.List.of(new DocumentClaimExplanationService.SemanticDecision("a", "UNSUPPORTED"))));
        assertThat(service.explain(id, request).reason()).isEqualTo("VALIDATION_FAILED");
        org.mockito.Mockito.verify(quota).fail(account, request);
        org.mockito.Mockito.verify(quota, org.mockito.Mockito.never()).succeed(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        org.mockito.Mockito.verifyNoInteractions(jdbc);
        var failedRequest = java.util.UUID.randomUUID();
        org.mockito.Mockito.when(client.generateStructured(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.eq(DocumentClaimExplanationService.SemanticBatch.class), org.mockito.ArgumentMatchers.eq(1024L)))
                .thenThrow(new IllegalStateException("synthetic verifier unavailable"));
        assertThat(service.explain(id, failedRequest).reason()).isEqualTo("PROVIDER_ERROR");
        org.mockito.Mockito.verify(quota).fail(account, failedRequest);
        org.mockito.Mockito.verify(quota, org.mockito.Mockito.never()).succeed(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }
    @Test void semanticResponseMustCoverEveryClaimExactlyOnce() {
        var item = new DocumentClaimSnapshotService.Evidence("a", java.util.UUID.randomUUID(), 1, "합성 원문", "기관", "문서", "https://example.invalid", "p1", null, "NOT_VERIFIED", java.util.List.of());
        assertThat(DocumentClaimExplanationService.semanticSupported(new DocumentClaimExplanationService.SemanticBatch(java.util.List.of(new DocumentClaimExplanationService.SemanticDecision("a", "SUPPORTED"))), java.util.List.of(item))).isTrue();
        for (String verdict : java.util.List.of("UNSUPPORTED", "UNCERTAIN", "supported"))
            assertThat(DocumentClaimExplanationService.semanticSupported(new DocumentClaimExplanationService.SemanticBatch(java.util.List.of(new DocumentClaimExplanationService.SemanticDecision("a", verdict))), java.util.List.of(item))).isFalse();
        assertThat(DocumentClaimExplanationService.semanticSupported(new DocumentClaimExplanationService.SemanticBatch(java.util.List.of(new DocumentClaimExplanationService.SemanticDecision("b", "SUPPORTED"))), java.util.List.of(item))).isFalse();
    }
    @Test
    @SuppressWarnings("unchecked")
    void providerIsCalledOnlyForPermittedCurrentEvidenceAndOutputIsNotStored() {
        var snapshots = org.mockito.Mockito.mock(DocumentClaimSnapshotService.class);
        var reviewer = org.mockito.Mockito.mock(com.financialhelper.source.DocumentClaimReviewerService.class);
        var accounts = org.mockito.Mockito.mock(com.financialhelper.account.AccountSessionService.class);
        var jdbc = org.mockito.Mockito.mock(org.springframework.jdbc.core.JdbcTemplate.class);
        var clients = org.mockito.Mockito.mock(org.springframework.beans.factory.ObjectProvider.class);
        var client = org.mockito.Mockito.mock(com.financialhelper.ai.OpenAiStructuredClient.class);
        var id = java.util.UUID.randomUUID(); var account = java.util.UUID.randomUUID();
        var item = new DocumentClaimSnapshotService.Evidence("a", java.util.UUID.randomUUID(), 1, "합성 원문", "합성 기관", "합성 문서", "https://synthetic.invalid", "p1", java.time.LocalDate.of(2025, 1, 1), "NOT_VERIFIED", java.util.List.of(new DocumentClaimSnapshotService.Related("EXCEPTION", 0, 2, "제외")));
        var current = new DocumentClaimSnapshotService.Response(id, "READY_CANDIDATES", java.util.List.of(item));
        org.mockito.Mockito.when(accounts.currentAccountId()).thenReturn(java.util.Optional.of(account));
        org.mockito.Mockito.when(snapshots.get(id)).thenReturn(current);
        org.mockito.Mockito.when(clients.getIfAvailable()).thenReturn(client);
        var quota = allowedQuota();
        var service = org.mockito.Mockito.spy(new DocumentClaimExplanationService(snapshots, reviewer, accounts, jdbc, clients, tools.jackson.databind.json.JsonMapper.builder().build(), true, true, quota, budget()));
        org.mockito.Mockito.doReturn(new DocumentClaimExplanationService.Context("합성 질문", java.util.UUID.randomUUID())).when(service).context(id, account);
        org.mockito.Mockito.doReturn(true).when(service).eligible(id, current);
        org.mockito.Mockito.when(client.generateStructured(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.eq(DocumentClaimExplanationService.GeneratedBatch.class), org.mockito.ArgumentMatchers.eq(2048L)))
                .thenReturn(new DocumentClaimExplanationService.GeneratedBatch(java.util.List.of(new DocumentClaimExplanationService.Generated("a", "합성 원문은 제외예요."))));
        org.mockito.Mockito.when(client.generateStructured(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.eq(DocumentClaimExplanationService.SemanticBatch.class), org.mockito.ArgumentMatchers.eq(1024L)))
                .thenReturn(new DocumentClaimExplanationService.SemanticBatch(java.util.List.of(new DocumentClaimExplanationService.SemanticDecision("a", "SUPPORTED"))));
        var explained = service.explain(id, java.util.UUID.randomUUID());
        assertThat(explained.status()).isEqualTo("AI_EXPLANATION");
        org.mockito.Mockito.verify(quota).succeed(org.mockito.ArgumentMatchers.eq(account), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        assertThat(explained.explanation()).contains("원문은 제외", "예외: 제외");
        org.mockito.Mockito.verify(client).generateStructured(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.eq(DocumentClaimExplanationService.GeneratedBatch.class), org.mockito.ArgumentMatchers.eq(2048L));
        org.mockito.Mockito.verify(client).generateStructured(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.eq(DocumentClaimExplanationService.SemanticBatch.class), org.mockito.ArgumentMatchers.eq(1024L));
        org.mockito.Mockito.verifyNoInteractions(jdbc);
        org.mockito.Mockito.doReturn(false).when(service).eligible(id, current);
        assertThat(service.availability(id)).isEqualTo("RIGHTS_UNAVAILABLE");
        assertThat(service.explain(id, java.util.UUID.randomUUID()).reason()).isEqualTo("RIGHTS_UNAVAILABLE");
        org.mockito.Mockito.verifyNoMoreInteractions(client);
        org.mockito.Mockito.doReturn(true).when(service).eligible(id, current);
        org.mockito.Mockito.when(quota.reserve(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any())).thenReturn(false);
        org.mockito.Mockito.when(quota.status(account)).thenReturn(new DocumentAiExplanationQuota.Status(0, 3, "2026-11-20T00:00:00+09:00"));
        assertThat(service.explain(id, java.util.UUID.randomUUID()).reason()).isEqualTo("QUOTA_EXHAUSTED");
        org.mockito.Mockito.verifyNoMoreInteractions(client);
        org.mockito.Mockito.when(quota.reserve(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any())).thenReturn(true);
        org.mockito.Mockito.doReturn(new DocumentClaimExplanationService.Context("비밀번호=abcd1234", java.util.UUID.randomUUID())).when(service).context(id, account);
        assertThat(service.explain(id, java.util.UUID.randomUUID()).status()).isEqualTo("FALLBACK");
        org.mockito.Mockito.verifyNoMoreInteractions(client);
        org.mockito.Mockito.doReturn(new DocumentClaimExplanationService.Context("합성 질문", java.util.UUID.randomUUID())).when(service).context(id, account);
        org.mockito.Mockito.when(client.generateStructured(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.eq(DocumentClaimExplanationService.GeneratedBatch.class), org.mockito.ArgumentMatchers.eq(2048L)))
                .thenThrow(new IllegalStateException("synthetic provider failure"));
        assertThat(service.explain(id, java.util.UUID.randomUUID()).reason()).isEqualTo("PROVIDER_ERROR");
        org.mockito.Mockito.when(snapshots.get(id)).thenReturn(current, new DocumentClaimSnapshotService.Response(id, "STALE", java.util.List.of()));
        assertThat(service.explain(id, java.util.UUID.randomUUID())).isEqualTo(new DocumentClaimExplanationService.Result("STALE", null));
    }

    @Test void anonymousUserCannotCallProvider() {
        var snapshots = org.mockito.Mockito.mock(DocumentClaimSnapshotService.class);
        var reviewer = org.mockito.Mockito.mock(com.financialhelper.source.DocumentClaimReviewerService.class);
        var accounts = org.mockito.Mockito.mock(com.financialhelper.account.AccountSessionService.class);
        var jdbc = org.mockito.Mockito.mock(org.springframework.jdbc.core.JdbcTemplate.class);
        @SuppressWarnings("unchecked") org.springframework.beans.factory.ObjectProvider<com.financialhelper.ai.OpenAiStructuredClient> clients = org.mockito.Mockito.mock(org.springframework.beans.factory.ObjectProvider.class);
        org.mockito.Mockito.when(accounts.currentAccountId()).thenReturn(java.util.Optional.empty());
        var service = new DocumentClaimExplanationService(snapshots, reviewer, accounts, jdbc, clients, tools.jackson.databind.json.JsonMapper.builder().build(), true, true, allowedQuota(), budget());
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.explain(java.util.UUID.randomUUID(), java.util.UUID.randomUUID()))
                .isInstanceOf(com.financialhelper.account.AccountAuthenticationException.class);
        org.mockito.Mockito.verifyNoInteractions(clients, snapshots);
    }

    @Test void disabledFeatureFallsBackWithoutProviderCall() {
        var snapshots = org.mockito.Mockito.mock(DocumentClaimSnapshotService.class);
        var reviewer = org.mockito.Mockito.mock(com.financialhelper.source.DocumentClaimReviewerService.class);
        var accounts = org.mockito.Mockito.mock(com.financialhelper.account.AccountSessionService.class);
        var jdbc = org.mockito.Mockito.mock(org.springframework.jdbc.core.JdbcTemplate.class);
        @SuppressWarnings("unchecked") org.springframework.beans.factory.ObjectProvider<com.financialhelper.ai.OpenAiStructuredClient> clients = org.mockito.Mockito.mock(org.springframework.beans.factory.ObjectProvider.class);
        var id = java.util.UUID.randomUUID(); var account = java.util.UUID.randomUUID();
        var item = new DocumentClaimSnapshotService.Evidence("a", java.util.UUID.randomUUID(), 1, "합성 원문", "기관", "문서", "https://example.invalid", "p1", null, "NOT_VERIFIED", java.util.List.of());
        var current = new DocumentClaimSnapshotService.Response(id, "READY_CANDIDATES", java.util.List.of(item));
        org.mockito.Mockito.when(accounts.currentAccountId()).thenReturn(java.util.Optional.of(account));
        org.mockito.Mockito.when(snapshots.get(id)).thenReturn(current);
        var service = new DocumentClaimExplanationService(snapshots, reviewer, accounts, jdbc, clients, tools.jackson.databind.json.JsonMapper.builder().build(), false, false, allowedQuota(), budget());
        assertThat(service.availability(null)).isEqualTo("FEATURE_DISABLED");
        assertThat(service.explain(id, java.util.UUID.randomUUID()).reason()).isEqualTo("FEATURE_DISABLED");
        org.mockito.Mockito.verifyNoInteractions(clients);
    }

    @Test void capturedSyntheticParaphraseReturnsAsProductExplanation() {
        var snapshots = org.mockito.Mockito.mock(DocumentClaimSnapshotService.class);
        var reviewer = org.mockito.Mockito.mock(com.financialhelper.source.DocumentClaimReviewerService.class);
        var accounts = org.mockito.Mockito.mock(com.financialhelper.account.AccountSessionService.class);
        var jdbc = org.mockito.Mockito.mock(org.springframework.jdbc.core.JdbcTemplate.class);
        @SuppressWarnings("unchecked") org.springframework.beans.factory.ObjectProvider<com.financialhelper.ai.OpenAiStructuredClient> clients = org.mockito.Mockito.mock(org.springframework.beans.factory.ObjectProvider.class);
        var client = org.mockito.Mockito.mock(com.financialhelper.ai.OpenAiStructuredClient.class);
        var id = java.util.UUID.randomUUID(); var account = java.util.UUID.randomUUID();
        var item = new DocumentClaimSnapshotService.Evidence("synthetic-1", java.util.UUID.randomUUID(), 1,
                "합성 메뉴는 화면에서 선택합니다.", "합성 기관", "합성 문서", "https://example.invalid", "p1", null, "NOT_VERIFIED", java.util.List.of());
        var current = new DocumentClaimSnapshotService.Response(id, "READY_CANDIDATES", java.util.List.of(item));
        org.mockito.Mockito.when(accounts.currentAccountId()).thenReturn(java.util.Optional.of(account));
        org.mockito.Mockito.when(snapshots.get(id)).thenReturn(current);
        org.mockito.Mockito.when(clients.getIfAvailable()).thenReturn(client);
        var service = org.mockito.Mockito.spy(new DocumentClaimExplanationService(snapshots, reviewer, accounts, jdbc, clients, tools.jackson.databind.json.JsonMapper.builder().build(), true, true, allowedQuota(), budget()));
        String question = "합성 메뉴는 어떻게 선택하나요?";
        org.mockito.Mockito.doReturn(new DocumentClaimExplanationService.Context(DocumentClaimSnapshotService.questionDigest(id, question), java.util.UUID.randomUUID())).when(service).context(id, account);
        org.mockito.Mockito.doReturn(true).when(service).eligible(id, current);
        org.mockito.Mockito.when(client.generateStructured(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.eq(DocumentClaimExplanationService.GeneratedBatch.class), org.mockito.ArgumentMatchers.eq(2048L)))
                .thenReturn(new DocumentClaimExplanationService.GeneratedBatch(java.util.List.of(new DocumentClaimExplanationService.Generated("synthetic-1", "합성 메뉴는 화면에서 골라 선택합니다."))));
        org.mockito.Mockito.when(client.generateStructured(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.eq(DocumentClaimExplanationService.SemanticBatch.class), org.mockito.ArgumentMatchers.eq(1024L)))
                .thenReturn(new DocumentClaimExplanationService.SemanticBatch(java.util.List.of(new DocumentClaimExplanationService.SemanticDecision("synthetic-1", "SUPPORTED"))));
        var result = service.explain(id, java.util.UUID.randomUUID(), question);
        assertThat(result.status()).isEqualTo("AI_EXPLANATION");
        assertThat(result.explanation()).contains("화면에서 골라 선택합니다", "출처: 합성 문서");
        var input = org.mockito.ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(client).generateStructured(org.mockito.ArgumentMatchers.anyString(), input.capture(), org.mockito.ArgumentMatchers.eq(DocumentClaimExplanationService.GeneratedBatch.class), org.mockito.ArgumentMatchers.eq(2048L));
        assertThat(input.getValue()).doesNotContain(question).contains("합성 메뉴는 화면에서 선택합니다.");
    }

    @Test
    void verifiesClaimIdentityAndNumbers() {
        var source = new DocumentClaimSnapshotService.Evidence("claim-a", java.util.UUID.randomUUID(), 1,
                "합성 상품은 2025년부터 신청할 수 있습니다.", "기관", "문서", "https://synthetic.invalid", "p1",
                java.time.LocalDate.of(2025, 1, 1), "NOT_VERIFIED", java.util.List.of());
        for (String valid : java.util.List.of("2025년부터 신청할 수 있어요.", "신청은 2025년부터 가능합니다.")) {
            assertThat(DocumentClaimExplanationService.validOutput(new DocumentClaimExplanationService.GeneratedBatch(
                    java.util.List.of(new DocumentClaimExplanationService.Generated("claim-a", valid))), java.util.List.of(source))).isTrue();
        }
        for (var invalid : java.util.List.of(
                new DocumentClaimExplanationService.Generated("claim-b", "2025년부터 신청할 수 있어요."),
                new DocumentClaimExplanationService.Generated("claim-a", "2026년부터 신청할 수 있어요."))) {
            assertThat(DocumentClaimExplanationService.validOutput(new DocumentClaimExplanationService.GeneratedBatch(java.util.List.of(invalid)), java.util.List.of(source))).isFalse();
        }
    }

    @Test void rejectsChangedAmountUnit() {
        var source = new DocumentClaimSnapshotService.Evidence("claim-a", java.util.UUID.randomUUID(), 1,
                "합성 금액은 5억 원입니다.", "기관", "문서", "https://synthetic.invalid", "p1",
                null, "NOT_VERIFIED", java.util.List.of());
        var changed = new DocumentClaimExplanationService.GeneratedBatch(java.util.List.of(
                new DocumentClaimExplanationService.Generated("claim-a", "합성 금액은 5원입니다.")));
        assertThat(DocumentClaimExplanationService.validOutput(changed, java.util.List.of(source))).isFalse();
        var equivalent = new DocumentClaimExplanationService.GeneratedBatch(java.util.List.of(
                new DocumentClaimExplanationService.Generated("claim-a", "합성 금액은 500,000,000원이에요.")));
        assertThat(DocumentClaimExplanationService.validOutput(equivalent, java.util.List.of(source))).isTrue();
    }

    @Test void objectiveChecksLeaveModalityAndExceptionsToSemanticVerifier() {
        var source = new DocumentClaimSnapshotService.Evidence("a", java.util.UUID.randomUUID(), 1,
                "합성 메뉴는 화면에서 선택할 수 있습니다.", "기관", "문서", "https://example.invalid", "p1", null, "NOT_VERIFIED", java.util.List.of());
        var paraphrase = new DocumentClaimExplanationService.GeneratedBatch(java.util.List.of(
                new DocumentClaimExplanationService.Generated("a", "합성 메뉴는 화면에서 골라 선택할 수 있어요.")));
        assertThat(DocumentClaimExplanationService.validOutput(paraphrase, java.util.List.of(source))).isTrue();
        var obligation = new DocumentClaimExplanationService.GeneratedBatch(java.util.List.of(
                new DocumentClaimExplanationService.Generated("a", "합성 메뉴는 화면에서 반드시 선택해야 합니다.")));
        assertThat(DocumentClaimExplanationService.validOutput(obligation, java.util.List.of(source))).isTrue();
        var withException = new DocumentClaimSnapshotService.Evidence("a", source.documentId(), 1,
                "합성 메뉴는 화면에서 선택할 수 있습니다.", "기관", "문서", "https://example.invalid", "p1", null, "NOT_VERIFIED",
                java.util.List.of(new DocumentClaimSnapshotService.Related("EXCEPTION", 0, 5, "기존 이용자는 제외")));
        assertThat(DocumentClaimExplanationService.validOutput(paraphrase, java.util.List.of(withException))).isTrue();
        var withCondition = new DocumentClaimSnapshotService.Evidence("a", source.documentId(), 1,
                "합성 메뉴는 화면에서 선택할 수 있습니다.", "기관", "문서", "https://example.invalid", "p1", null, "NOT_VERIFIED",
                java.util.List.of(new DocumentClaimSnapshotService.Related("CONDITION", 0, 5, "신청자는 성인이어야 합니다.")));
        assertThat(DocumentClaimExplanationService.validOutput(paraphrase, java.util.List.of(withCondition))).isTrue();
        var changedCondition = new DocumentClaimExplanationService.GeneratedBatch(java.util.List.of(
                new DocumentClaimExplanationService.Generated("a", "합성 메뉴는 화면에서 선택할 수 있고 신청자는 미성년자여야 합니다.")));
        assertThat(DocumentClaimExplanationService.validOutput(changedCondition, java.util.List.of(withCondition))).isTrue();
    }

    @Test void knownSemanticLimitationDoesNotProveEligibilityMeaning() {
        var source = new DocumentClaimSnapshotService.Evidence("a", java.util.UUID.randomUUID(), 1,
                "합성 상품은 성인에게 제공됩니다.", "기관", "문서", "https://example.invalid", "p1", null, "NOT_VERIFIED", java.util.List.of());
        var incorrect = new DocumentClaimExplanationService.GeneratedBatch(java.util.List.of(
                new DocumentClaimExplanationService.Generated("a", "합성 상품은 미성년자에게 제공됩니다.")));
        // This known false acceptance requires semantic evaluation, not another string-based guarantee.
        assertThat(DocumentClaimExplanationService.validOutput(incorrect, java.util.List.of(source))).isTrue();
    }

    @Test void acceptsEquivalentNumericDatesButRejectsChangedDates() {
        var source = new DocumentClaimSnapshotService.Evidence("a", java.util.UUID.randomUUID(), 1,
                "가상상품은 2025년 3월 1일부터 신청할 수 있습니다.", "기관", "문서", "https://example.invalid", "p1", null, "NOT_VERIFIED", java.util.List.of());
        var equivalent = new DocumentClaimExplanationService.GeneratedBatch(java.util.List.of(
                new DocumentClaimExplanationService.Generated("a", "가상상품은 2025-03-01부터 신청할 수 있어요.")));
        assertThat(DocumentClaimExplanationService.validOutput(equivalent, java.util.List.of(source))).isTrue();
        var changed = new DocumentClaimExplanationService.GeneratedBatch(java.util.List.of(
                new DocumentClaimExplanationService.Generated("a", "가상상품은 2025-03-02부터 신청할 수 있어요.")));
        assertThat(DocumentClaimExplanationService.validOutput(changed, java.util.List.of(source))).isFalse();
    }

    @Test void acceptsSyntheticProviderUncertaintyButNotUnsupportedCurrentAssertion() {
        var source = new DocumentClaimSnapshotService.Evidence("a", java.util.UUID.randomUUID(), 1,
                "가상상품은 1,000원부터 신청할 수 있습니다.", "합성 기관", "합성 문서", "https://example.invalid", "p1", null, "NOT_VERIFIED",
                java.util.List.of(new DocumentClaimSnapshotService.Related("CONDITION", 0, 1, "신청자는 성인이어야 합니다."),
                        new DocumentClaimSnapshotService.Related("EXCEPTION", 1, 2, "기존 계약은 제외됩니다.")));
        String explanation = "가상상품은 1,000원부터 신청할 수 있습니다. 신청자는 성인이어야 하며, 기존 계약은 제외됩니다. 다만 이 문서의 현재 적용 여부는 확인되지 않았습니다.";
        assertThat(DocumentClaimExplanationService.validOutput(new DocumentClaimExplanationService.GeneratedBatch(
                java.util.List.of(new DocumentClaimExplanationService.Generated("a", explanation))), java.util.List.of(source))).isTrue();
        String altered = explanation.replace("현재 적용 여부는 확인되지 않았습니다", "현재 누구나 신청할 수 있습니다");
        assertThat(DocumentClaimExplanationService.validOutput(new DocumentClaimExplanationService.GeneratedBatch(
                java.util.List.of(new DocumentClaimExplanationService.Generated("a", altered))), java.util.List.of(source))).isTrue();
    }

    @Test void checksDatesPeriodsPercentagesAndFutureVersusCurrent() {
        var source = new DocumentClaimSnapshotService.Evidence("a", java.util.UUID.randomUUID(), 1,
                "가상상품은 2025년 3월부터 12개월 동안 연 3%를 적용할 예정입니다.", "기관", "문서", "https://example.invalid", "p1", null, "NOT_VERIFIED", java.util.List.of());
        String valid = "가상상품은 2025년 3월부터 12개월 동안 연 3퍼센트를 적용할 예정이에요.";
        assertThat(DocumentClaimExplanationService.validOutput(new DocumentClaimExplanationService.GeneratedBatch(
                java.util.List.of(new DocumentClaimExplanationService.Generated("a", valid))), java.util.List.of(source))).isTrue();
        for (String incorrect : java.util.List.of(valid.replace("2025년", "2026년"), valid.replace("12개월", "13개월"),
                valid.replace("3퍼센트", "4퍼센트"))) {
            assertThat(DocumentClaimExplanationService.validOutput(new DocumentClaimExplanationService.GeneratedBatch(
                    java.util.List.of(new DocumentClaimExplanationService.Generated("a", incorrect))), java.util.List.of(source))).isFalse();
        }
        assertThat(DocumentClaimExplanationService.validOutput(new DocumentClaimExplanationService.GeneratedBatch(
                java.util.List.of(new DocumentClaimExplanationService.Generated("a", valid.replace("예정이에요", "확정됐어요")))), java.util.List.of(source))).isTrue();
    }

    @Test void allowsSimpleRewordingWithoutRequiringEverySourceWord() {
        var source = new DocumentClaimSnapshotService.Evidence("a", java.util.UUID.randomUUID(), 1,
                "가상상품은 온라인으로 신청할 수 있습니다.", "기관", "문서", "https://example.invalid", "p1", null, "NOT_VERIFIED", java.util.List.of());
        var output = new DocumentClaimExplanationService.GeneratedBatch(java.util.List.of(
                new DocumentClaimExplanationService.Generated("a", "가상상품은 인터넷에서 접수할 수 있어요.")));
        assertThat(DocumentClaimExplanationService.validOutput(output, java.util.List.of(source))).isTrue();
    }

    @Test void acceptsReorderedSentencesWithoutChangingMeaning() {
        var source = new DocumentClaimSnapshotService.Evidence("a", java.util.UUID.randomUUID(), 1,
                "가상상품은 2025년부터 신청할 수 있습니다. 신청은 온라인에서 진행합니다.", "기관", "문서", "https://example.invalid", "p1", null, "NOT_VERIFIED", java.util.List.of());
        var reordered = new DocumentClaimExplanationService.GeneratedBatch(java.util.List.of(
                new DocumentClaimExplanationService.Generated("a", "신청은 온라인에서 진행해요. 가상상품은 2025년부터 신청할 수 있어요.")));
        assertThat(DocumentClaimExplanationService.validOutput(reordered, java.util.List.of(source))).isTrue();
    }

    @Test void rejectsOtherClaimsAndOmittedClaims() {
        var first = new DocumentClaimSnapshotService.Evidence("a", java.util.UUID.randomUUID(), 1,
                "가상대출은 신청할 수 있습니다.", "기관", "문서", "https://example.invalid", "p1", null, "NOT_VERIFIED", java.util.List.of());
        var second = new DocumentClaimSnapshotService.Evidence("b", java.util.UUID.randomUUID(), 1,
                "다른저축은 이용할 수 있습니다.", "기관", "문서", "https://example.invalid", "p2", null, "NOT_VERIFIED", java.util.List.of());
        var mixed = new DocumentClaimExplanationService.GeneratedBatch(java.util.List.of(
                new DocumentClaimExplanationService.Generated("a", "다른저축은 신청할 수 있어요."),
                new DocumentClaimExplanationService.Generated("b", "다른저축은 이용할 수 있어요.")));
        assertThat(DocumentClaimExplanationService.validOutput(mixed, java.util.List.of(first, second))).isTrue();
        var omitted = new DocumentClaimExplanationService.GeneratedBatch(java.util.List.of(
                new DocumentClaimExplanationService.Generated("a", "가상대출은 신청할 수 있어요.")));
        assertThat(DocumentClaimExplanationService.validOutput(omitted, java.util.List.of(first, second))).isFalse();
    }
}
