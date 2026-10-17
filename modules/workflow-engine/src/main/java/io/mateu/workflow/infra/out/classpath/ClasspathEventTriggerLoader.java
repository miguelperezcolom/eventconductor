package io.mateu.workflow.infra.out.classpath;

import io.mateu.workflow.application.services.EventTriggerImporter;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Loads {@code classpath:/workflows/events.yaml} (or {@code events.yml}) at startup, in both persistence
 * modes, after the classpath definitions are in place ({@code @Order(1)}; the JPA classpath importer is
 * {@code @Order(0)}, the memory repository loads in its constructor) so its entries can be checked
 * against them — exactly as {@link ClasspathCronScheduleLoader} does for {@code cron.yaml}.
 *
 * <p>Always replaces the classpath source, so a deployment that removed an entry — or the whole file
 * — also removes the trigger a previous deployment left in the database.
 *
 * <p>A database that is down at startup does not stop the boot: the failure is logged and the load
 * retried in the background, with backoff, until it goes through. Until then the triggers a previous
 * deployment stored keep matching.
 */
@Service
@RequiredArgsConstructor
@Order(1)
@Slf4j
public class ClasspathEventTriggerLoader implements ApplicationRunner {

    /** The source key classpath triggers are recorded under. */
    public static final String SOURCE = "classpath:/workflows/events.yaml";

    private static final String BASE = "classpath:/workflows/";

    static final long FIRST_RETRY_DELAY_MS = 5_000;
    static final long MAX_RETRY_DELAY_MS = 60_000;

    final EventTriggerImporter eventTriggerImporter;

    private volatile Thread retryThread;

    @Override
    public void run(ApplicationArguments args) {
        loadOrRetryInBackground(FIRST_RETRY_DELAY_MS);
    }

    /** Loads now; if storing fails (database down), keeps retrying on a daemon thread until it works. */
    void loadOrRetryInBackground(long firstDelayMs) {
        try {
            load();
        } catch (RuntimeException e) {
            log.warn("Could not store the classpath event triggers ({}); retrying in the background", e.getMessage());
            var thread = new Thread(() -> retryUntilLoaded(firstDelayMs), "workflow-events-classpath-retry");
            thread.setDaemon(true);
            retryThread = thread;
            thread.start();
        }
    }

    private void retryUntilLoaded(long firstDelayMs) {
        var delay = firstDelayMs;
        while (!Thread.currentThread().isInterrupted()) {
            try {
                Thread.sleep(delay);
                load();
                log.info("Stored the classpath event triggers after an earlier failure");
                return;
            } catch (InterruptedException e) {
                return;
            } catch (RuntimeException e) {
                log.debug("Classpath event triggers still not stored: {}", e.getMessage());
                delay = Math.min(delay * 2, MAX_RETRY_DELAY_MS);
            }
        }
    }

    @PreDestroy
    void stop() {
        var thread = retryThread;
        if (thread != null) {
            thread.interrupt();
        }
    }

    /** Reads the classpath events file(s) and replaces the classpath source's triggers. */
    public EventTriggerImporter.Result load() {
        return load(BASE);
    }

    /** As {@link #load()}, reading the events file(s) under {@code base} (tests point it elsewhere). */
    EventTriggerImporter.Result load(String base) {
        var resolver = new PathMatchingResourcePatternResolver();
        var files = new ArrayList<EventTriggerImporter.EventFile>();
        for (var name : List.of("events.yaml", "events.yml")) {
            var location = base + name;
            var resource = resolver.getResource(location);
            if (!resource.exists()) {
                continue;
            }
            try (var in = resource.getInputStream()) {
                files.add(new EventTriggerImporter.EventFile(location, in.readAllBytes()));
            } catch (Exception e) {
                log.error("Could not read {}: {}", location, e.getMessage());
                return new EventTriggerImporter.Result(List.of(), List.of(location + ": " + e.getMessage()));
            }
        }
        return eventTriggerImporter.importSource(SOURCE, files);
    }
}
