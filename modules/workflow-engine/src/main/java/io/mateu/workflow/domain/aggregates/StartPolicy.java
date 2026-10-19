package io.mateu.workflow.domain.aggregates;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import io.mateu.workflow.analysis.FlowControlRules;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Per-key flow control of the starts of a definition — on the definition (REST, MCP, console, sync
 * invocation, and the triggers that declare none) or on one trigger. Carried in the file as
 * {@code startPolicy: { key, debounce, throttle: { limit, period, onLimit }, singleton }}; validated by
 * {@link FlowControlRules} (engine and Maven plugin alike). See FLOW-CONTROL-PLAN.md §2.
 *
 * @param key       text template rendering the key the policy counts by — over the event for a trigger's
 *                  own policy, over the start's variables (plus {@code businessKey} and {@code workflowId})
 *                  for the definition's; null means one key for the whole scope
 * @param debounce  ISO-8601 duration: start once, with the last start's data, after this much quiet
 * @param throttle  at most {@code limit} starts per {@code period} per key; over it queue or drop
 * @param singleton {@code skip} or {@code replace} while a start of the key is still running
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({"key", "debounce", "throttle", "singleton"})
public record StartPolicy(String key, String debounce, Throttle throttle, String singleton) {

    public StartPolicy {
        key = key == null || key.isBlank() ? null : key;
        debounce = debounce == null || debounce.isBlank() ? null : debounce.trim();
        singleton = singleton == null || singleton.isBlank() ? null : singleton.trim();
    }

    /** {@code throttle: { limit, period, onLimit }}. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonPropertyOrder({"limit", "period", "onLimit"})
    public record Throttle(Integer limit, String period, String onLimit) {

        /** Whether a start over the limit is dropped (otherwise it is queued). */
        @JsonIgnore
        public boolean drops() {
            return "drop".equals(onLimit);
        }

        @JsonIgnore
        public Duration periodDuration() {
            return FlowControlRules.duration(period);
        }
    }

    /** What a singleton does while a start of its key is running. */
    public enum Singleton { SKIP, REPLACE }

    @JsonIgnore
    public Duration debounceDuration() {
        return debounce == null ? null : FlowControlRules.duration(debounce);
    }

    @JsonIgnore
    public Singleton singletonMode() {
        return singleton == null ? null : Singleton.valueOf(singleton.toUpperCase(java.util.Locale.ROOT));
    }

    /** The policy as the file writes it, for the shared rules. */
    public Map<String, Object> asMap() {
        var map = new LinkedHashMap<String, Object>();
        if (key != null) map.put("key", key);
        if (debounce != null) map.put("debounce", debounce);
        if (throttle != null) {
            var t = new LinkedHashMap<String, Object>();
            if (throttle.limit() != null) t.put("limit", throttle.limit());
            if (throttle.period() != null) t.put("period", throttle.period());
            if (throttle.onLimit() != null) t.put("onLimit", throttle.onLimit());
            map.put("throttle", t);
        }
        if (singleton != null) map.put("singleton", singleton);
        return map;
    }

    /** What is wrong with it, each message prefixed with {@code where}; empty means valid. */
    public List<String> violations(String where) {
        return FlowControlRules.startPolicyViolations(asMap(), where);
    }
}
