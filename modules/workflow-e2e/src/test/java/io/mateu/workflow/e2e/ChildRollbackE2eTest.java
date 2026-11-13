package io.mateu.workflow.e2e;

import io.mateu.workflow.application.usecases.process.cancel.CancelProcessCommand;
import io.mateu.workflow.application.usecases.process.parentnotify.ChildProcesses;
import io.mateu.workflow.domain.aggregates.Process;
import io.mateu.workflow.domain.aggregates.ProcessStatus;
import io.mateu.workflow.domain.aggregates.StepExecution;
import io.mateu.workflow.domain.aggregates.StepExecutionStatus;
import io.mateu.workflow.dtos.events.integration.TaskExecutionRequested;
import io.mateu.workflow.e2e.support.AbstractE2eTest;
import io.mateu.workflow.e2e.support.TestWorker;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * A child process the engine cuts short while it runs — its parent rolling back, its forEach failing
 * fast — is rolled back, not just cancelled: its completed compensable steps are undone, and the
 * parent's rollback ends only once the child's has. A child that already completed is undone by the
 * parent's compensation of its PROCESS step instead (never both). An operator's cancel still undoes
 * nothing.
 */
class ChildRollbackE2eTest extends AbstractE2eTest {

    private static final Duration WAIT = Duration.ofSeconds(10);

    private static String var(TaskExecutionRequested request, String name) {
        return request.variables().stream().filter(v -> name.equals(v.name()))
                .map(io.mateu.workflow.dtos.Variable::value).findFirst().orElse(null);
    }

    private List<Process> childrenOf(String businessKey, String stepId) {
        var children = new ArrayList<Process>();
        steps(businessKey).stream().filter(s -> stepId.equals(s.getStepId()) && !s.isContainer())
                .sorted(Comparator.comparing(s -> s.getItemIndex() == null ? -1 : s.getItemIndex()))
                .forEach(s -> children.addAll(ChildProcesses.of(processRepository, s.getId())));
        return children;
    }

    private Process reload(Process process) {
        return processRepository.findById(process.getId()).orElseThrow();
    }

    @Test
    void aChildCutShortByItsParentsRollbackUndoesWhatItDidAndTheParentEndsAfterIt() {
        worker.on("car", (req, cb, n) -> cb.complete());
        worker.on("hold", (req, cb, n) -> cb.complete());
        var confirms = new CopyOnWriteArrayList<TestWorker.WorkerCallback>();
        worker.on("confirm", (req, cb, n) -> confirms.add(cb));
        var flights = new CopyOnWriteArrayList<TestWorker.WorkerCallback>();
        worker.on("flight", (req, cb, n) -> flights.add(cb));
        worker.on("cancel-car", (req, cb, n) -> cb.complete());
        worker.on("cancel-booking", (req, cb, n) -> cb.complete());
        var releases = new CopyOnWriteArrayList<TestWorker.WorkerCallback>();
        worker.on("release-hold", (req, cb, n) -> releases.add(cb));

        createProcess("fork-child-rollback", "child-rb-1");
        // The child held the room and is confirming it when the flight fails.
        await().atMost(WAIT).untilAsserted(() -> assertThat(confirms).hasSize(1));
        await().atMost(WAIT).untilAsserted(() -> assertThat(flights).hasSize(1));
        flights.get(0).fail("no seats");

        // The child is rolled back: its hold is released (held here, so the parent's wait shows).
        await().atMost(WAIT).untilAsserted(() -> assertThat(releases).hasSize(1));
        var child = childrenOf("child-rb-1", "room").get(0);
        assertThat(reload(child).getStatus()).isEqualTo(ProcessStatus.ERROR);
        assertThat(reload(child).isRollbackRequested()).isTrue();
        await().atMost(WAIT).untilAsserted(() -> assertThat(worker.invocationsOf("cancel-car")).isEqualTo(1));
        // The car is undone, but the parent waits for its child to finish undoing.
        assertThat(process("child-rb-1").getStatus()).isEqualTo(ProcessStatus.ERROR);

        releases.get(0).complete();

        await().atMost(WAIT).untilAsserted(() ->
                assertThat(reload(child).getStatus()).isEqualTo(ProcessStatus.COMPENSATED));
        await().atMost(WAIT).untilAsserted(() ->
                assertThat(process("child-rb-1").getStatus()).isEqualTo(ProcessStatus.COMPENSATED));
        // The room was undone inside the child, so the parent's compensation of it does not run too.
        assertThat(worker.invocationsOf("cancel-booking")).isZero();
        assertThat(step("child-rb-1", "room").getStatus()).isEqualTo(StepExecutionStatus.CANCELLED);
    }

