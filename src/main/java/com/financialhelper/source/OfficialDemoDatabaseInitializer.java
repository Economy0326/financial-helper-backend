package com.financialhelper.source;

import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.Environment;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/** Fail closed before DataSource/Flyway when the dedicated demo profile is selected. */
public class OfficialDemoDatabaseInitializer implements ApplicationContextInitializer<ConfigurableApplicationContext> {
    @Override
    public void initialize(ConfigurableApplicationContext context) {
        if (java.util.List.of(context.getEnvironment().getActiveProfiles()).stream()
                .anyMatch(profile -> profile.equals("official-demo") || profile.equals("official-local-demo"))) {
            context.addBeanFactoryPostProcessor(factory -> verify(context.getEnvironment()));
        }
    }

    public static void verify(Environment env) {
        try {
            if ("persistent".equals(env.getProperty("FH_DEMO_STORAGE_MODE"))) {
                verifyPersistent(env);
                return;
            }
            String run = env.getProperty("FH_DEMO_RUN_ID");
            String id = env.getProperty("FH_DEMO_CONTAINER_ID");
            if (run == null || !run.matches("[a-f0-9]{32}") || id == null || !id.matches("[a-f0-9]{64}")) fail();
            Process process = new ProcessBuilder("docker", "inspect", id).redirectErrorStream(true).start();
            var output = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                try { return process.getInputStream().readAllBytes(); }
                catch (java.io.IOException exception) { throw new IllegalStateException(exception); }
            });
            if (!process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)) { process.destroyForcibly(); fail(); }
            if (process.exitValue() != 0) fail();
            List<?> results = JsonMapper.builder().build().readValue(
                    new String(output.get(2, java.util.concurrent.TimeUnit.SECONDS), StandardCharsets.UTF_8), List.class);
            Map<?, ?> container = (Map<?, ?>) results.getFirst();
            Map<?, ?> config = (Map<?, ?>) container.get("Config");
            Map<?, ?> host = (Map<?, ?>) container.get("HostConfig");
            Map<?, ?> labels = (Map<?, ?>) config.get("Labels");
            Map<?, ?> state = (Map<?, ?>) container.get("State");
            Map<?, ?> tmpfs = (Map<?, ?>) host.get("Tmpfs");
            Map<?, ?> ports = (Map<?, ?>) ((Map<?, ?>) container.get("NetworkSettings")).get("Ports");
            List<?> bindings = (List<?>) ports.get("5432/tcp");
            Map<?, ?> binding = (Map<?, ?>) bindings.getFirst();
            String db = "fh_official_demo_" + run;
            String user = "fh_demo_" + run.substring(0, 12);
            String password = env.getProperty("FH_DEMO_DB_PASSWORD");
            String expectedUrl = "jdbc:postgresql://127.0.0.1:" + binding.get("HostPort") + "/" + db;
            List<?> vars = (List<?>) config.get("Env");
            if (!id.equals(container.get("Id")) || !("/fh-official-demo-" + run).equals(container.get("Name"))
                    || !run.equals(labels.get("financial-helper.official-demo.run"))
                    || !Boolean.TRUE.equals(state.get("Running")) || !"postgres:16-alpine".equals(config.get("Image"))
                    || tmpfs == null || !tmpfs.containsKey("/var/lib/postgresql/data")
                    || !((List<?>) container.get("Mounts")).isEmpty() || bindings.size() != 1
                    || !"127.0.0.1".equals(binding.get("HostIp"))
                    || !expectedUrl.equals(env.getProperty("spring.datasource.url"))
                    || !expectedUrl.equals(env.getProperty("FH_DEMO_DB_URL"))
                    || !user.equals(env.getProperty("spring.datasource.username"))
                    || password == null || password.length() < 24
                    || !password.equals(env.getProperty("spring.datasource.password"))
                    || !vars.contains("POSTGRES_DB=" + db) || !vars.contains("POSTGRES_USER=" + user)
                    || !vars.contains("POSTGRES_PASSWORD=" + password)) fail();
        } catch (Exception exception) {
            throw new IllegalStateException("Official demo database isolation could not be verified");
        }
    }

    private static void verifyPersistent(Environment env) throws Exception {
        String id = env.getProperty("FH_DEMO_CONTAINER_ID");
        String owner = env.getProperty("FH_DEMO_OWNER_ID");
        if (id == null || !id.matches("[a-f0-9]{64}") || owner == null || !owner.matches("[a-f0-9]{32}")) fail();
        Process process = new ProcessBuilder("docker", "inspect", id).redirectErrorStream(true).start();
        byte[] output = process.getInputStream().readAllBytes();
        if (!process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS) || process.exitValue() != 0) fail();
        List<?> results = JsonMapper.builder().build().readValue(new String(output, StandardCharsets.UTF_8), List.class);
        Map<?, ?> container = (Map<?, ?>) results.getFirst();
        Map<?, ?> config = (Map<?, ?>) container.get("Config");
        Map<?, ?> host = (Map<?, ?>) container.get("HostConfig");
        Map<?, ?> labels = (Map<?, ?>) config.get("Labels");
        Map<?, ?> state = (Map<?, ?>) container.get("State");
        Map<?, ?> ports = (Map<?, ?>) ((Map<?, ?>) container.get("NetworkSettings")).get("Ports");
        List<?> bindings = (List<?>) ports.get("5432/tcp");
        Map<?, ?> binding = (Map<?, ?>) bindings.getFirst();
        List<?> mounts = (List<?>) container.get("Mounts");
        Map<?, ?> mount = mounts.size() == 1 ? (Map<?, ?>) mounts.getFirst() : Map.of();
        String db = "fh_official_local_demo";
        String user = "fh_official_demo";
        String password = env.getProperty("FH_DEMO_DB_PASSWORD");
        String url = "jdbc:postgresql://127.0.0.1:" + binding.get("HostPort") + "/" + db;
        List<?> vars = (List<?>) config.get("Env");
        Process volumeProcess = new ProcessBuilder("docker", "volume", "inspect", "fh-official-local-demo-data")
                .redirectErrorStream(true).start();
        byte[] volumeOutput = volumeProcess.getInputStream().readAllBytes();
        if (!volumeProcess.waitFor(10, java.util.concurrent.TimeUnit.SECONDS) || volumeProcess.exitValue() != 0) fail();
        List<?> volumes = JsonMapper.builder().build().readValue(
                new String(volumeOutput, StandardCharsets.UTF_8), List.class);
        Map<?, ?> volume = (Map<?, ?>) volumes.getFirst();
        Map<?, ?> volumeLabels = (Map<?, ?>) volume.get("Labels");
        if (!id.equals(container.get("Id")) || !"/fh-official-local-demo".equals(container.get("Name"))
                || !owner.equals(labels.get("financial-helper.official-local-demo.owner"))
                || !"fh-official-local-demo-data".equals(volume.get("Name"))
                || !"local".equals(volume.get("Driver"))
                || volumeLabels == null || !owner.equals(volumeLabels.get("financial-helper.official-local-demo.owner"))
                || !Boolean.TRUE.equals(state.get("Running")) || !"postgres:16-alpine".equals(config.get("Image"))
                || (host.get("Tmpfs") != null && !((Map<?, ?>) host.get("Tmpfs")).isEmpty()) || mounts.size() != 1
                || !"volume".equals(mount.get("Type")) || !"fh-official-local-demo-data".equals(mount.get("Name"))
                || !"/var/lib/postgresql/data".equals(mount.get("Destination"))
                || bindings.size() != 1 || !"127.0.0.1".equals(binding.get("HostIp"))
                || !url.equals(env.getProperty("spring.datasource.url"))
                || !url.equals(env.getProperty("FH_DEMO_DB_URL"))
                || !user.equals(env.getProperty("spring.datasource.username"))
                || password == null || password.length() < 24
                || !password.equals(env.getProperty("spring.datasource.password"))
                || !vars.contains("POSTGRES_DB=" + db) || !vars.contains("POSTGRES_USER=" + user)
                || !vars.contains("POSTGRES_PASSWORD=" + password)) fail();
    }

    private static void fail() { throw new IllegalStateException("Official demo database isolation could not be verified"); }
}
