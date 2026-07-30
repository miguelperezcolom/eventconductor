package io.mateu.workflow.infra.out.cache;

import io.mateu.workflow.application.out.RuleRuntimeMetrics;
import io.mateu.workflow.application.out.RuleSource;
import io.mateu.workflow.domain.Rule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Caching decorator over a remote RuleSource (REST or gRPC). The whole catalog
 * is loaded on first use and re-loaded when the TTL expires; a TTL of zero
 * means entries never expire (refresh only on demand or via catalog Kafka
 * events, which call {@link #put} / {@link #invalidate}).
 */
public class CachingRuleSource implements RuleSource {

    private static final Logger log = LoggerFactory.getLogger(CachingRuleSource.class);

    private final RuleSource delegate;
    private final Duration ttl;
    private final Clock clock;
    private final RuleRuntimeMetrics metrics;
    private final Map<String, Rule> cache = new ConcurrentHashMap<>();
    private volatile long loadedAt = Long.MIN_VALUE;

    public CachingRuleSource(RuleSource delegate, Duration ttl) {
        this(delegate, ttl, Clock.systemUTC());
    }

    public CachingRuleSource(RuleSource delegate, Duration ttl, Clock clock) {
        this(delegate, ttl, clock, RuleRuntimeMetrics.NOOP);
    }

    // metrics defaults to NOOP so the cache still works as a plain library with no Spring
    public CachingRuleSource(RuleSource delegate, Duration ttl, RuleRuntimeMetrics metrics) {
        this(delegate, ttl, Clock.systemUTC(), metrics);
    }

    public CachingRuleSource(RuleSource delegate, Duration ttl, Clock clock, RuleRuntimeMetrics metrics) {
        this.delegate = delegate;
        this.ttl = ttl != null ? ttl : Duration.ZERO;
        this.clock = clock;
        this.metrics = metrics != null ? metrics : RuleRuntimeMetrics.NOOP;
    }

    @Override
    public Optional<Rule> findById(String id) {
        ensureFresh();
        var cached = cache.get(id);
        if (cached != null) {
            metrics.cacheHit(id);
            return Optional.of(cached);
        }
        metrics.cacheMiss(id);
        var fetched = delegate.findById(id);
        fetched.ifPresent(rule -> cache.put(rule.id(), rule));
        return fetched;
    }

    @Override
    public List<Rule> findAll() {
        ensureFresh();
        return List.copyOf(cache.values());
    }

    @Override
    public synchronized void refresh() {
        cache.clear();
        delegate.findAll().forEach(rule -> cache.put(rule.id(), rule));
        loadedAt = clock.millis();
        log.info("Rule cache refreshed: {} rules", cache.size());
    }

    public void put(Rule rule) {
        cache.put(rule.id(), rule);
    }

    public void invalidate(String id) {
        cache.remove(id);
    }

    private void ensureFresh() {
        if (loadedAt == Long.MIN_VALUE
                || (!ttl.isZero() && clock.millis() - loadedAt > ttl.toMillis())) {
            refresh();
        }
    }
}
