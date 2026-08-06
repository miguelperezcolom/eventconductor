package io.mateu.workflow.infra.config;

import io.mateu.workflow.application.out.ShardRegistry;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Hot, file-backed shard registry — the piece that makes add/remove elastic, not a redeploy. It reads
 * the active-shard list from a file ({@code workflow.sharding.registry-file}) and re-reads it on a
 * short interval, so editing the file (in Kubernetes: editing the ConfigMap mounted at that path)
 * takes effect within one refresh, no restart. Present only when the file path is set, and
 * {@link Primary} so it wins over the static {@link ConfigShardRegistry} — the same {@link ShardRegistry}
 * port, so nothing that reads it (the ingress router) changes.
 *
 * <p><b>Fail-safe on read error.</b> A transient failure to read the file (a ConfigMap volume mid-swap,
 * a permissions blip) keeps the last known good list rather than reporting zero shards — draining the
 * whole fleet because a file was briefly unreadable would be the worst possible failure mode. The
 * registry only ever changes to a list it actually managed to read.
 *
 * <p>File format: shard ids separated by commas and/or newlines; blank lines and {@code #} comments
 * (whole-line or trailing) ignored. So both {@code s0,s1,s2} and one-id-per-line work.
 */
@Component
@Primary
@ConditionalOnProperty(name = "workflow.sharding.registry-file")
@Slf4j
public class FileShardRegistry implements ShardRegistry {

    private final Path file;
    private final long refreshMs;
    private volatile List<String> active = List.of();
    private ScheduledExecutorService refresher;

    public FileShardRegistry(@Value("${workflow.sharding.registry-file}") String path,
                             @Value("${workflow.sharding.registry-refresh-ms:5000}") long refreshMs) {
        this.file = Path.of(path);
        this.refreshMs = refreshMs;
        // Load once now so the registry is usable before the refresher's first tick.
        reload();
    }

    @PostConstruct
    void startRefreshing() {
        refresher = Executors.newSingleThreadScheduledExecutor(r -> {
            var t = new Thread(r, "shard-registry-refresh");
            t.setDaemon(true);
            return t;
        });
        refresher.scheduleWithFixedDelay(this::reload, refreshMs, refreshMs, TimeUnit.MILLISECONDS);
    }

    @PreDestroy
    void stopRefreshing() {
        if (refresher != null) {
            refresher.shutdownNow();
        }
    }

    /** Re-read the file; on any read error keep the last known good list. Package-visible for tests. */
    void reload() {
        List<String> parsed;
        try {
            parsed = parse(Files.readAllLines(file));
        } catch (IOException e) {
            log.warn("Could not read shard registry file {}; keeping the last known {} active shard(s)",
                    file, active.size(), e);
            return;
        }
        if (!parsed.equals(active)) {
            log.info("Shard registry changed: active shards = {}", parsed);
            active = parsed;
        }
    }

    static List<String> parse(List<String> lines) {
        return lines.stream()
                // Drop a trailing/whole-line comment, then split the remainder on commas.
                .map(line -> {
                    var hash = line.indexOf('#');
                    return hash >= 0 ? line.substring(0, hash) : line;
                })
                .flatMap(line -> Arrays.stream(line.split(",")))
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .toList();
    }

    @Override
    public List<String> activeShards() {
        return active;
    }
}
