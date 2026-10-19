package io.mateu.workflow.domain.aggregates;

import io.mateu.workflow.domain.services.ContinueAsNewVariables;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static io.mateu.workflow.domain.aggregates.BusinessKeyPolicy.Decision.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The declarations of 2.33 — startPolicy, businessKeyPolicy, priority, CONTINUE_AS_NEW — as the engine reads them. */
class FlowControlDeclarationsTest {

    @Test
    void businessKeyPolicies() {
        assertThat(BusinessKeyPolicy.of(null)).isEqualTo(BusinessKeyPolicy.UNIQUE);
        assertThat(BusinessKeyPolicy.of(" terminateRunning ")).isEqualTo(BusinessKeyPolicy.TERMINATE_RUNNING);
        assertThatThrownBy(() -> BusinessKeyPolicy.of("reuse")).isInstanceOf(IllegalArgumentException.class);
        assertThat(BusinessKeyPolicy.UNIQUE.decide(ProcessStatus.COMPLETED)).isEqualTo(KEEP);
        assertThat(BusinessKeyPolicy.ALLOW_AFTER_FINISHED.decide(ProcessStatus.RUNNING)).isEqualTo(KEEP);
        assertThat(BusinessKeyPolicy.ALLOW_AFTER_FINISHED.decide(ProcessStatus.COMPLETED)).isEqualTo(REUSE);
        assertThat(BusinessKeyPolicy.ALLOW_AFTER_FAILED.decide(ProcessStatus.COMPLETED)).isEqualTo(KEEP);
        assertThat(BusinessKeyPolicy.ALLOW_AFTER_FAILED.decide(ProcessStatus.COMPENSATED)).isEqualTo(REUSE);
        assertThat(BusinessKeyPolicy.TERMINATE_RUNNING.decide(ProcessStatus.PAUSED)).isEqualTo(TERMINATE_AND_REUSE);
        assertThat(BusinessKeyPolicy.TERMINATE_RUNNING.decide(ProcessStatus.CANCELLED)).isEqualTo(REUSE);
        assertThat(BusinessKeyPolicy.ALLOW_AFTER_FINISHED.text()).isEqualTo("allowAfterFinished");
        assertThat(BusinessKeyPolicy.ended(null)).isFalse();
    }

    @Test
    void aStartPolicyReadsItsDurationsAndModes() {
        var policy = new StartPolicy(" ", " PT30S ", new StartPolicy.Throttle(3, "PT1M", "drop"), "replace");
        assertThat(policy.key()).isNull();
        assertThat(policy.debounceDuration()).isEqualTo(Duration.ofSeconds(30));
        assertThat(policy.throttle().drops()).isTrue();
        assertThat(policy.throttle().periodDuration()).isEqualTo(Duration.ofMinutes(1));
        assertThat(policy.singletonMode()).isEqualTo(StartPolicy.Singleton.REPLACE);
        assertThat(policy.asMap()).containsKeys("debounce", "throttle", "singleton").doesNotContainKey("key");
        assertThat(policy.violations("startPolicy")).isEmpty();
        assertThat(new StartPolicy("${x}", null, null, null).violations("p")).hasSize(1);
        assertThat(new StartPolicy(null, null, null, "skip").debounceDuration()).isNull();
        assertThat(new StartPolicy(null, null, new StartPolicy.Throttle(null, null, null), null).asMap())
                .containsEntry("throttle", Map.of());
    }

    static Step step(String id, StepType type, String precondition) {
        return new Step(id, null, type, id, null, precondition, null, null, null, false, "work", null, null, null, null,
                0L, null, null, null, null, 0L, 0, false, null, null, 0, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null);
    }

    static WorkflowDefinition definition(List<Step> steps) {
        return new WorkflowDefinition("wf", "Wf", 1, null, false, 0, false, null, 0, steps);
    }

