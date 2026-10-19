package io.mateu.workflow.infra.out.memory;

import io.mateu.workflow.application.ratelimit.RateLimitStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Rate-limit windows and waiting tasks in the heap: memory persistence, one JVM. */
@Component
@ConditionalOnProperty(name = "workflow.persistence", havingValue = "memory", matchIfMissing = true)
public class InMemoryRateLimitStore implements RateLimitStore {

    private record Window(LocalDateTime start, int used) {
    }

    private final Map<String, Window> windows = new ConcurrentHashMap<>();
    private final Map<String, Deferred> deferred = new ConcurrentHashMap<>();

    @Override
    public synchronized boolean tryAcquire(String topic, int permits, Duration period, LocalDateTime now) {
        var window = windows.get(topic);
        if (window == null || !now.isBefore(window.start().plus(period))) {
            window = new Window(now, 0);
        }
        if (window.used() >= permits) {
            windows.put(topic, window);
            return false;
        }
        windows.put(topic, new Window(window.start(), window.used() + 1));
        return true;
    }

    @Override
    public Optional<LocalDateTime> windowEnd(String topic, Duration period) {
        return Optional.ofNullable(windows.get(topic)).map(w -> w.start().plus(period));
    }

    @Override
    public void defer(Deferred task) {
        deferred.putIfAbsent(task.stepExecutionId(), task);
    }

    @Override
    public boolean hasDeferred(String topic) {
        return deferred.values().stream().anyMatch(d -> d.topic().equals(topic));
    }

    @Override
    public List<String> deferredTopics() {
        return deferred.values().stream().map(Deferred::topic).distinct().sorted().toList();
    }

    @Override
    public List<Deferred> deferred(String topic, int max) {
        return deferred.values().stream().filter(d -> d.topic().equals(topic))
                .sorted(Comparator.comparingInt(Deferred::priority).reversed()
                        .thenComparing(Deferred::deferredAt).thenComparing(Deferred::stepExecutionId))
                .limit(max).toList();
    }

    @Override
    public boolean claim(String stepExecutionId) {
        return deferred.remove(stepExecutionId) != null;
    }

    @Override
    public long deferredCount() {
        return deferred.size();
    }
}
