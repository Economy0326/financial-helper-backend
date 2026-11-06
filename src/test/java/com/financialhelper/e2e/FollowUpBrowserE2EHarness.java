package com.financialhelper.e2e;

import com.financialhelper.FinancialHelperBackendApplication;
import com.financialhelper.procedure.ProcedureStatus;
import com.financialhelper.procedure.ProcedureVersion;
import com.financialhelper.procedure.ProcedureVersionRepository;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** Test-classpath-only HTTP server. The console controls fixtures; no fixture HTTP endpoint exists. */
public final class FollowUpBrowserE2EHarness {
    private static final String ACCOUNT_SCENARIO = "UNAUTHORIZED_ACCOUNT_TRANSFER";
    private static final String ACCOUNT_INSTITUTION = "GENERIC_FINANCIAL_INSTITUTION";
    private static final String ACCOUNT_PRODUCT = "BANK_ACCOUNT";

    private FollowUpBrowserE2EHarness() {}

    public static void main(String[] args) throws Exception {
        Guard guard = guard(System.getenv());
        verifyContainer(guard);
        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(
                FinancialHelperBackendApplication.class, BrowserE2EAiStubConfig.class)
                .profiles("browser-e2e")
                .initializers(application -> {
                    var environment = application.getEnvironment();
                    if (!Set.of(environment.getActiveProfiles()).equals(Set.of("browser-e2e"))
                            || !guard.jdbcUrl().equals(environment.getProperty("spring.datasource.url"))
                            || !"127.0.0.1".equals(environment.getProperty("server.address"))
                            || !"8080".equals(environment.getProperty("server.port"))) {
                        throw new IllegalStateException("Browser E2E profile, database, or server bind mismatch");
                    }
                    for (String disabled : List.of("app.auth.kakao.enabled", "app.auth.naver.enabled",
                            "app.law-open-api.enabled", "app.source-ingestion.run-on-startup",
                            "app.card-corpus.run-on-startup", "app.breadth-corpus.run-on-startup",
                            "app.ai.smoke-test-enabled")) {
                        if (!"false".equalsIgnoreCase(environment.getProperty(disabled))) {
                            throw new IllegalStateException("External integration must remain disabled: " + disabled);
                        }
                    }
                    if (!"true".equalsIgnoreCase(environment.getProperty("app.ai.enabled"))
                            || !"browser-e2e-stub-only".equals(environment.getProperty("app.ai.openai.api-key"))) {
                        throw new IllegalStateException("Browser E2E AI stub configuration mismatch");
                    }
                }).run(args)) {
            JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);
            for (String version : List.of("36", "37")) {
                Integer applied = jdbc.queryForObject(
                        "select count(*) from flyway_schema_history where version = ? and success = true",
                        Integer.class, version);
                if (applied == null || applied != 1) {
                    throw new IllegalStateException("Required Flyway migration is not applied: V" + version);
                }
            }
            String database = jdbc.queryForObject("select current_database()", String.class);
            if (!guard.databaseName().equals(database)) {
                throw new IllegalStateException("Connected database identity changed");
            }
            System.out.println("BROWSER_E2E_READY run=" + guard.runId()
                    + " database=" + database + " migrations=V36,V37");
            commands(context, jdbc, guard);
        }
    }

    static Guard guard(Map<String, String> environment) {
        String runId = required(environment, "E2E_RUN_ID");
        if (!runId.matches("[a-z][a-z0-9]{7,23}")) {
            throw new IllegalArgumentException("E2E_RUN_ID must be 8-24 lower-case alphanumeric characters");
        }
        String url = required(environment, "E2E_DB_URL");
        if (!url.startsWith("jdbc:postgresql://")) {
            throw new IllegalArgumentException("E2E_DB_URL must be PostgreSQL JDBC");
        }
        URI parsed;
        try {
            parsed = URI.create(url.substring("jdbc:".length()));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("E2E_DB_URL is malformed", exception);
        }
        String database = "financial_helper_e2e_" + runId;
        if (!"postgresql".equals(parsed.getScheme()) || !"127.0.0.1".equals(parsed.getHost())
                || parsed.getPort() < 1024 || parsed.getPort() > 65535
                || !('/' + database).equals(parsed.getPath())
                || parsed.getRawQuery() != null || parsed.getRawFragment() != null
                || parsed.getUserInfo() != null) {
            throw new IllegalArgumentException("E2E_DB_URL must target this run's loopback database");
        }
        for (String existing : List.of("DB_URL", "TEST_DB_URL", "SPRING_DATASOURCE_URL")) {
            if (url.equals(environment.get(existing))) {
                throw new IllegalArgumentException("E2E_DB_URL must differ from " + existing);
            }
        }
        if (environment.get("SPRING_DATASOURCE_URL") != null) {
            throw new IllegalArgumentException("SPRING_DATASOURCE_URL override is forbidden");
        }
        if (!"browser-e2e".equals(required(environment, "SPRING_PROFILES_ACTIVE"))) {
            throw new IllegalArgumentException("Only the browser-e2e Spring profile is allowed");
        }
        required(environment, "E2E_DB_USERNAME");
        required(environment, "E2E_DB_PASSWORD");
        String containerId = required(environment, "E2E_CONTAINER_ID");
        if (!containerId.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("E2E_CONTAINER_ID must be a full Docker container ID");
        }
        return new Guard(runId, url, database, parsed.getPort(), containerId,
                "fh-o03b-e2e-" + runId);
    }

    private static String required(Map<String, String> environment, String key) {
        String value = environment.get(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(key + " is required");
        }
        return value;
    }

    /** Reuse the browser isolation guard before Spring can create a DataSource. */
    public static void verifySpringEnvironment(org.springframework.core.env.Environment environment) {
        try {
            if (!Set.of(environment.getActiveProfiles()).equals(Set.of("browser-e2e"))) throw new IllegalStateException();
            Guard current = guard(System.getenv());
            verifyContainer(current);
            String user = required(System.getenv(), "E2E_DB_USERNAME");
            String password = required(System.getenv(), "E2E_DB_PASSWORD");
            if (password.length() < 24 || !current.jdbcUrl().equals(environment.getProperty("spring.datasource.url"))
                    || !user.equals(environment.getProperty("spring.datasource.username"))
                    || !password.equals(environment.getProperty("spring.datasource.password"))) throw new IllegalStateException();
            String raw = inspect("database-config", "inspect", "--format={{json .Config.Env}}", current.containerId());
            List<?> values = JsonMapper.builder().build().readValue(raw, List.class);
            if (!values.contains("POSTGRES_DB=" + current.databaseName()) || !values.contains("POSTGRES_USER=" + user)
                    || !values.contains("POSTGRES_PASSWORD=" + password)) throw new IllegalStateException();
        } catch (Exception exception) {
            throw new IllegalStateException("Browser E2E Spring database isolation could not be verified");
        }
    }
    static void verifyContainer(Guard guard) throws Exception {
        String id = inspect("container-id", "inspect", "--format={{.Id}}", guard.containerId());
        String name = inspect("container-name", "inspect", "--format={{.Name}}", guard.containerId());
        String running = inspect("running-state", "inspect", "--format={{.State.Running}}", guard.containerId());
        String mounts = inspect("mount-types", "inspect", "--format={{range .Mounts}}{{.Type}} {{end}}", guard.containerId());
        String tmpfs = parseTmpfsConfig(inspect("tmpfs-config", "inspect",
                "--format={{json .HostConfig.Tmpfs}}", guard.containerId()));
        String label = parseRunLabel(inspect("run-label", "inspect",
                "--format={{json .Config.Labels}}", guard.containerId()));
        String ports = inspect("loopback-port", "port", guard.containerId(), "5432/tcp");
        String mountTable = inspect("actual-tmpfs", "exec", guard.containerId(), "cat", "/proc/mounts");
        validateContainerIdentity(guard, id, name, running, mounts, tmpfs, mountTable, label, ports);
    }

    static void validateContainerIdentity(Guard guard, String id, String name, String running,
            String mounts, String tmpfs, String mountTable, String label, String ports) {
        boolean dataPathIsTmpfs = mountTable.lines()
                .map(line -> line.split("\\s+"))
                .anyMatch(fields -> fields.length >= 3 && "/var/lib/postgresql/data".equals(fields[1])
                        && "tmpfs".equals(fields[2]));
        if (!guard.containerId().equals(id) || !('/' + guard.containerName()).equals(name)
                || !"true".equals(running)
                || (!mounts.isBlank() && !mounts.trim().matches("tmpfs(?:\\s+tmpfs)*"))
                || tmpfs.isBlank() || "<no value>".equals(tmpfs) || !dataPathIsTmpfs
                || !guard.runId().equals(label)
                || !ports.lines().toList().equals(List.of("127.0.0.1:" + guard.port()))) {
            throw new IllegalStateException("Docker container identity, isolation, or port mapping mismatch");
        }
    }

    private static String inspect(String stage, String operation, String... arguments) throws Exception {
        String[] command = new String[arguments.length + 2];
        command[0] = "docker";
        command[1] = operation;
        System.arraycopy(arguments, 0, command, 2, arguments.length);
        Process process;
        try {
            process = new ProcessBuilder(command).redirectErrorStream(true).start();
        } catch (IOException exception) {
            throw new IllegalStateException(dockerStartFailure(operation, stage, exception));
        }
        if (!process.waitFor(8, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IllegalStateException("Docker " + operation + " stage=" + stage + " reason=TIMEOUT");
        }
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        if (process.exitValue() != 0) {
            throw new IllegalStateException(dockerFailure(operation, stage, process.exitValue(), output));
        }
        return output;
    }

    static String dockerFailure(String operation, String stage, int exitCode, String output) {
        String lower = output.toLowerCase(java.util.Locale.ROOT);
        String reason = lower.contains("access is denied") || lower.contains("permission denied")
                || lower.contains("cannot connect to the docker daemon") || lower.contains("error during connect")
                ? "ENGINE_ACCESS_FAILED"
                : lower.contains("template parsing error") || lower.contains("can't evaluate field")
                ? "FORMAT_REJECTED"
                : lower.contains("no such container") ? "CONTAINER_NOT_FOUND" : "DOCKER_COMMAND_FAILED";
        return "Docker " + operation + " stage=" + stage + " exit=" + exitCode + " reason=" + reason;
    }

    static String dockerStartFailure(String operation, String stage, IOException exception) {
        String reason = exception.getMessage() != null && exception.getMessage().contains("CreateProcess error=2")
                ? "EXECUTABLE_NOT_FOUND" : "PROCESS_START_FAILED";
        return "Docker " + operation + " stage=" + stage + " reason=" + reason;
    }

    static String parseTmpfsConfig(String output) {
        return parseJsonMapValue("tmpfs-config", output, "/var/lib/postgresql/data");
    }

    static String parseRunLabel(String output) {
        return parseJsonMapValue("run-label", output, "financial-helper.e2e.run");
    }

    private static String parseJsonMapValue(String stage, String output, String key) {
        try {
            var root = JsonMapper.builder().build().readTree(output);
            if (root == null || !root.isObject()) {
                throw new IllegalStateException("Docker inspect stage=" + stage + " reason=INVALID_RESPONSE");
            }
            var value = root.get(key);
            return value != null && value.isString() ? value.asText() : "";
        } catch (IllegalStateException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException("Docker inspect stage=" + stage + " reason=INVALID_RESPONSE");
        }
    }

    private static void commands(ConfigurableApplicationContext context, JdbcTemplate jdbc,
            Guard guard) throws IOException {
        ProcedureVersionRepository versions = context.getBean(ProcedureVersionRepository.class);
        BufferedReader input = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        System.out.println("Commands: auth-browser | seed-card | card-browser | overlap-account | supersede <consultation-id> <question-id> | inspect <consultation-id> | exit");
        String line;
        while ((line = input.readLine()) != null) {
            String[] parts = line.trim().split("\\s+");
            try {
                switch (parts[0]) {
                    case "auth-browser" -> BrowserE2EAccountAuthFixture.verify(context, jdbc, guard);
                    case "seed-card" -> BrowserE2ECardFixture.seed(context, jdbc, guard);
                    case "card-browser" -> BrowserE2ECardFlow.run(context, jdbc, guard);
                    case "overlap-account" -> overlapAccount(versions, guard);
                    case "supersede" -> {
                        if (parts.length != 3) throw new IllegalArgumentException("Two IDs required");
                        int changed = jdbc.update("update follow_up_question set superseded_at = current_timestamp "
                                + "where consultation_id = ? and id = ? and question_scope = 'VERSION_BOUND' "
                                + "and superseded_at is null", UUID.fromString(parts[1]), UUID.fromString(parts[2]));
                        if (changed != 1) throw new IllegalArgumentException("One active VERSION_BOUND question required");
                        System.out.println("QUESTION_SUPERSEDED");
                    }
                    case "inspect" -> {
                        if (parts.length != 2) throw new IllegalArgumentException("Consultation ID required");
                        UUID consultationId = UUID.fromString(parts[1]);
                        String step = jdbc.queryForObject("select current_step from consultation where id = ?",
                                String.class, consultationId);
                        Integer superseded = jdbc.queryForObject("select count(*) from follow_up_question "
                                + "where consultation_id = ? and superseded_at is not null", Integer.class,
                                consultationId);
                        System.out.println("CONSULTATION step=" + step + " supersededQuestions=" + superseded);
                    }
                    case "exit" -> { return; }
                    default -> System.out.println("UNKNOWN_COMMAND");
                }
            } catch (Exception exception) {
                String reason = exception.getMessage();
                System.out.println("FIXTURE_COMMAND_REJECTED " + exception.getClass().getSimpleName()
                        + (reason != null && reason.matches("E2E_[A-Z0-9_]+") ? " " + reason : ""));
            }
        }
    }

    private static void overlapAccount(ProcedureVersionRepository versions, Guard guard) {
        List<ProcedureVersion> approved = versions.findByScenarioAndInstitutionAndProductTypeAndStatus(
                ACCOUNT_SCENARIO, ACCOUNT_INSTITUTION, ACCOUNT_PRODUCT, ProcedureStatus.APPROVED);
        if (approved.stream().anyMatch(version -> ("browser-e2e:" + guard.runId())
                .equals(version.getReviewNotes()))) {
            System.out.println("OVERLAP_ALREADY_PRESENT");
            return;
        }
        if (approved.size() != 1) {
            throw new IllegalStateException("Exactly one seed ACCOUNT version required");
        }
        ProcedureVersion source = approved.getFirst();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        ProcedureVersion overlap = new ProcedureVersion(source.getScenario(), source.getInstitution(),
                source.getProductType(), source.getVersion() + 1, ProcedureStatus.APPROVED,
                source.getApplicabilityStartDate(), source.getApplicabilityEndDate(),
                source.getRequiredFactsJson(), source.getConditionRulesJson(), source.getActionStepsJson(),
                source.getDocumentRequirementsJson(), source.getEvidenceReferencesJson(),
                source.getReviewedContactsJson(), source.getReviewedValuesJson(),
                source.getConditionsExceptionsJson(), "browser-e2e:" + guard.runId(),
                "browser-e2e-fixture", now, now);
        versions.saveAndFlush(overlap);
        System.out.println("OVERLAP_READY id=" + overlap.getId());
    }

    record Guard(String runId, String jdbcUrl, String databaseName, int port, String containerId,
            String containerName) {}
}
