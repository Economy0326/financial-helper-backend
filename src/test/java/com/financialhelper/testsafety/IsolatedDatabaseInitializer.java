package com.financialhelper.testsafety;

import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.Environment;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/** Refuse Spring test contexts before any DataSource or Flyway bean can be created. */
public class IsolatedDatabaseInitializer implements ApplicationContextInitializer<ConfigurableApplicationContext> {
    @Override
    public void initialize(ConfigurableApplicationContext context) {
        context.addBeanFactoryPostProcessor(factory -> verify(context.getEnvironment()));
    }

    static void verify(Environment env) {
        try {
            String run = System.getenv("FH_TEST_RUN_ID");
            String id = System.getenv("FH_TEST_CONTAINER_ID");
            if (run == null || !run.matches("[0-9a-f]{32}") || id == null || !id.matches("[0-9a-f]{64}")) fail();
            Process process = new ProcessBuilder("docker", "inspect", id).redirectErrorStream(true).start();
            var output = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                try { return process.getInputStream().readAllBytes(); }
                catch (java.io.IOException exception) { throw new IllegalStateException(exception); }
            });
            if (!process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)) { process.destroyForcibly(); fail(); }
            if (process.exitValue() != 0) fail();
            String json = new String(output.get(2, java.util.concurrent.TimeUnit.SECONDS), StandardCharsets.UTF_8);
            List<?> results = JsonMapper.builder().build().readValue(json, List.class);
            Map<?, ?> container = (Map<?, ?>) results.getFirst();
            Map<?, ?> config = (Map<?, ?>) container.get("Config");
            Map<?, ?> host = (Map<?, ?>) container.get("HostConfig");
            Map<?, ?> labels = (Map<?, ?>) config.get("Labels");
            Map<?, ?> state = (Map<?, ?>) container.get("State");
            Map<?, ?> tmpfs = (Map<?, ?>) host.get("Tmpfs");
            Map<?, ?> network = (Map<?, ?>) container.get("NetworkSettings");
            Map<?, ?> ports = (Map<?, ?>) network.get("Ports");
            List<?> bindings = (List<?>) ports.get("5432/tcp");
            Map<?, ?> binding = (Map<?, ?>) bindings.getFirst();
            String db = "fh_test_" + run;
            String user = "fh_user_" + run.substring(0, 12);
            List<?> vars = (List<?>) config.get("Env");
            String password = env.getProperty("spring.datasource.password");
            if (!id.equals(container.get("Id")) || !("/fh-isolated-" + run).equals(container.get("Name"))
                    || labels == null || !run.equals(labels.get("financial-helper.test-run"))
                    || !Boolean.TRUE.equals(state.get("Running")) || !"postgres:16-alpine".equals(config.get("Image"))
                    || tmpfs == null || !tmpfs.containsKey("/var/lib/postgresql/data")
                    || !((List<?>) container.get("Mounts")).isEmpty() || bindings.size() != 1
                    || !"127.0.0.1".equals(binding.get("HostIp"))
                    || !("jdbc:postgresql://127.0.0.1:" + binding.get("HostPort") + "/" + db)
                            .equals(env.getProperty("spring.datasource.url"))
                    || !user.equals(env.getProperty("spring.datasource.username"))
                    || password == null || password.length() < 24
                    || !vars.contains("POSTGRES_DB=" + db) || !vars.contains("POSTGRES_USER=" + user)
                    || !vars.contains("POSTGRES_PASSWORD=" + password)) fail();
        } catch (Exception exception) {
            throw new IllegalStateException("Spring test database isolation could not be verified", exception instanceof InterruptedException ? exception : null);
        }
    }

    private static void fail() { throw new IllegalStateException("Spring test database isolation could not be verified"); }
}
