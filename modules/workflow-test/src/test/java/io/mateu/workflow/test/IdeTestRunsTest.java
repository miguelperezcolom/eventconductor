package io.mateu.workflow.test;

import io.mateu.core.infra.JsonSerializer;
import io.mateu.workflow.application.out.*;
import io.mateu.workflow.application.services.WorkflowTestRunService;
import io.mateu.workflow.application.usecases.process.inject.*;
import io.mateu.workflow.domain.aggregates.*;
import io.mateu.workflow.domain.aggregates.Process;
import io.mateu.workflow.security.AuthorizationContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Execute unpublished snapshots through the real engine; every external dependency is synthetic. */
@EventConductorTest(properties = "workflow.test-runs.required-roles=ide-tester")
@org.springframework.context.annotation.Import(IdeTestRunsTest.Metrics.class)
class IdeTestRunsTest {
    @Autowired WorkflowTestRunService tests;
    @Autowired WorkflowDefinitionRepository definitions;
    @Autowired ProcessRepository processes;
    @Autowired InjectStepsUseCase inject;
    @Autowired ProcessSnapshotReader snapshots;
    @Autowired io.micrometer.core.instrument.MeterRegistry meters;

    @org.springframework.boot.test.context.TestConfiguration
    static class Metrics {
        @org.springframework.context.annotation.Bean
        io.micrometer.core.instrument.MeterRegistry meterRegistry() {
            return new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        }
    }

    WorkflowDefinition definition(String document) {
        var def = JsonSerializer.pojoFromJson(document, WorkflowDefinition.class);
        return def.withSteps(def.steps().stream().map(s -> s.withWorkflowDefinitionId(def.id())).toList());
    }
    TestProcess start(WorkflowTestRuntime runtime, WorkflowDefinition def, List<Variable> variables) {
        String id = UUID.randomUUID().toString();
        tests.start(id, def, variables, "synthetic-digest", new AuthorizationContext("ide-author", List.of(), List.of("ide-tester")));
        runtime.runUntilIdle();
        return runtime.process("ide-test:"+id);
    }
    WorkflowDefinition child(String id, String target) {
        return definition("""
            {"id":"%s","name":"Synthetic child","version":1,"steps":[
             {"id":"start","type":"START","name":"Start"},
             {"id":"child-work","name":"Child work","type":"%s","%s":"%s","preconditionStepId":"start"},
             {"id":"end","type":"END","name":"End","preconditionStepId":"child-work"}]}
            """.formatted(id,target==null?"ACTION":"PROCESS",target==null?"topic":"childWorkflowDefinitionId",target==null?"synthetic":target));
    }

    @Test void everyCurrentStepTypeRunsFromAnUnpublishedSnapshotWithNestedChildrenAndRealWaits(WorkflowTestRuntime runtime) throws Exception {
        definitions.save(child("ide-grandchild", null));
        definitions.save(child("ide-child", "ide-grandchild"));
        runtime.whenStep("rule").thenComplete();
        runtime.whenStep("a").thenComplete(); runtime.whenStep("b").thenComplete();
        runtime.whenStep("child-work").thenComplete();
        var http = runtime.whenHttpCall("http").thenRespond(503,"busy").thenRespond(200,"ok");
        runtime.whenLlmCall("llm").thenAnswer("synthetic");
        runtime.whenAgent("agent").thenReviewRequired().thenOutput("{\"ok\":true}");
        String source;
        try(var stream = getClass().getResourceAsStream("/ide-execution/all-types.json")) {
            source = new String(Objects.requireNonNull(stream).readAllBytes(), StandardCharsets.UTF_8);
        }
        var draft = definition(source);
        var run = start(runtime, draft, List.of(new Variable("bookingId", "IDE-1"), new Variable("items", "[1,2]")));
        assertThat(run.stepExecutions("wait")).singleElement().satisfies(s -> assertThat(s.getStatus()).isEqualTo(StepExecutionStatus.PENDING));
        runtime.sendMessage("synthetic-go", "IDE-1");
        runtime.advanceTime(Duration.ofMinutes(1));
        assertThat(run.status()).isEqualTo(ProcessStatus.PAUSED);
        runtime.resolveAgentReview(run,"agent");
        assertThat(run.stepExecutions("human")).singleElement().satisfies(s -> assertThat(s.getStatus()).isEqualTo(StepExecutionStatus.PENDING));
        runtime.completeUserTask(run,"human",Map.of("approved",true));
        runtime.advanceTime(Duration.ofSeconds(2));
        var dynamic = run.stepExecutions("dynamic").getFirst();
        inject.handle(new InjectStepsCommand(dynamic.id(), """
            [{"id":"injected-end","type":"END","name":"Injected end","preconditionStepId":"dynamic"}]
            """));
        runtime.completeTask(run,"dynamic",Map.of());
        assertThat(run.status()).isEqualTo(ProcessStatus.COMPLETED);
        assertThat(definitions.findById(draft.id())).isEmpty();
        assertThat(snapshots.read(run.state().getWorkflowDefinitionJson()).version()).isEqualTo(17);
        assertThat(http.requests()).hasSize(2);
        assertThat(run.stepExecutions("http").getFirst().getAttemptCount()).isGreaterThanOrEqualTo(1);
        assertThat(run.stepExecutions("injected-end").getFirst().getInjectedByStepExecutionId()).isEqualTo(dynamic.id());
        var children = processes.findAll().stream().filter(p -> p.getWorkflowDefinitionId().startsWith("ide-") && p.getParentStepExecutionId()!=null).toList();
        assertThat(children).hasSize(4).allSatisfy(p -> {
            assertThat(p.isTestRun()).isTrue(); assertThat(p.getInitiatingActor()).isEqualTo("ide-author");
        });
        var executed = EnumSet.noneOf(StepType.class);
        run.stepExecutions().stream().filter(s -> s.getStatus()==StepExecutionStatus.COMPLETED)
            .map(s -> JsonSerializer.pojoFromJson(s.getStepJson(),Step.class).type()).forEach(executed::add);
        var expected = EnumSet.allOf(StepType.class); expected.remove(StepType.REMOVED); expected.remove(StepType.CONTINUE_AS_NEW);
        assertThat(executed).containsAll(expected);
    }

