package io.mateu.workflow.infra.in.startup;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/**
 * Runs every {@link StartupDefinitionImport} (task contracts, then workflow definitions, from the
 * classpath and the configured directories) synchronously while the context starts, in a lifecycle
 * phase before the web server's — so by the time the HTTP connector accepts a request, the definitions
 * it may name are in the repository. Also before Kafka listener containers and inbound stream
 * bindings start, so no message is consumed for a definition that is about to be imported.
 *
 * <p>A failing import fails the startup, exactly as it did as an {@code ApplicationRunner}.
 */
@Component
@Slf4j
public class StartupDefinitionImports implements SmartLifecycle {

    /**
     * Below the servlet/reactive web server's start phase ({@code WebServerStartStopLifecycle}:
     * {@code Integer.MAX_VALUE - 2048}) with room to spare, and below Kafka listener containers
     * ({@code MAX_VALUE - 100}) and Spring Cloud Stream input bindings ({@code MAX_VALUE - 1000}).
     * Every singleton — data source, Flyway, JPA, the repositories — is initialised before any
     * lifecycle phase starts.
     */
    public static final int PHASE = Integer.MAX_VALUE - 4096;

    private final ObjectProvider<StartupDefinitionImport> imports;
    private volatile boolean running;

    public StartupDefinitionImports(ObjectProvider<StartupDefinitionImport> imports) {
        this.imports = imports;
    }

    @Override
    public void start() {
        // orderedStream honours @Order: task contracts (-100) before definitions (0, then unordered).
        imports.orderedStream().forEach(StartupDefinitionImport::importDefinitions);
        running = true;
        log.debug("Local definition imports finished before the web server starts");
    }

    @Override
    public void stop() {
        running = false;
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        return PHASE;
    }
}
