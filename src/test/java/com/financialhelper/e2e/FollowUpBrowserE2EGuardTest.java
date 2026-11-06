package com.financialhelper.e2e;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FollowUpBrowserE2EGuardTest {
    private static final String RUN = "runabc12";
    private static final String URL = "jdbc:postgresql://127.0.0.1:55432/financial_helper_e2e_" + RUN;

    @Test
    void acceptsOnlyRunScopedLoopbackDatabaseAndExplicitProfile() {
        var guard = FollowUpBrowserE2EHarness.guard(environment());
        assertThat(guard.databaseName()).isEqualTo("financial_helper_e2e_" + RUN);
        assertThat(guard.port()).isEqualTo(55432);
    }

    @Test
    void rejectsDevelopmentSharedAndRemoteDatabasesBeforeSpringStarts() {
        for (String url : new String[]{"", "jdbc:postgresql://127.0.0.1:5432/financial_helper",
                "jdbc:postgresql://127.0.0.1:5432/financial_helper_test",
                "jdbc:postgresql://example.com:55432/financial_helper_e2e_" + RUN,
                "jdbc:postgresql://127.0.0.1:55432/financial_helper_e2e_other"}) {
            var values = environment();
            values.put("E2E_DB_URL", url);
            assertThatThrownBy(() -> FollowUpBrowserE2EHarness.guard(values)).isInstanceOf(RuntimeException.class);
        }
    }

    @Test
    void rejectsDatasourceOverrideProfileFallbackAndMissingContainerIdentity() {
        var override = environment();
        override.put("SPRING_DATASOURCE_URL", URL);
        assertThatThrownBy(() -> FollowUpBrowserE2EHarness.guard(override)).isInstanceOf(RuntimeException.class);
        var localProfile = environment();
        localProfile.put("SPRING_PROFILES_ACTIVE", "local");
        assertThatThrownBy(() -> FollowUpBrowserE2EHarness.guard(localProfile)).isInstanceOf(RuntimeException.class);
        var missingContainer = environment();
        missingContainer.remove("E2E_CONTAINER_ID");
        assertThatThrownBy(() -> FollowUpBrowserE2EHarness.guard(missingContainer)).isInstanceOf(RuntimeException.class);
    }

    @Test
    void validatesConfiguredAndActualTmpfsWithoutRequiringMountsEntry() {
        var guard = FollowUpBrowserE2EHarness.guard(environment());
        String id = "a".repeat(64);
        String name = "/fh-o03b-e2e-" + RUN;
        FollowUpBrowserE2EHarness.validateContainerIdentity(guard, id, name, "true",
                "", "rw", "tmpfs /var/lib/postgresql/data tmpfs rw 0 0", RUN, "127.0.0.1:55432");
        assertThatThrownBy(() -> FollowUpBrowserE2EHarness.validateContainerIdentity(
                guard, id, name, "true", "", "rw", "overlay /var/lib/postgresql/data overlay rw 0 0",
                RUN, "127.0.0.1:55432"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> FollowUpBrowserE2EHarness.validateContainerIdentity(
                guard, id, name, "true", "", "<no value>",
                "tmpfs /var/lib/postgresql/data tmpfs rw 0 0", RUN, "127.0.0.1:55432"))
                .isInstanceOf(IllegalStateException.class);
        for (String persistentMount : new String[]{"bind", "volume"}) {
            assertThatThrownBy(() -> FollowUpBrowserE2EHarness.validateContainerIdentity(
                    guard, id, name, "true", persistentMount, "rw",
                    "tmpfs /var/lib/postgresql/data tmpfs rw 0 0", RUN, "127.0.0.1:55432"))
                    .isInstanceOf(IllegalStateException.class);
        }
        assertThatThrownBy(() -> FollowUpBrowserE2EHarness.validateContainerIdentity(
                guard, "b".repeat(64), name, "true", "", "rw",
                "tmpfs /var/lib/postgresql/data tmpfs rw 0 0", RUN, "127.0.0.1:55432"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> FollowUpBrowserE2EHarness.validateContainerIdentity(
                guard, id, name, "true", "", "rw",
                "tmpfs /var/lib/postgresql/data tmpfs rw 0 0", "wrong", "127.0.0.1:55432"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> FollowUpBrowserE2EHarness.validateContainerIdentity(
                guard, id, name, "true", "", "rw",
                "tmpfs /var/lib/postgresql/data tmpfs rw 0 0", RUN, "0.0.0.0:55432"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void classifiesDockerFailureWithoutExposingRawOutput() {
        String secret = "private-credential-marker";
        String denied = FollowUpBrowserE2EHarness.dockerFailure("inspect", "tmpfs-config", 1,
                "error during connect: Access is denied " + secret);
        assertThat(denied).contains("inspect", "stage=tmpfs-config", "exit=1", "ENGINE_ACCESS_FAILED")
                .doesNotContain(secret, "error during connect");
        assertThat(FollowUpBrowserE2EHarness.dockerFailure("inspect", "run-label", 2,
                "template parsing error: " + secret))
                .contains("stage=run-label", "exit=2", "FORMAT_REJECTED").doesNotContain(secret);
        assertThat(FollowUpBrowserE2EHarness.dockerFailure("exec", "actual-tmpfs", 3,
                "unexpected " + secret))
                .contains("stage=actual-tmpfs", "exit=3", "DOCKER_COMMAND_FAILED").doesNotContain(secret);
    }

    @Test
    void distinguishesMissingExecutableFromOtherProcessStartFailures() {
        String secret = "private-credential-marker";
        assertThat(FollowUpBrowserE2EHarness.dockerStartFailure("inspect", "container-id",
                new IOException("CreateProcess error=2 " + secret)))
                .contains("stage=container-id", "EXECUTABLE_NOT_FOUND").doesNotContain(secret);
        assertThat(FollowUpBrowserE2EHarness.dockerStartFailure("exec", "actual-tmpfs",
                new IOException("Unexpected process failure " + secret)))
                .contains("stage=actual-tmpfs", "PROCESS_START_FAILED").doesNotContain(secret);
    }

    @Test
    void parsesDockerTmpfsJsonAndRejectsMalformedOutputWithoutEchoingIt() {
        assertThat(FollowUpBrowserE2EHarness.parseTmpfsConfig(
                "{\"/var/lib/postgresql/data\":\"rw\"}")).isEqualTo("rw");
        assertThat(FollowUpBrowserE2EHarness.parseTmpfsConfig("{}")).isEmpty();
        assertThatThrownBy(() -> FollowUpBrowserE2EHarness.parseTmpfsConfig("null"))
                .isInstanceOf(IllegalStateException.class);
        String secret = "private-credential-marker";
        assertThatThrownBy(() -> FollowUpBrowserE2EHarness.parseTmpfsConfig("bad " + secret))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("stage=tmpfs-config reason=INVALID_RESPONSE")
                .hasMessageNotContaining(secret);
    }

    @Test
    void parsesRunLabelJsonAndRejectsMissingOrMalformedValues() {
        assertThat(FollowUpBrowserE2EHarness.parseRunLabel(
                "{\"financial-helper.e2e.run\":\"" + RUN + "\"}"))
                .isEqualTo(RUN);
        assertThat(FollowUpBrowserE2EHarness.parseRunLabel("{}")).isEmpty();
        assertThat(FollowUpBrowserE2EHarness.parseRunLabel(
                "{\"financial-helper.e2e.run\":null}")).isEmpty();
        String secret = "private-credential-marker";
        assertThatThrownBy(() -> FollowUpBrowserE2EHarness.parseRunLabel("bad " + secret))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("stage=run-label reason=INVALID_RESPONSE")
                .hasMessageNotContaining(secret);
    }

    private static Map<String, String> environment() {
        Map<String, String> values = new HashMap<>();
        values.put("E2E_RUN_ID", RUN);
        values.put("E2E_DB_URL", URL);
        values.put("E2E_DB_USERNAME", "fixture");
        values.put("E2E_DB_PASSWORD", "fixture-only");
        values.put("E2E_CONTAINER_ID", "a".repeat(64));
        values.put("SPRING_PROFILES_ACTIVE", "browser-e2e");
        values.put("DB_URL", "jdbc:postgresql://localhost:5432/financial_helper");
        values.put("TEST_DB_URL", "jdbc:postgresql://localhost:5432/financial_helper_test");
        return values;
    }
}
