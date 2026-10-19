package io.mateu.workflow.analysis;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FlowControlRulesTest {

    @Test
    void aFullStartPolicyIsValid() {
        var policy = Map.of("key", "${data.customerId}", "debounce", "PT30S",
                "throttle", Map.of("limit", 10, "period", "PT1M", "onLimit", "drop"), "singleton", "replace");
        assertThat(FlowControlRules.startPolicyViolations(policy, "startPolicy")).isEmpty();
        assertThat(FlowControlRules.startPolicyViolations(null, "startPolicy")).isEmpty();
    }

    @Test
    void aStartPolicyNeedsSomethingToDo() {
        assertThat(FlowControlRules.startPolicyViolations(Map.of("key", "${x}"), "startPolicy"))
                .singleElement().asString().contains("at least one of debounce, throttle or singleton");
        assertThat(FlowControlRules.startPolicyViolations("skip", "startPolicy"))
                .singleElement().asString().contains("is an object");
    }

    @Test
    void badFieldsAreNamed() {
        var policy = new LinkedHashMap<String, Object>();
        policy.put("key", "${unterminated");
        policy.put("debounce", "30s");
        policy.put("throttle", Map.of("limit", 0, "onLimit", "wait", "burst", 3));
        policy.put("singleton", "kill");
        policy.put("concurrency", 2);
        var violations = FlowControlRules.startPolicyViolations(policy, "triggers[0].startPolicy");
        assertThat(violations).allMatch(v -> v.startsWith("triggers[0].startPolicy"));
        assertThat(String.join("\n", violations))
                .contains("unknown field 'concurrency'")
                .contains("key: Unterminated")
                .contains("debounce: '30s' is not an ISO-8601 duration")
                .contains("limit must be a whole number from 1")
                .contains("period is required")
                .contains("onLimit must be one of [queue, drop]")
                .contains("unknown field 'burst'")
                .contains("singleton must be one of [skip, replace]");
        assertThat(FlowControlRules.startPolicyViolations(Map.of("key", " ", "singleton", "skip"), null))
                .singleElement().asString().startsWith("key must be a text template");
        assertThat(FlowControlRules.startPolicyViolations(Map.of("throttle", "10/min"), null))
                .singleElement().asString().contains("a throttle is");
        assertThat(FlowControlRules.startPolicyViolations(Map.of("debounce", "PT0S"), null))
                .singleElement().asString().contains("longer than zero");
    }

    @Test
    void debounceIsRefusedOnASyncInvocableDefinition() {
        assertThat(FlowControlRules.syncInvocationViolations(Map.of("debounce", "PT1S"), true, "startPolicy"))
                .singleElement().asString().contains("syncInvocation");
        assertThat(FlowControlRules.syncInvocationViolations(Map.of("debounce", "PT1S"), false, "startPolicy")).isEmpty();
        assertThat(FlowControlRules.syncInvocationViolations(Map.of("singleton", "skip"), true, "startPolicy")).isEmpty();
    }

    @Test
    void businessKeyPolicies() {
        for (var policy : FlowControlRules.BUSINESS_KEY_POLICIES) {
            assertThat(FlowControlRules.businessKeyPolicyViolations(policy, null)).isEmpty();
        }
        assertThat(FlowControlRules.businessKeyPolicyViolations(null, null)).isEmpty();
        assertThat(FlowControlRules.businessKeyPolicyViolations("reuse", "def")).singleElement().asString()
                .startsWith("def: businessKeyPolicy must be one of");
    }

    @Test
    void priority() {
        assertThat(FlowControlRules.priorityViolations("a", "ACTION", 100)).isEmpty();
        assertThat(FlowControlRules.priorityViolations("a", "HTTP_CALL", "0")).isEmpty();
        assertThat(FlowControlRules.priorityViolations("a", "ACTION", null)).isEmpty();
        assertThat(FlowControlRules.priorityViolations("a", "ACTION", 101)).singleElement().asString()
                .contains("from 0 to 100");
        assertThat(FlowControlRules.priorityViolations("a", "ACTION", 2.5)).hasSize(1);
        assertThat(FlowControlRules.priorityViolations("t", "TIMER", 10)).singleElement().asString()
                .contains("not to TIMER");
    }

    @Test
    void continueAsNew() {
        assertThat(FlowControlRules.continueAsNewViolations("c", "CONTINUE_AS_NEW", null, List.of(), false)).isEmpty();
        assertThat(FlowControlRules.continueAsNewViolations("c", "CONTINUE_AS_NEW",
                Map.of("version", "latest", "variables", List.of("a", "b")), List.of(), false)).isEmpty();
        assertThat(FlowControlRules.continueAsNewViolations("c", "CONTINUE_AS_NEW",
                Map.of("variables", Map.of("month", "${month + 1}")), List.of(), false)).isEmpty();
        var bad = FlowControlRules.continueAsNewViolations("c", "CONTINUE_AS_NEW",
                Map.of("version", "v2", "variables", Map.of("m", "${oops"), "extra", true), List.of("after"), true);
        assertThat(String.join("\n", bad))
                .contains("no step may follow it (followed by after)")
                .contains("cannot declare forEach")
                .contains("version must be one of [same, latest]")
                .contains("continueAsNew.variables.m: Unterminated")
                .contains("unknown field 'extra'");
        assertThat(FlowControlRules.continueAsNewViolations("c", "CONTINUE_AS_NEW",
                Map.of("variables", "all"), List.of(), false)).singleElement().asString().contains("a list of names");
        assertThat(FlowControlRules.continueAsNewViolations("c", "CONTINUE_AS_NEW",
                Map.of("variables", List.of("", 3)), List.of(), false)).hasSize(2);
        assertThat(FlowControlRules.continueAsNewViolations("c", "CONTINUE_AS_NEW", "latest", List.of(), false))
                .singleElement().asString().contains("is an object");
        assertThat(FlowControlRules.continueAsNewViolations("a", "ACTION", Map.of(), List.of(), false))
                .singleElement().asString().contains("only to CONTINUE_AS_NEW");
        assertThat(FlowControlRules.continueAsNewViolations("a", "ACTION", null, List.of("b"), false)).isEmpty();
    }

    @Test
    void durations() {
        assertThat(FlowControlRules.duration("PT1M")).isEqualTo(Duration.ofMinutes(1));
        assertThat(FlowControlRules.duration(" P1DT2H ")).isEqualTo(Duration.ofHours(26));
        assertThatThrownBy(() -> FlowControlRules.duration("P1M")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> FlowControlRules.duration(30)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> FlowControlRules.duration("PT")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void integers() {
        assertThat(FlowControlRules.integer(3L)).isEqualTo(3);
        assertThat(FlowControlRules.integer(4.0)).isEqualTo(4);
        assertThat(FlowControlRules.integer("x")).isNull();
        assertThat(FlowControlRules.integer(true)).isNull();
    }
}
