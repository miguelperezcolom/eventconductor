package io.mateu.workflow.e2e.support;

import io.mateu.workflow.application.out.ProcessLockService;
import io.mateu.workflow.application.out.ProcessRepository;
import io.mateu.workflow.application.out.ProcessUpdateRepository;
import io.mateu.workflow.application.out.StepExecutionRepository;
import io.mateu.workflow.application.updates.ProcessUpdate;
import io.mateu.workflow.application.updates.ProcessUpdateService;
import io.mateu.workflow.domain.aggregates.Process;
import io.mateu.workflow.domain.aggregates.ProcessStatus;
import io.mateu.workflow.domain.aggregates.StepExecutionStatus;
import io.mateu.workflow.domain.aggregates.Variable;
import io.mateu.workflow.dtos.events.integration.ProcessCreationRequested;
import io.mateu.workflow.infra.in.async.processupstreamevent.ProcessUpstreamEventCommand;
import io.mateu.workflow.infra.in.async.processupstreamevent.ProcessUpstreamEventUseCase;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static io.mateu.workflow.e2e.support.ProcessUpdatesHttp.json;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * The process-update scenarios, run the same way over memory and over JPA (H2): the {@code order-updates}
 * definition, driven through the REST door, checked against the engine's state and the recorded updates.
 */
public class ProcessUpdateScenario {

    private static final Duration TIMEOUT = Duration.ofSeconds(20);
    private static final String DEFINITION = "order-updates";

    private final ProcessUpdatesHttp http;
    private final ProcessUpstreamEventUseCase upstream;
    private final ProcessRepository processes;
    private final StepExecutionRepository steps;
    private final ProcessUpdateRepository updates;
    private final ProcessLockService locks;
    private final TestWorker worker;

    public ProcessUpdateScenario(ProcessUpdateService service, ProcessUpstreamEventUseCase upstream,
                                 ProcessRepository processes, StepExecutionRepository steps,
                                 ProcessUpdateRepository updates, ProcessLockService locks, TestWorker worker) {
        this.http = new ProcessUpdatesHttp(service);
        this.upstream = upstream;
        this.processes = processes;
        this.steps = steps;
        this.updates = updates;
        this.locks = locks;
        this.worker = worker;
    }

    // ── helpers ─────────────────────────────────────────────────────────────────────────────────

    /** Starts an order and waits until it waits for shipping. */
    public Process waitingOrder(String businessKey, Variable... variables) {
        upstream.handle(new ProcessUpstreamEventCommand(new ProcessCreationRequested(DEFINITION, businessKey,
                List.of(variables).stream().map(v -> new io.mateu.workflow.dtos.Variable(v.name(), v.value())).toList())));
        await().atMost(TIMEOUT).until(() -> processes.findByBusinessKey(businessKey)
                .map(p -> steps.findByProcessId(p.getId()).stream().anyMatch(s -> "awaitShipping".equals(s.getStepId())
                        && s.getStatus() == StepExecutionStatus.PENDING && s.getStartedAt() != null))
                .orElse(false));
        return process(businessKey);
    }

    public Process process(String businessKey) {
        return processes.findByBusinessKey(businessKey).orElseThrow();
    }

    public String variable(String businessKey, String name) {
        return process(businessKey).getVariables().stream().filter(v -> name.equals(v.name()))
                .map(Variable::value).findFirst().orElse(null);
    }

    private void awaitStatus(String businessKey, ProcessStatus status) {
        await().atMost(TIMEOUT).until(() -> processes.findByBusinessKey(businessKey)
                .map(Process::getStatus).filter(status::equals).isPresent());
    }

    private static String address(String value) {
        return "{\"address\": \"" + value + "\"}";
    }

    // ── scenarios ───────────────────────────────────────────────────────────────────────────────