    @Test void sameKeepsTheDraftAndTheTestMarkerWhileLatestIsRefused(WorkflowTestRuntime runtime) {
        runtime.whenStep("bill").thenComplete();
        var published = definitions.findById("subscription").orElseThrow();
        var latest = published.withSteps(published.steps().stream()
            .map(s -> s.type()==StepType.CONTINUE_AS_NEW?s.withContinueAsNew(new ContinueAsNew("latest",null)):s).toList());
        assertThatThrownBy(() -> start(runtime,latest,List.of(new Variable("customerId","IDE-latest"),new Variable("period","0"))))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("latest");
        for (String mode : List.of("same")) {
            var draft = published.withVersion(81).withSteps(published.steps().stream()
                .map(s -> s.type()==StepType.CONTINUE_AS_NEW?s.withContinueAsNew(new ContinueAsNew(mode,null)):s).toList());
            var first = start(runtime,draft,List.of(new Variable("customerId","IDE-"+mode),new Variable("period","0")));
            String originalKey = first.businessKey();
            runtime.advanceTime(Duration.ofDays(31));
            var next = runtime.process(originalKey);
            assertThat(next.state().getContinuedFromProcessId()).isEqualTo(first.id());
            assertThat(next.state().isTestRun()).isTrue();
            assertThat(next.state().getInitiatingActor()).isEqualTo("ide-author");
            assertThat(next.state().getWorkflowDefinitionVersion()).isEqualTo(81);
            assertThat(next.state().getWorkflowDefinitionId()).startsWith("vscode-subscription-");
            assertThat(definitions.findById("subscription").orElseThrow().version()).isEqualTo(published.version());
        }
    }

    @Test void repeatsAndLoopBacksKeepTheirSeparatePassesOnTheDraft(WorkflowTestRuntime runtime) {
        runtime.whenStep("poll").thenComplete(Map.of("inStock","no")).thenComplete(Map.of("inStock","yes"));
        var poll = start(runtime,definitions.findById("stock-poll").orElseThrow().withVersion(91),List.of());
        runtime.advanceTime(Duration.ofHours(2));
        assertThat(poll.status()).isEqualTo(ProcessStatus.COMPLETED);
        assertThat(poll.stepExecutions("poll")).hasSize(2);
        runtime.whenStep("a").thenComplete(); runtime.whenStep("b").thenComplete();
        runtime.whenStep("review").thenComplete(Map.of("again","yes")).thenComplete(Map.of("again","no"));
        var loop = start(runtime,definitions.findById("daily-review").orElseThrow().withVersion(92),List.of());
        runtime.advanceTime(Duration.ofHours(6));
        assertThat(loop.status()).isEqualTo(ProcessStatus.COMPLETED);
        assertThat(loop.stepExecutions("review")).hasSize(2);
        assertThat(loop.stepExecutions("a")).extracting(s -> s.getIteration()).contains(0,1);
        assertThat(loop.state().getWorkflowDefinitionVersion()).isEqualTo(92);
    }

    @Test void repeatedTestRunsOpenNoMetricSeriesPerRun(WorkflowTestRuntime runtime) {
        var draft = child("ide-metrics", null);
        runtime.whenStep("child-work").thenComplete();
        start(runtime, draft, List.of());
        int afterFirst = meters.getMeters().size();
        var started = meters.find("eventconductor.process.started").tag("workflowDefinitionId", "vscode-test-run").counter();
        assertThat(started).isNotNull();
        double before = started.count();
        for (int i = 0; i < 3; i++) start(runtime, draft, List.of());
        assertThat(meters.getMeters().size()).isEqualTo(afterFirst);
        assertThat(started.count() - before).isEqualTo(3);
        assertThat(meters.getMeters()).noneMatch(meter -> {
            String tag = meter.getId().getTag("workflowDefinitionId");
            return tag != null && tag.startsWith("vscode-") && !tag.equals("vscode-test-run");
        });
    }

    @Test void aMissingChildAndAnUnavailableWorkerSurfaceTheirNormalFailuresAndWaits(WorkflowTestRuntime runtime) {
        assertThatThrownBy(() -> start(runtime,child("ide-missing-child","not-deployed"),List.of()))
            .isInstanceOf(UnknownWorkflowDefinitionException.class).hasMessageContaining("not-deployed");
        var waiting = start(runtime,child("ide-unavailable-worker",null),List.of());
        assertThat(waiting.status()).isEqualTo(ProcessStatus.RUNNING);
        assertThat(waiting.stepExecutions("child-work").getFirst().getStatus()).isEqualTo(StepExecutionStatus.PENDING);
    }
}
