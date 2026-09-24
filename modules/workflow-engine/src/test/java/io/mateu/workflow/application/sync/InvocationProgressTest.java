package io.mateu.workflow.application.sync;

import io.mateu.core.infra.JsonSerializer;
import io.mateu.workflow.domain.aggregates.LogMessage;
import io.mateu.workflow.domain.aggregates.Process;
import io.mateu.workflow.domain.aggregates.ProcessStatus;
import io.mateu.workflow.domain.aggregates.Step;
import io.mateu.workflow.domain.aggregates.StepExecution;
import io.mateu.workflow.domain.aggregates.StepExecutionStatus;
import io.mateu.workflow.domain.aggregates.StepType;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class InvocationProgressTest {

    private static final LocalDateTime T0 = LocalDateTime.now().minusSeconds(30);

    private static StepExecution step(String id, StepExecutionStatus status, LocalDateTime finishedAt) {
        var step = new Step(id, "wd", StepType.ACTION, "Step " + id, null, null, null, null, false, null, null,
                null, null, null, 0, null, null, null, null, 0, 0, false, null, 0, null);
        return StepExecution.builder().id("se-" + id).stepId(id).processId("p").stepJson(JsonSerializer.toJson(step))
                .status(status).startedAt(T0).finishedAt(finishedAt).variables(List.of()).build();
    }

    private static LogMessage log(String id, LocalDateTime at) {
        return LogMessage.builder().id(id).timestamp(at).processId("p").stepExecutionId("se-a")
                .messageType("Info").message("hello " + id).build();
    }

    private static Process process(ProcessStatus status) {
        return Process.builder().id("p").status(status).variables(List.of()).build();
    }

    @Test
    void eachFactIsSentOnceWithRisingIds() {
        var progress = new InvocationProgress(null);
        var first = progress.next(process(ProcessStatus.RUNNING),
                List.of(step("a", StepExecutionStatus.COMPLETED, T0.plusSeconds(1)), step("b", StepExecutionStatus.CREATED, null)),
                List.of(log("l1", T0.plusSeconds(2))));
        assertThat(first).extracting(InvocationProgress.Event::name).containsExactly("status", "step", "log");

        var second = progress.next(process(ProcessStatus.RUNNING),
                List.of(step("a", StepExecutionStatus.COMPLETED, T0.plusSeconds(1)), step("b", StepExecutionStatus.PENDING, null)),
                List.of(log("l1", T0.plusSeconds(2))));
        assertThat(second).extracting(InvocationProgress.Event::name).containsExactly("step");
        assertThat(((Map<?, ?>) second.getFirst().data()).get("key")).isEqualTo("step:se-b:PENDING");

        var ids = new java.util.ArrayList<String>();
        first.forEach(e -> ids.add(e.id()));
        second.forEach(e -> ids.add(e.id()));
        assertThat(ids).isSorted();
        assertThat(progress.next(process(ProcessStatus.COMPLETED), List.of(), List.of()))
                .extracting(InvocationProgress.Event::name).containsExactly("status");
    }

    @Test
    void aResumeLooksBackAWindowAndDropsOlderFacts() {
        var now = LocalDateTime.now();
        var lastSeen = now.atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();
        var progress = new InvocationProgress(String.format("%013d-%06d", lastSeen, 7));
        var events = progress.next(process(ProcessStatus.RUNNING),
                List.of(step("old", StepExecutionStatus.COMPLETED, now.minusMinutes(5)),
                        step("recent", StepExecutionStatus.COMPLETED, now.minusSeconds(2))),
                List.of(log("ancient", now.minusMinutes(10)), log("fresh", now.plusSeconds(1))));
        assertThat(events).extracting(e -> (Object) ((Map<?, ?>) e.data()).get("key"))
                .contains("step:se-recent:COMPLETED", "log:fresh")
                .doesNotContain("step:se-old:COMPLETED", "log:ancient");
    }

    @Test
    void aGarbledResumeIdStartsFromTheBeginning() {
        var events = new InvocationProgress("not-a-cursor").next(process(ProcessStatus.RUNNING),
                List.of(step("a", StepExecutionStatus.COMPLETED, T0)), List.of());
        assertThat(events).hasSize(2);
        assertThat(new InvocationProgress(null).now("reply", Map.of()).name()).isEqualTo("reply");
    }
}
