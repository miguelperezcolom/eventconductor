package io.mateu.workflow.application.flowcontrol;

import io.mateu.workflow.application.out.ProcessRepository;
import io.mateu.workflow.application.out.WorkflowMetrics;
import io.mateu.workflow.application.services.IngressRouter;
import io.mateu.workflow.application.usecases.process.cancel.CancelProcessCommand;
import io.mateu.workflow.application.usecases.process.cancel.CancelProcessUseCase;
import io.mateu.workflow.domain.aggregates.Process;
import io.mateu.workflow.domain.aggregates.ProcessStatus;
import io.mateu.workflow.domain.aggregates.StartPolicy;
import io.mateu.workflow.dtos.events.integration.ProcessCreationRequested;
import io.mateu.workflow.infra.out.memory.InMemoryFlowControlStore;
import io.mateu.workflow.security.AuthorizationContext;
import io.mateu.workflow.time.EngineClock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static io.mateu.workflow.application.flowcontrol.FlowControlService.Outcome.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class FlowControlServiceTest {

    static final Instant T0 = Instant.parse("2026-10-19T10:00:00Z");

    InMemoryFlowControlStore store;
    ProcessRepository processes;
    CancelProcessUseCase cancel;
    IngressRouter router;
    WorkflowMetrics metrics;
    FlowControlService service;
    List<FlowStart> started;
    List<ProcessCreationRequested> routed;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        at(T0);
        store = new InMemoryFlowControlStore();
        processes = mock(ProcessRepository.class);
        when(processes.findById(anyString())).thenReturn(Optional.empty());
        when(processes.findByBusinessKey(anyString())).thenReturn(Optional.empty());
        cancel = mock(CancelProcessUseCase.class);
        router = mock(IngressRouter.class);
        routed = new ArrayList<>();
        doAnswer(inv -> routed.add(inv.getArgument(0))).when(router).route(any());
        metrics = mock(WorkflowMetrics.class);
        ObjectProvider<CancelProcessUseCase> cancelProvider = mock(ObjectProvider.class);
        when(cancelProvider.getObject()).thenReturn(cancel);
        ObjectProvider<IngressRouter> routerProvider = mock(ObjectProvider.class);
        when(routerProvider.getObject()).thenReturn(router);
        service = new FlowControlService(store, processes, cancelProvider, routerProvider, metrics);
        started = new ArrayList<>();
    }

    @AfterEach
    void tearDown() {
        EngineClock.reset();
    }

    static void at(Instant instant) {
        EngineClock.set(Clock.fixed(instant, ZoneOffset.UTC));
    }

    FlowStart start(StartPolicy policy, String key, String businessKey, Map<String, String> variables) {
        return new FlowStart(FlowStart.startScope("wf"), key, "wf", businessKey, variables, AuthorizationContext.SYSTEM,
                FlowStart.START, null, policy, false);
    }

    FlowControlService.Decision admit(FlowStart start) {
        return service.admit(start, s -> {
            started.add(s);
            return new FlowControlService.Started("p-" + s.businessKey(), s.businessKey());
        }, FlowControlService.Mode.DEFAULT);
    }

    @Test
    void aDebounceKeepsTheLastStartAndReleasesItAfterTheQuietPeriod() {
        var policy = new StartPolicy("${k}", "PT30S", null, null);
        assertThat(admit(start(policy, "a", "bk-1", Map.of("n", "1"))).outcome()).isEqualTo(DEBOUNCED);
        at(T0.plusSeconds(20));
        var second = admit(start(policy, "a", "bk-2", Map.of("n", "2")));
        assertThat(second.outcome()).isEqualTo(DEBOUNCED);
        assertThat(second.dueAt()).isEqualTo(java.time.LocalDateTime.ofInstant(T0.plusSeconds(50), ZoneOffset.UTC));
        assertThat(store.pending(FlowControlStore.Kind.DEBOUNCE)).isEqualTo(1);
        assertThat(service.nextDueAt()).contains(second.dueAt());

        at(T0.plusSeconds(49));
        assertThat(service.releaseDue()).isZero();
        at(T0.plusSeconds(50));
        assertThat(service.releaseDue()).isEqualTo(1);
        assertThat(routed).singleElement().satisfies(creation -> {
            assertThat(creation.businessKey()).isEqualTo("bk-2");
            assertThat(creation.variables()).extracting(io.mateu.workflow.dtos.Variable::value).containsExactly("2");
        });
        assertThat(store.pending(FlowControlStore.Kind.DEBOUNCE)).isZero();
        assertThat(started).isEmpty();
    }

    @Test
    void aThrottleQueuesFifoAndReleasesWindowByWindow() {
        var policy = new StartPolicy(null, null, new StartPolicy.Throttle(2, "PT1M", null), null);
        var outcomes = new ArrayList<FlowControlService.Outcome>();
        for (int i = 1; i <= 5; i++) {
            outcomes.add(admit(start(policy, "", "bk-" + i, Map.of())).outcome());
        }
        assertThat(outcomes).containsExactly(STARTED, STARTED, THROTTLED, THROTTLED, THROTTLED);
        assertThat(store.pending(FlowControlStore.Kind.THROTTLE)).isEqualTo(3);

        at(T0.plusSeconds(30));
        assertThat(service.releaseDue()).isZero();
        at(T0.plusSeconds(60));
        assertThat(service.releaseDue()).isEqualTo(2);
        assertThat(routed).extracting(ProcessCreationRequested::businessKey).containsExactly("bk-3", "bk-4");
        // A new start waits behind the one still queued.
        assertThat(admit(start(policy, "", "bk-6", Map.of())).outcome()).isEqualTo(THROTTLED);
        at(T0.plusSeconds(120));
        service.releaseDue();
        assertThat(routed).extracting(ProcessCreationRequested::businessKey).containsExactly("bk-3", "bk-4", "bk-5", "bk-6");
    }

    @Test
    void aThrottleThatDropsAndASynchronousCallerAreNeverQueued() {
        var drop = new StartPolicy(null, null, new StartPolicy.Throttle(1, "PT1M", "drop"), null);
        assertThat(admit(start(drop, "", "a", Map.of())).outcome()).isEqualTo(STARTED);
        var dropped = admit(start(drop, "", "b", Map.of()));
        assertThat(dropped.outcome()).isEqualTo(DROPPED);
        assertThat(dropped.dueAt()).isEqualTo(java.time.LocalDateTime.ofInstant(T0.plusSeconds(60), ZoneOffset.UTC));

        var queue = new StartPolicy("${x}", "PT5S", new StartPolicy.Throttle(1, "PT1M", "queue"), null);
        var first = service.admit(start(queue, "k", "c", Map.of()), s -> new FlowControlService.Started(null, "c"),
                FlowControlService.Mode.IMMEDIATE_ONLY);
        assertThat(first.outcome()).isEqualTo(STARTED);   // no debounce for a synchronous caller
        var second = service.admit(start(queue, "k", "d", Map.of()), s -> new FlowControlService.Started(null, "d"),
                FlowControlService.Mode.IMMEDIATE_ONLY);
        assertThat(second.outcome()).isEqualTo(DROPPED);
        assertThat(store.pending(FlowControlStore.Kind.THROTTLE)).isZero();
    }

    @Test
    void aSingletonSkipsWhileTheLastStartRunsAndReplacesWhenAskedTo() {
        var skip = new StartPolicy("${a}", null, null, "skip");
        var running = Process.builder().id("p-bk-1").businessKey("bk-1").status(ProcessStatus.RUNNING).build();
        when(processes.findById("p-bk-1")).thenReturn(Optional.of(running));
        assertThat(admit(start(skip, "a1", "bk-1", Map.of())).outcome()).isEqualTo(STARTED);
        var skipped = admit(start(skip, "a1", "bk-2", Map.of()));
        assertThat(skipped.outcome()).isEqualTo(SKIPPED);
        assertThat(skipped.processId()).isEqualTo("p-bk-1");
        assertThat(admit(start(skip, "a2", "bk-3", Map.of())).outcome()).isEqualTo(STARTED);

        when(processes.findById("p-bk-1")).thenReturn(Optional.of(running.withStatus(ProcessStatus.COMPLETED)));
        assertThat(admit(start(skip, "a1", "bk-4", Map.of())).outcome()).isEqualTo(STARTED);

        var replace = new StartPolicy("${a}", null, null, "replace");
        var holder = Process.builder().id("p-r-1").businessKey("r-1").status(ProcessStatus.RUNNING).build();
        when(processes.findById("p-r-1")).thenReturn(Optional.of(holder));
        assertThat(admit(start(replace, "s", "r-1", Map.of())).outcome()).isEqualTo(STARTED);
        assertThat(admit(start(replace, "s", "r-2", Map.of())).outcome()).isEqualTo(REPLACED);
        verify(cancel).handle(new CancelProcessCommand("p-r-1"));
    }

    @Test
    void aStartNotCreatedYetCountsAsRunningForItsGrace() {
        var skip = new StartPolicy(null, null, null, "skip");
        service.admit(start(skip, "", "late-1", Map.of()), s -> new FlowControlService.Started(null, "late-1"),
                FlowControlService.Mode.DEFAULT);
        assertThat(admit(start(skip, "", "late-2", Map.of())).outcome()).isEqualTo(SKIPPED);
        at(T0.plus(Duration.ofMinutes(6)));
        assertThat(admit(start(skip, "", "late-3", Map.of())).outcome()).isEqualTo(STARTED);
    }

    @Test
    void aQueuedStartStillHonoursItsSingletonWhenReleased() {
        var policy = new StartPolicy(null, null, new StartPolicy.Throttle(1, "PT1M", null), "skip");
        var running = Process.builder().id("p-q-1").businessKey("q-1").status(ProcessStatus.RUNNING).build();
        when(processes.findById("p-q-1")).thenReturn(Optional.of(running));
        assertThat(admit(start(policy, "", "q-1", Map.of())).outcome()).isEqualTo(STARTED);
        // The singleton is asked first: while q-1 runs, a second start is skipped, not queued.
        assertThat(admit(start(policy, "", "q-2", Map.of())).outcome()).isEqualTo(SKIPPED);
        when(processes.findById("p-q-1")).thenReturn(Optional.of(running.withStatus(ProcessStatus.ERROR)));
        assertThat(admit(start(policy, "", "q-3", Map.of())).outcome()).isEqualTo(THROTTLED);
        at(T0.plusSeconds(60));
        assertThat(service.releaseDue()).isEqualTo(1);
        assertThat(routed).extracting(ProcessCreationRequested::businessKey).containsExactly("q-3");
    }

    @Test
    void aThrottledStartWithoutABusinessKeyGetsOne() {
        var policy = new StartPolicy(null, null, new StartPolicy.Throttle(1, "PT1M", null), null);
        admit(start(policy, "", null, Map.of()));
        var queued = admit(start(policy, "", null, Map.of()));
        assertThat(queued.outcome()).isEqualTo(THROTTLED);
        assertThat(queued.businessKey()).startsWith("wf-flow-");
    }

    @Test
    void keysAreRenderedOverTheVariablesAndRefusedWhenEmpty() {
        var policy = new StartPolicy("${customerId}-${region}", null, null, "skip");
        assertThat(FlowKeys.overVariables(policy, "wf", "bk", Map.of("customerId", "c1", "region", "eu"))).isEqualTo("c1-eu");
        assertThat(FlowKeys.overVariables(new StartPolicy(null, null, null, "skip"), "wf", "bk", Map.of())).isEmpty();
        assertThat(FlowKeys.overVariables(new StartPolicy("${workflowId}/${businessKey}", null, null, "skip"), "wf", "bk",
                null)).isEqualTo("wf/bk");
        assertThatThrownBy(() -> FlowKeys.overVariables(new StartPolicy("${missing}", null, null, "skip"), "wf", "bk", Map.of()))
                .isInstanceOf(FlowKeys.UnrenderableKeyException.class);
        assertThatThrownBy(() -> FlowKeys.render(new StartPolicy("${(}", null, null, "skip"), Map.of()))
                .isInstanceOf(FlowKeys.UnrenderableKeyException.class);
        assertThat(FlowControlService.keyId("start:wf", "a")).hasSize(64).isNotEqualTo(FlowControlService.keyId("start:wf", "b"));
        assertThat(FlowControlService.Outcome.REPLACED.started()).isTrue();
        assertThat(FlowControlService.Outcome.SKIPPED.started()).isFalse();
    }
}