    @Test
    void aChildThatAlreadyCompletedIsUndoneByItsParentsCompensationOnly() {
        worker.on("car", (req, cb, n) -> cb.complete());
        worker.on("hold", (req, cb, n) -> cb.complete());
        worker.on("confirm", (req, cb, n) -> cb.complete());
        var flights = new CopyOnWriteArrayList<TestWorker.WorkerCallback>();
        worker.on("flight", (req, cb, n) -> flights.add(cb));
        worker.on("cancel-car", (req, cb, n) -> cb.complete());
        worker.on("cancel-booking", (req, cb, n) -> cb.complete());
        worker.on("release-hold", (req, cb, n) -> cb.complete());

        createProcess("fork-child-rollback", "child-rb-2");
        await().atMost(WAIT).untilAsserted(() ->
                assertThat(step("child-rb-2", "room").getStatus()).isEqualTo(StepExecutionStatus.COMPLETED));
        await().atMost(WAIT).untilAsserted(() -> assertThat(flights).hasSize(1));
        flights.get(0).fail("no seats");

        await().atMost(WAIT).untilAsserted(() ->
                assertThat(process("child-rb-2").getStatus()).isEqualTo(ProcessStatus.COMPENSATED));
        assertThat(worker.invocationsOf("cancel-booking")).isEqualTo(1);
        assertThat(worker.invocationsOf("release-hold")).isZero();
        assertThat(childrenOf("child-rb-2", "room").get(0).getStatus()).isEqualTo(ProcessStatus.COMPLETED);
    }

    @Test
    void theRollbackReachesGrandchildren() {
        worker.on("car", (req, cb, n) -> cb.complete());
        worker.on("deposit", (req, cb, n) -> cb.complete());
        worker.on("hold", (req, cb, n) -> cb.complete());
        var confirms = new CopyOnWriteArrayList<TestWorker.WorkerCallback>();
        worker.on("confirm", (req, cb, n) -> confirms.add(cb));
        var flights = new CopyOnWriteArrayList<TestWorker.WorkerCallback>();
        worker.on("flight", (req, cb, n) -> flights.add(cb));
        worker.on("cancel-car", (req, cb, n) -> cb.complete());
        worker.on("refund-deposit", (req, cb, n) -> cb.complete());
        worker.on("release-hold", (req, cb, n) -> cb.complete());

        createProcess("fork-grandchild-rollback", "child-rb-3");
        await().atMost(WAIT).untilAsserted(() -> assertThat(confirms).hasSize(1));
        await().atMost(WAIT).untilAsserted(() -> assertThat(flights).hasSize(1));
        flights.get(0).fail("no seats");

        await().atMost(WAIT).untilAsserted(() ->
                assertThat(process("child-rb-3").getStatus()).isEqualTo(ProcessStatus.COMPENSATED));
        var child = reload(childrenOf("child-rb-3", "trip").get(0));
        var grandchild = processRepository.findAll().stream()
                .filter(p -> p.getParentStepExecutionId() != null
                        && stepExecutionRepository.findById(p.getParentStepExecutionId())
                        .map(StepExecution::getProcessId).filter(child.getId()::equals).isPresent())
                .findFirst().orElseThrow();
        assertThat(child.getStatus()).isEqualTo(ProcessStatus.COMPENSATED);
        assertThat(grandchild.getStatus()).isEqualTo(ProcessStatus.COMPENSATED);
        assertThat(worker.invocationsOf("release-hold")).isEqualTo(1);
        assertThat(worker.invocationsOf("refund-deposit")).isEqualTo(1);
        assertThat(worker.invocationsOf("cancel-car")).isEqualTo(1);
    }

