package io.mateu.workflowbench.suite;

import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.redpanda.RedpandaContainer;
import org.testcontainers.utility.DockerImageName;

import java.sql.DriverManager;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * PostgreSQL and Redpanda in Testcontainers, started on first use and stopped at the end (Ryuk
 * removes them even if the JVM dies). Each mode gets a database of its own in the one PostgreSQL,
 * so no mode inherits another's tables, indexes or bloat.
 */
final class Infra implements AutoCloseable {

    static final String POSTGRES_IMAGE = System.getProperty("suite.postgres.image", "postgres:16-alpine");
    static final String REDPANDA_IMAGE = System.getProperty("suite.redpanda.image",
            "docker.redpanda.com/redpandadata/redpanda:v24.1.7");
    static final int PARTITIONS = Integer.getInteger("suite.partitions", 6);

    private PostgreSQLContainer<?> postgres;
    private RedpandaContainer redpanda;
    private final Map<String, Object> versions = new LinkedHashMap<>();

    synchronized PostgreSQLContainer<?> postgres() {
        if (postgres == null) {
            postgres = new PostgreSQLContainer<>(DockerImageName.parse(POSTGRES_IMAGE).asCompatibleSubstituteFor("postgres"))
                    .withDatabaseName("suite")
                    .withUsername("suite")
                    .withPassword("suite")
                    // Testcontainers' default is fsync=off. -Dsuite.postgres.fsync=on measures with
                    // durable commits, as a production database runs.
                    .withCommand("postgres", "-c", "fsync=" + System.getProperty("suite.postgres.fsync", "off"));
            postgres.start();
            versions.put("postgresImage", POSTGRES_IMAGE);
            versions.put("postgresVersion", jdbc("suite").queryForObject("SHOW server_version", String.class));
            versions.put("postgresMaxConnections", jdbc("suite").queryForObject("SHOW max_connections", String.class));
            versions.put("postgresSharedBuffers", jdbc("suite").queryForObject("SHOW shared_buffers", String.class));
            versions.put("postgresSynchronousCommit", jdbc("suite").queryForObject("SHOW synchronous_commit", String.class));
            // Testcontainers starts PostgreSQL with fsync=off: commits never wait for the disk. Recorded
            // because it flatters every JPA number here against a production database.
            versions.put("postgresFsync", jdbc("suite").queryForObject("SHOW fsync", String.class));
        }
        return postgres;
    }

    synchronized String kafkaBootstrap() {
        if (redpanda == null) {
            redpanda = new RedpandaContainer(DockerImageName.parse(REDPANDA_IMAGE)
                    .asCompatibleSubstituteFor("docker.redpanda.com/redpandadata/redpanda"));
            redpanda.start();
            versions.put("redpandaImage", REDPANDA_IMAGE);
            versions.put("redpandaFlags", "testcontainers default: --mode=dev-container --smp=1 --memory=1G");
            try {
                versions.put("redpandaVersion", redpanda.execInContainer("rpk", "version").getStdout().trim()
                        .replaceAll("\\s+", " "));
            } catch (Exception e) {
                versions.put("redpandaVersion", "unknown: " + e.getMessage());
            }
        }
        return redpanda.getBootstrapServers();
    }

    /** A fresh database in the shared container, named after the mode. */
    String freshDatabase(String name) {
        var pg = postgres();
        try (var connection = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
             var statement = connection.createStatement()) {
            statement.executeUpdate("DROP DATABASE IF EXISTS " + name + " WITH (FORCE)");
            statement.executeUpdate("CREATE DATABASE " + name);
        } catch (Exception e) {
            throw new IllegalStateException("Could not create database " + name, e);
        }
        return jdbcUrl(name);
    }

    String jdbcUrl(String database) {
        var url = postgres().getJdbcUrl();
        var pathStart = url.indexOf('/', "jdbc:postgresql://".length());
        var query = url.contains("?") ? url.substring(url.indexOf('?')) : "";
        return url.substring(0, pathStart) + "/" + database + query;
    }

    JdbcTemplate jdbc(String database) {
        return new JdbcTemplate(new DriverManagerDataSource(jdbcUrl(database), "suite", "suite"));
    }

    /** Fresh topics for a kafka mode, so no mode reads another's backlog or committed offsets. */
    void freshTopics(List<String> names) {
        try (var admin = AdminClient.create(Map.of("bootstrap.servers", kafkaBootstrap()))) {
            var existing = admin.listTopics().names().get();
            var toDelete = names.stream().filter(existing::contains).toList();
            if (!toDelete.isEmpty()) {
                admin.deleteTopics(toDelete).all().get();
                Thread.sleep(2000);
            }
            admin.createTopics(names.stream().map(n -> new NewTopic(n, PARTITIONS, (short) 1)).toList()).all().get();
        } catch (Exception e) {
            throw new IllegalStateException("Could not create topics " + names, e);
        }
    }

    Map<String, Object> versions() {
        return versions;
    }

    /** Containers' memory as Docker reports it right now — one snapshot, taken at the end of a mode. */
    Map<String, String> containerMemory() {
        var out = new LinkedHashMap<String, String>();
        var containers = new LinkedHashMap<String, Object>();
        containers.put("postgres", postgres);
        containers.put("redpanda", redpanda);
        for (var entry : containers.entrySet()) {
            if (entry.getValue() instanceof org.testcontainers.containers.GenericContainer<?> c && c.isRunning()) {
                try {
                    var p = new ProcessBuilder("docker", "stats", "--no-stream", "--format",
                            "{{.CPUPerc}} cpu, {{.MemUsage}}", c.getContainerId()).redirectErrorStream(true).start();
                    out.put(entry.getKey(), new String(p.getInputStream().readAllBytes()).trim());
                    p.waitFor();
                } catch (Exception e) {
                    out.put(entry.getKey(), "unavailable");
                }
            }
        }
        return out;
    }

    @Override
    public void close() {
        if (redpanda != null) {
            redpanda.stop();
        }
        if (postgres != null) {
            postgres.stop();
        }
    }
}