    /** Applied: 200 with the reply, the variables written, the update recorded with its result. */
    public void anAcceptedUpdateAnswersWithItsResult() throws Exception {
        var order = waitingOrder("upd-ok", new Variable("address", "Old 1"));

        var response = http.update(order.getId(), "changeAddress", address("Elm 2"));

        assertThat(response.getStatus()).isEqualTo(200);
        var body = json(response);
        assertThat(body.path("status").asText()).isEqualTo("COMPLETED");
        assertThat(body.path("result").path("address").asText()).isEqualTo("Elm 2");
        assertThat(body.path("result").path("history").asText()).isEqualTo("Elm 2");
        assertThat(body.path("location").asText()).isEqualTo("/workflow/api/updates/" + body.path("updateId").asText());
        assertThat(variable("upd-ok", "address")).isEqualTo("Elm 2");
        var recorded = updates.findByProcessId(order.getId());
        assertThat(recorded).singleElement().satisfies(update -> {
            assertThat(update.status()).isEqualTo(ProcessUpdate.Status.COMPLETED);
            assertThat(update.result()).contains("Elm 2");
            assertThat(update.completedAt()).isNotNull();
        });
        var listed = json(http.get("/workflow/api/processes/" + order.getId() + "/updates", null));
        assertThat(listed).hasSize(1);
        assertThat(listed.get(0).path("name").asText()).isEqualTo("changeAddress");
        var read = http.get("/workflow/api/updates/" + body.path("updateId").asText(), null);
        assertThat(read.getStatus()).isEqualTo(200);
        assertThat(json(read).path("result").path("address").asText()).isEqualTo("Elm 2");
        // The update did not move the flow: the order still waits for shipping.
        assertThat(process("upd-ok").getStatus()).isEqualTo(ProcessStatus.RUNNING);
    }

    /** A payload the schema does not accept: 400, nothing recorded, nothing changed. */
    public void aPayloadTheSchemaRefusesIsA400() throws Exception {
        var order = waitingOrder("upd-schema", new Variable("address", "Old 1"));

        var wrongType = http.update(order.getId(), "changeAddress", "{\"address\": 12}");
        var missing = http.update(order.getId(), "changeAddress", "{}");
        var notJson = http.update(order.getId(), "changeAddress", "{not json");

        assertThat(wrongType.getStatus()).isEqualTo(400);
        assertThat(json(wrongType).path("reason").asText()).isEqualTo("INVALID_PAYLOAD");
        assertThat(missing.getStatus()).isEqualTo(400);
        assertThat(notJson.getStatus()).isEqualTo(400);
        assertThat(updates.findByProcessId(order.getId())).isEmpty();
        assertThat(variable("upd-schema", "address")).isEqualTo("Old 1");
    }

    /** A guard that does not hold: 422 with its message, recorded as REFUSED, nothing changed. */
    public void aGuardThatSaysNoIsA422() throws Exception {
        var order = waitingOrder("upd-guard", new Variable("address", "Same 1"));

        var response = http.update(order.getId(), "changeAddress", address("Same 1"));

        assertThat(response.getStatus()).isEqualTo(422);
        var problem = json(response);
        assertThat(problem.path("reason").asText()).isEqualTo("REFUSED");
        assertThat(problem.path("detail").asText()).isEqualTo("That is already the shipping address.");
        assertThat(variable("upd-guard", "addressHistory")).isNull();
        assertThat(updates.findByProcessId(order.getId())).singleElement()
                .extracting(ProcessUpdate::status).isEqualTo(ProcessUpdate.Status.REFUSED);
    }