    @Test
    void aForEachThatFailsFastRollsBackTheChildItCutShort() {
        var holds = new CopyOnWriteArrayList<String>();
        worker.on("hold", (req, cb, n) -> {
            var room = var(req, "room");
            if ("103".equals(room)) {
                cb.fail("no availability");
            } else {
                holds.add(room);
                cb.complete(List.of(TestWorker.var("bookingId", "B-" + room)));
            }
        });
        var confirms = new CopyOnWriteArrayList<String>();
        var pendingConfirms = new CopyOnWriteArrayList<TestWorker.WorkerCallback>();
        worker.on("confirm", (req, cb, n) -> {
            var room = var(req, "room");
            if ("101".equals(room)) {
                confirms.add(room);
                cb.complete();
            } else {
                pendingConfirms.add(cb);
            }
        });
        var released = new CopyOnWriteArrayList<String>();
        worker.on("release-hold", (req, cb, n) -> {
            released.add(var(req, "room"));
            cb.complete();
        });
        var cancelled = new CopyOnWriteArrayList<String>();
        worker.on("cancel-booking", (req, cb, n) -> {
            cancelled.add(var(req, "room") + "/" + var(req, "bookingId"));
            cb.complete();
        });

        // 101 holds and confirms; 102 holds and is still confirming when 103's hold fails for good.
        createProcess("foreach-child-rollback-failfast", "child-rb-4",
                new io.mateu.workflow.dtos.Variable("rooms", "[\"101\",\"102\",\"103\"]"));

        await().atMost(WAIT).untilAsserted(() ->
                assertThat(process("child-rb-4").getStatus()).isEqualTo(ProcessStatus.COMPENSATED));
        // 102 was cut short with its room held: its child rolled back and released it.
        assertThat(released).containsExactly("102");
        // 101 completed: the parent undid it with its own compensation, its child is left alone.
        assertThat(cancelled).containsExactly("101/B-101");
        var children = childrenOf("child-rb-4", "book-rooms");
        assertThat(children).hasSize(3);
        assertThat(children).extracting(c -> reload(c).getStatus())
                .containsExactly(ProcessStatus.COMPLETED, ProcessStatus.COMPENSATED, ProcessStatus.ERROR);
        assertThat(worker.invocationsOf("notify")).isZero();
    }

    @Test
    void anEndThatCompensatesWhatItAbandonsRollsBackTheChildItCutShort() {
        worker.on("hold", (req, cb, n) -> cb.complete());
        var confirms = new CopyOnWriteArrayList<TestWorker.WorkerCallback>();
        worker.on("confirm", (req, cb, n) -> confirms.add(cb));
        var quick = new CopyOnWriteArrayList<TestWorker.WorkerCallback>();
        worker.on("quick", (req, cb, n) -> quick.add(cb));
        worker.on("release-hold", (req, cb, n) -> cb.complete());
        worker.on("cancel-booking", (req, cb, n) -> cb.complete());

        createProcess("fork-child-end-abandoned", "child-rb-6");
        await().atMost(WAIT).untilAsserted(() -> assertThat(confirms).hasSize(1));
        await().atMost(WAIT).untilAsserted(() -> assertThat(quick).hasSize(1));
        quick.get(0).complete();

        await().atMost(WAIT).untilAsserted(() ->
                assertThat(process("child-rb-6").getStatus()).isEqualTo(ProcessStatus.COMPLETED));
        var child = childrenOf("child-rb-6", "room").get(0);
        await().atMost(WAIT).untilAsserted(() ->
                assertThat(reload(child).getStatus()).isEqualTo(ProcessStatus.COMPENSATED));
        assertThat(worker.invocationsOf("release-hold")).isEqualTo(1);
        assertThat(worker.invocationsOf("cancel-booking")).isZero();
    }

    @Test
    void anOperatorCancellingTheParentCancelsTheChildWithoutRollingItBack() {
        worker.on("car", (req, cb, n) -> cb.complete());
        worker.on("hold", (req, cb, n) -> cb.complete());
        var confirms = new CopyOnWriteArrayList<TestWorker.WorkerCallback>();
        worker.on("confirm", (req, cb, n) -> confirms.add(cb));
        worker.on("flight", (req, cb, n) -> { });
        worker.on("release-hold", (req, cb, n) -> cb.complete());
        worker.on("cancel-car", (req, cb, n) -> cb.complete());

        var parent = createProcess("fork-child-rollback", "child-rb-5");
        await().atMost(WAIT).untilAsserted(() -> assertThat(confirms).hasSize(1));

        cancelProcessUseCase.handle(new CancelProcessCommand(parent.getId()));

        await().atMost(WAIT).untilAsserted(() ->
                assertThat(reload(childrenOf("child-rb-5", "room").get(0)).getStatus()).isEqualTo(ProcessStatus.CANCELLED));
        assertThat(process("child-rb-5").getStatus()).isEqualTo(ProcessStatus.CANCELLED);
        assertThat(reload(childrenOf("child-rb-5", "room").get(0)).isRollbackRequested()).isFalse();
        assertThat(worker.invocationsOf("release-hold")).isZero();
        assertThat(worker.invocationsOf("cancel-car")).isZero();
    }
}