    @Test
    void theDefinitionChecksItsFlowControlAndItsContinuations() {
        var steps = new ArrayList<Step>(List.of(step("start", StepType.START, null),
                step("call", StepType.ACTION, "start").withPriority(90),
                step("next", StepType.CONTINUE_AS_NEW, "call").withContinueAsNew(new ContinueAsNew("latest", List.of("a")))));
        var good = definition(steps).withStartPolicy(new StartPolicy("${a}", null, null, "skip"))
                .withBusinessKeyPolicy("allowAfterFailed");
        good.checkInvariants();
        assertThat(good.keyPolicy()).isEqualTo(BusinessKeyPolicy.ALLOW_AFTER_FAILED);
        assertThat(good.steps().get(1).effectivePriority()).isEqualTo(90);
        assertThat(step("x", StepType.ACTION, null).effectivePriority()).isEqualTo(50);
        assertThat(good.startPolicyFor(new EventTrigger(null, "d", "t", null, null, null))).isEqualTo(good.startPolicy());
        var own = new StartPolicy(null, "PT1S", null, null);
        assertThat(good.startPolicyFor(new EventTrigger(null, "d", "t", null, null, null, own))).isEqualTo(own);

        var followed = new ArrayList<>(steps);
        followed.add(step("after", StepType.END, "next"));
        assertThatThrownBy(() -> definition(followed).checkInvariants()).hasMessageContaining("no step may follow it");
        assertThatThrownBy(() -> definition(List.of(step("t", StepType.TIMER, null).withPriority(5))).checkInvariants())
                .hasMessageContaining("priority applies to steps");
        assertThatThrownBy(() -> definition(steps).withBusinessKeyPolicy("reuse").checkInvariants())
                .hasMessageContaining("businessKeyPolicy");
        assertThatThrownBy(() -> definition(steps).withStartPolicy(new StartPolicy(null, "PT1S", null, null))
                .withSyncInvocation(new SyncInvocation(true, null, null, 0)).checkInvariants())
                .hasMessageContaining("invocable synchronously");
        assertThatThrownBy(() -> definition(steps).withTriggers(List.of(new EventTrigger(null, "d", "t", null, null, null,
                new StartPolicy("${a}", null, null, null)))).checkInvariants())
                .hasMessageContaining("triggers[0].startPolicy");
    }

    @Test
    void aContinuationCarriesAllTheNamedOrTheMappedVariables() {
        var process = Process.builder().id("p").variables(List.of(new Variable("a", "1"), new Variable("b", "2"))).build();
        var all = step("n", StepType.CONTINUE_AS_NEW, null);
        assertThat(ContinueAsNewVariables.of(all, process, problem -> { })).extracting(Variable::name).containsExactly("a", "b");
        var named = all.withContinueAsNew(new ContinueAsNew(null, List.of("b", "missing")));
        assertThat(ContinueAsNewVariables.of(named, process, problem -> { })).extracting(Variable::name).containsExactly("b");
        var problems = new ArrayList<String>();
        var mapped = all.withContinueAsNew(new ContinueAsNew(null, Map.of("c", "${a}-${b}", "list", List.of("${a}"),
                "bad", "${(")));
        var carried = ContinueAsNewVariables.of(mapped, process, problems::add);
        assertThat(carried).contains(new Variable("c", "1-2"), new Variable("list", "[\"1\"]"));
        assertThat(problems).singleElement().asString().contains("'bad'");
        assertThat(new ContinueAsNew("latest", null).latest()).isTrue();
        assertThat(new ContinueAsNew(null, null).asMap()).isEmpty();
    }

    @Test
    void flowControlAndBusinessKeysMayNotReadSensitiveVariables() {
        var steps = List.of(step("start", StepType.START, null), step("call", StepType.ACTION, "start"));
        var sealedCard = definition(steps).withSensitiveVariables(List.of("card*"));
        assertThatThrownBy(() -> sealedCard.withStartPolicy(new StartPolicy("${cardNumber}", null, null, "skip"))
                .checkInvariants()).hasMessageContaining("startPolicy.key reads the sensitive variable(s) cardNumber");
        var trigger = new EventTrigger("t", "d", "x", null, "order-${data.card}", Map.of("cardNumber", "${data.card}"),
                new StartPolicy("${data.card}", null, null, "skip"));
        assertThatThrownBy(() -> sealedCard.withTriggers(List.of(trigger)).checkInvariants())
                .hasMessageContaining("triggers[0].businessKey reads the event field(s)")
                .hasMessageContaining("triggers[0].startPolicy.key");
        sealedCard.withStartPolicy(new StartPolicy("${customerId}", null, null, "skip")).checkInvariants();
        assertThat(definition(steps).sensitiveMatcher()).isNull();
        assertThat(sealedCard.sensitiveMatcher().test("cardNumber")).isTrue();
    }
}