    /** Not accepting: the step the update needs is not waiting (409, recorded), and a finished process (409). */
    public void aProcessThatIsNotAcceptingIsA409() throws Exception {
        worker.on("reserve", TestWorker.deferForever());
        upstream.handle(new ProcessUpstreamEventCommand(new ProcessCreationRequested(DEFINITION, "upd-early", List.of())));
        await().atMost(TIMEOUT).until(() -> processes.findByBusinessKey("upd-early").isPresent()
                && worker.invocationsOf("reserve") > 0);
        var early = process("upd-early");

        var tooEarly = http.update(early.getId(), "changeAddress", address("Elm 2"));

        assertThat(tooEarly.getStatus()).isEqualTo(409);
        assertThat(json(tooEarly).path("reason").asText()).isEqualTo("NOT_ACCEPTING");
        assertThat(json(tooEarly).path("detail").asText()).contains("awaitShipping");
        // An update with no window is accepted all the same.
        assertThat(http.update(early.getId(), "addNote", "{\"note\": \"early\"}").getStatus()).isEqualTo(200);

        worker.on("reserve", TestWorker.succeed());
        var done = waitingOrder("upd-done");
        assertThat(http.update(done.getId(), "approveShipping", "{\"by\": \"ana\"}").getStatus()).isEqualTo(200);
        awaitStatus("upd-done", ProcessStatus.COMPLETED);
        var finished = http.update(done.getId(), "addNote", "{\"note\": \"late\"}");
        assertThat(finished.getStatus()).isEqualTo(409);
        assertThat(json(finished).path("detail").asText()).contains("COMPLETED");
    }

    /** Several updates over the process's life, applied in the order they were sent. */
    public void severalUpdatesApplyInOrder() throws Exception {
        var order = waitingOrder("upd-many", new Variable("address", "A0"));

        for (var address : List.of("Ave 1", "Ave 2", "Ave 3")) {
            assertThat(http.update(order.getId(), "changeAddress", address(address)).getStatus()).isEqualTo(200);
        }

        assertThat(variable("upd-many", "addressHistory")).isEqualTo("Ave 1,Ave 2,Ave 3");
        assertThat(updates.findByProcessId(order.getId())).extracting(ProcessUpdate::status)
                .containsOnly(ProcessUpdate.Status.COMPLETED).hasSize(3);
    }

    /** Concurrent updates of one process are serialized by its lock: none is lost. */
    public void concurrentUpdatesAreSerialized() throws Exception {
        var order = waitingOrder("upd-concurrent");
        var pool = Executors.newFixedThreadPool(8);
        var calls = new ArrayList<Callable<Integer>>();
        for (int i = 0; i < 8; i++) {
            var note = "n" + i;
            calls.add(() -> http.update(order.getId(), "addNote", "{\"note\": \"" + note + "\"}").getStatus());
        }
        try {
            for (var future : pool.invokeAll(calls)) {
                assertThat(future.get()).isEqualTo(200);
            }
        } finally {
            pool.shutdownNow();
        }

        var notes = new HashSet<>(List.of(variable("upd-concurrent", "notes").split(",")));
        assertThat(notes).containsExactlyInAnyOrder("n0", "n1", "n2", "n3", "n4", "n5", "n6", "n7");
    }

    /** A retry with the key gets the recorded outcome, applied once; the key with another payload is a 422. */
    public void aRetryWithTheKeyIsTheSameUpdate() throws Exception {
        var order = waitingOrder("upd-retry", new Variable("address", "A0"));

        var first = http.update(order.getId(), "changeAddress", address("Bay 1"), "key-1", "wait=10");
        var again = http.update(order.getId(), "changeAddress", "{ \"address\" : \"Bay 1\" }", "key-1", "wait=10");
        var other = http.update(order.getId(), "changeAddress", address("Bay 2"), "key-1", "wait=10");

        assertThat(first.getStatus()).isEqualTo(200);
        assertThat(again.getStatus()).isEqualTo(200);
        assertThat(json(again).path("updateId").asText()).isEqualTo(json(first).path("updateId").asText());
        assertThat(variable("upd-retry", "addressHistory")).isEqualTo("Bay 1");
        assertThat(other.getStatus()).isEqualTo(422);
        assertThat(json(other).path("reason").asText()).isEqualTo("IDEMPOTENCY_KEY_REUSED");
        assertThat(updates.findByProcessId(order.getId())).hasSize(1);

        // A refusal is replayed too.
        var refused = http.update(order.getId(), "changeAddress", address("Bay 1"), "key-2", "wait=10");
        var refusedAgain = http.update(order.getId(), "changeAddress", address("Bay 1"), "key-2", "wait=10");
        assertThat(refused.getStatus()).isEqualTo(422);
        assertThat(refusedAgain.getStatus()).isEqualTo(422);
        assertThat(json(refusedAgain).path("updateId").asText()).isEqualTo(json(refused).path("updateId").asText());
    }

    /** The deadline passes while the process is busy: 202 + Location; the update completes, durably, after. */
    public void theDeadlineIsA202AndTheUpdateCompletesAfter() throws Exception {
        var order = waitingOrder("upd-deadline", new Variable("address", "A0"));
        var holding = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var holder = Executors.newSingleThreadExecutor();
        try {
            holder.submit(() -> locks.runExclusively(order.getId(), () -> {
                holding.countDown();
                try {
                    release.await(15, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
            assertThat(holding.await(10, TimeUnit.SECONDS)).isTrue();

            var response = http.update(order.getId(), "changeAddress", address("Late 1"), "late-key", "wait=1");

            assertThat(response.getStatus()).isEqualTo(202);
            var location = response.getHeader("Location");
            assertThat(location).startsWith("/workflow/api/updates/");
            assertThat(json(response).path("status").asText()).isEqualTo("REQUESTED");
            assertThat(variable("upd-deadline", "address")).isEqualTo("A0");

            release.countDown();
            var later = http.get(location, "wait=15");
            assertThat(later.getStatus()).isEqualTo(200);
            assertThat(json(later).path("result").path("address").asText()).isEqualTo("Late 1");
            assertThat(variable("upd-deadline", "address")).isEqualTo("Late 1");
        } finally {
            release.countDown();
            holder.shutdownNow();
        }
    }

    /** With no process for the key, the update starts one (its set seeds the variables); with one, it goes there. */
    /**
     * Update-with-start under businessKeyPolicy (2.33): while the holder runs, the update goes to it; once it has
     * ended, a definition that reuses keys starts a new run with the update, while a {@code unique} one sends the
     * update to the ended process, which refuses it.
     */
    public void updateWithStartFollowsTheBusinessKeyPolicy(
            io.mateu.workflow.application.usecases.process.cancel.CancelProcessUseCase cancel) throws Exception {
        var first = http.updateWithStart("order-updates-reuse", """
                {"businessKey": "uws-reuse-1", "variables": {"address": "Initial 1"}, "update": "changeAddress",
                 "payload": {"address": "First 2"}}""", null);
        assertThat(first.getStatus()).isEqualTo(200);
        var firstId = json(first).path("processId").asText();
        waitingOrder("uws-reuse-1");
        var toRunning = http.updateWithStart("order-updates-reuse", """
                {"businessKey": "uws-reuse-1", "update": "changeAddress", "payload": {"address": "Second 3"}}""", null);
        assertThat(json(toRunning).path("processId").asText()).isEqualTo(firstId);

        cancel.handle(new io.mateu.workflow.application.usecases.process.cancel.CancelProcessCommand(firstId));
        await().atMost(TIMEOUT).until(() -> processes.findById(firstId).orElseThrow().getStatus() == ProcessStatus.CANCELLED);
        var restarted = http.updateWithStart("order-updates-reuse", """
                {"businessKey": "uws-reuse-1", "variables": {"address": "Again 1"}, "update": "changeAddress",
                 "payload": {"address": "Again 2"}}""", null);
        assertThat(restarted.getStatus()).isEqualTo(200);
        assertThat(json(restarted).path("started").asBoolean()).isTrue();
        var secondId = json(restarted).path("processId").asText();
        assertThat(secondId).isNotEqualTo(firstId);
        assertThat(processes.findByBusinessKey("uws-reuse-1").orElseThrow().getId()).isEqualTo(secondId);
        assertThat(processes.findAllByBusinessKey("uws-reuse-1")).hasSize(2);

        // unique (order-updates): the ended holder is the target, and it refuses.
        var kept = http.updateWithStart(DEFINITION, """
                {"businessKey": "uws-unique-1", "variables": {"address": "Initial 1"}, "update": "changeAddress",
                 "payload": {"address": "First 2"}}""", null);
        var keptId = json(kept).path("processId").asText();
        waitingOrder("uws-unique-1");
        cancel.handle(new io.mateu.workflow.application.usecases.process.cancel.CancelProcessCommand(keptId));
        await().atMost(TIMEOUT).until(() -> processes.findById(keptId).orElseThrow().getStatus() == ProcessStatus.CANCELLED);
        var refused = http.updateWithStart(DEFINITION, """
                {"businessKey": "uws-unique-1", "update": "changeAddress", "payload": {"address": "Again 2"}}""", null);
        assertThat(refused.getStatus()).isEqualTo(409);
        assertThat(processes.findAllByBusinessKey("uws-unique-1")).hasSize(1);
    }

    public void updateWithStartStartsOrUpdates() throws Exception {
        var started = http.updateWithStart(DEFINITION, """
                {"businessKey": "uws-1", "variables": {"address": "Initial 1"}, "update": "changeAddress",
                 "payload": {"address": "First 2"}}""", "uws-key");

        assertThat(started.getStatus()).isEqualTo(200);
        assertThat(json(started).path("started").asBoolean()).isTrue();
        assertThat(json(started).path("result").path("address").asText()).isEqualTo("First 2");
        var processId = json(started).path("processId").asText();
        assertThat(process("uws-1").getId()).isEqualTo(processId);
        assertThat(variable("uws-1", "address")).isEqualTo("First 2");
        assertThat(updates.findByProcessId(processId)).singleElement().satisfies(update -> {
            assertThat(update.withStart()).isTrue();
            assertThat(update.status()).isEqualTo(ProcessUpdate.Status.COMPLETED);
        });

        // A retry of the same update-with-start: the same update, nothing started twice.
        var retried = http.updateWithStart(DEFINITION, """
                {"businessKey": "uws-1", "variables": {"address": "Initial 1"}, "update": "changeAddress",
                 "payload": {"address": "First 2"}}""", "uws-key");
        assertThat(retried.getStatus()).isEqualTo(200);
        assertThat(json(retried).path("updateId").asText()).isEqualTo(json(started).path("updateId").asText());
        assertThat(json(retried).path("started").asBoolean()).isTrue();

        // The process exists now: the next update-with-start is an ordinary update of it.
        waitingOrder("uws-1");
        var existing = http.updateWithStart(DEFINITION, """
                {"businessKey": "uws-1", "update": "changeAddress", "payload": {"address": "Second 3"}}""", null);
        assertThat(existing.getStatus()).isEqualTo(200);
        assertThat(json(existing).path("started").isMissingNode()).isTrue();
        assertThat(json(existing).path("processId").asText()).isEqualTo(processId);
        assertThat(variable("uws-1", "addressHistory")).isEqualTo("First 2,Second 3");
        assertThat(processes.findAll().stream().filter(p -> "uws-1".equals(p.getBusinessKey()))).hasSize(1);

        // A refused update-with-start starts nothing.
        var refused = http.updateWithStart(DEFINITION, """
                {"businessKey": "uws-2", "variables": {"address": "Same 1"}, "update": "changeAddress",
                 "payload": {"address": "Same 1"}}""", null);
        assertThat(refused.getStatus()).isEqualTo(422);
        assertThat(processes.findByBusinessKey("uws-2")).isEmpty();

        // A key held by another definition's process is a conflict.
        upstream.handle(new ProcessUpstreamEventCommand(new ProcessCreationRequested("compensation", "uws-3", List.of())));
        var conflict = http.updateWithStart(DEFINITION, """
                {"businessKey": "uws-3", "update": "addNote", "payload": {"note": "x"}}""", null);
        assertThat(conflict.getStatus()).isEqualTo(409);
        assertThat(json(conflict).path("reason").asText()).isEqualTo("BUSINESS_KEY_TAKEN");
    }

    /**
     * Compensation interplay: the rollback works on the state the updates left (the compensation step sees the
     * new address), and a process that failed takes no more updates.
     */
    public void compensationSeesUpdatesAndAFailedProcessRefusesThem() throws Exception {
        worker.on("ship", TestWorker.fail());
        var order = waitingOrder("upd-saga", new Variable("address", "A0"));
        assertThat(http.update(order.getId(), "changeAddress", address("Saga 1")).getStatus()).isEqualTo(200);

        var approved = http.update(order.getId(), "approveShipping", "{\"by\": \"ana\"}");
        assertThat(approved.getStatus()).isEqualTo(200);
        assertThat(json(approved).path("result").path("approvedBy").asText()).isEqualTo("ana");

        awaitStatus("upd-saga", ProcessStatus.COMPENSATED);
        var release = worker.received().stream().filter(r -> "release".equals(r.stepId())
                && order.getId().equals(r.processId())).findFirst().orElseThrow();
        assertThat(release.variables()).anySatisfy(v -> {
            assertThat(v.name()).isEqualTo("address");
            assertThat(v.value()).isEqualTo("Saga 1");
        });
        var late = http.update(order.getId(), "addNote", "{\"note\": \"too late\"}");
        assertThat(late.getStatus()).isEqualTo(409);
        assertThat(json(late).path("detail").asText()).contains("COMPENSATED");
    }

    /** completes resumes the waiting step: the flow moves on to the end. */
    public void anUpdateCanResumeTheFlow() throws Exception {
        var order = waitingOrder("upd-resume");

        var response = http.updateByKey("upd-resume", "approveShipping", "{\"by\": \"luis\"}");

        assertThat(response.getStatus()).isEqualTo(200);
        awaitStatus("upd-resume", ProcessStatus.COMPLETED);
        assertThat(variable("upd-resume", "approvedBy")).isEqualTo("luis");
        // The step it resumed is no longer waiting: the same update again is not accepted.
        assertThat(updates.findByProcessId(order.getId())).hasSize(1);
    }

    /** An expression that fails: 500 FAILED, nothing changed; unknown update and process: 404; masked payloads. */
    public void failuresUnknownsAndMasking() throws Exception {
        var order = waitingOrder("upd-misc");

        var broken = http.update(order.getId(), "broken", null);
        assertThat(broken.getStatus()).isEqualTo(500);
        assertThat(json(broken).path("reason").asText()).isEqualTo("FAILED");
        assertThat(variable("upd-misc", "boom")).isNull();

        assertThat(http.update(order.getId(), "nope", "{}").getStatus()).isEqualTo(404);
        assertThat(http.update("no-such-process", "addNote", "{}").getStatus()).isEqualTo(404);
        assertThat(http.updateByKey("no-such-key", "addNote", "{}").getStatus()).isEqualTo(404);
        assertThat(http.get("/workflow/api/updates/nope", null).getStatus()).isEqualTo(404);

        var noted = http.update(order.getId(), "addNote", "{\"note\": \"hi\", \"secret\": \"4111-1111\"}");
        assertThat(noted.getStatus()).isEqualTo(200);
        assertThat(json(noted).path("payload").path("secret").asText()).isEqualTo("••••");
        assertThat(updates.findByProcessId(order.getId()).stream().map(ProcessUpdate::payload))
                .noneMatch(payload -> payload != null && payload.contains("4111"));
    }
}
